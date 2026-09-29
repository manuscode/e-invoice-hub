package io.github.manuscode.invoicehub.delivery;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.github.tomakehurst.wiremock.client.MappingBuilder;
import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import com.github.tomakehurst.wiremock.matching.RequestPatternBuilder;
import com.github.tomakehurst.wiremock.stubbing.Scenario;
import com.jayway.jsonpath.JsonPath;
import io.github.manuscode.invoicehub.TestcontainersConfiguration;
import io.github.manuscode.invoicehub.invoice.InvoiceService;
import io.github.manuscode.invoicehub.invoice.InvoiceStatus;
import io.micrometer.core.instrument.MeterRegistry;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.modulith.events.IncompleteEventPublications;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.testcontainers.kafka.KafkaContainer;

@SpringBootTest(properties = {
        "invoice-hub.delivery.retry.delay=100ms",
        "invoice-hub.delivery.retry.multiplier=2",
        // Fail fast while Kafka is unavailable, instead of the default 2 minutes.
        "spring.kafka.producer.properties.max.block.ms=2000",
        "spring.kafka.producer.properties.request.timeout.ms=1000",
        "spring.kafka.producer.properties.delivery.timeout.ms=2000"})
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class ErpDeliveryIntegrationTest {

    private static final String DEAD_LETTER_TOPIC = "invoice-accepted-dlt";
    private static final Duration DELIVERY_TIMEOUT = Duration.ofSeconds(30);

    @RegisterExtension
    static WireMockExtension erp = WireMockExtension.newInstance().options(wireMockConfig().dynamicPort()).build();

    @DynamicPropertySource
    static void erpProperties(DynamicPropertyRegistry registry) {
        registry.add("invoice-hub.erp.base-url", erp::baseUrl);
    }

    @Autowired
    private MockMvcTester mockMvc;

    @Autowired
    private InvoiceService invoiceService;

    @Autowired
    private KafkaContainer kafka;

    @Autowired
    private JdbcClient jdbcClient;

    @Autowired
    private IncompleteEventPublications incompleteEventPublications;

    @Autowired
    private MeterRegistry meterRegistry;

    @Test
    void deliversValidInvoice() throws IOException {
        String invoiceNumber = uniqueInvoiceNumber();
        erp.stubFor(erpCall(invoiceNumber).willReturn(aResponse().withStatus(201)));

        UUID id = uploadValidInvoice(invoiceNumber);

        awaitStatus(id, InvoiceStatus.DELIVERED);
        erp.verify(1, erpRequest(invoiceNumber)
                .withRequestBody(matchingJsonPath("$.invoiceId", equalTo(id.toString())))
                .withRequestBody(matchingJsonPath("$.invoice.seller.vatId", equalTo("DE 123456789"))));
    }

    @Test
    void measuresDeliveryDuration() throws IOException {
        String invoiceNumber = uniqueInvoiceNumber();
        erp.stubFor(erpCall(invoiceNumber).willReturn(aResponse().withStatus(201)));
        long deliveredBefore = meterRegistry.timer("invoicehub.invoices.delivery.duration").count();

        UUID id = uploadValidInvoice(invoiceNumber);

        awaitStatus(id, InvoiceStatus.DELIVERED);
        // Delivered events of other tests may be counted meanwhile.
        await().atMost(DELIVERY_TIMEOUT).untilAsserted(() ->
                assertThat(meterRegistry.timer("invoicehub.invoices.delivery.duration").count())
                        .isGreaterThan(deliveredBefore));
    }

    @Test
    void countsInvoicesInDeadLetterTopic() throws IOException {
        String invoiceNumber = uniqueInvoiceNumber();
        erp.stubFor(erpCall(invoiceNumber).willReturn(aResponse().withStatus(400)));
        double deadLetteredBefore = meterRegistry.counter("invoicehub.invoices.dead.lettered").count();

        UUID id = uploadValidInvoice(invoiceNumber);

        awaitStatus(id, InvoiceStatus.DELIVERY_FAILED);
        await().atMost(DELIVERY_TIMEOUT).untilAsserted(() ->
                assertThat(meterRegistry.counter("invoicehub.invoices.dead.lettered").count())
                        .isGreaterThan(deadLetteredBefore));
    }

    @Test
    void deliversAfterErpWasUnavailableForShortTime() throws IOException {
        String invoiceNumber = uniqueInvoiceNumber();
        stubResponsesInOrder(invoiceNumber, aResponse().withStatus(503), aResponse().withStatus(503),
                aResponse().withStatus(201));

        UUID id = uploadValidInvoice(invoiceNumber);

        awaitStatus(id, InvoiceStatus.DELIVERED);
        erp.verify(3, erpRequest(invoiceNumber));
    }

    @Test
    void marksAsFailedWhenErpIsUnavailableLongerThanAllRetries() throws IOException {
        String invoiceNumber = uniqueInvoiceNumber();
        erp.stubFor(erpCall(invoiceNumber).willReturn(aResponse().withStatus(503)));

        UUID id = uploadValidInvoice(invoiceNumber);

        awaitStatus(id, InvoiceStatus.DELIVERY_FAILED);
        erp.verify(4, erpRequest(invoiceNumber));
        assertThat(keysInDeadLetterTopic()).contains(id.toString());
    }

    @Test
    void marksAsFailedWithoutRetryWhenErpRejectsInvoice() throws IOException {
        String invoiceNumber = uniqueInvoiceNumber();
        erp.stubFor(erpCall(invoiceNumber).willReturn(aResponse().withStatus(400).withBody("Unknown buyer")));

        UUID id = uploadValidInvoice(invoiceNumber);

        awaitStatus(id, InvoiceStatus.DELIVERY_FAILED);
        erp.verify(1, erpRequest(invoiceNumber));
        assertThat(keysInDeadLetterTopic()).contains(id.toString());
    }

    @Test
    void retriesWhenErpIsRateLimiting() throws IOException {
        String invoiceNumber = uniqueInvoiceNumber();
        stubResponsesInOrder(invoiceNumber, aResponse().withStatus(429), aResponse().withStatus(201));

        UUID id = uploadValidInvoice(invoiceNumber);

        awaitStatus(id, InvoiceStatus.DELIVERED);
        erp.verify(2, erpRequest(invoiceNumber));
    }

    @Test
    void publishesNoEventForRejectedInvoice() throws IOException {
        byte[] invalid = new ClassPathResource("samples/xrechnung-ubl-missing-buyer-reference.xml").getContentAsByteArray();

        UUID id = upload(new MockMultipartFile("file", "invalid.xml", MediaType.APPLICATION_XML_VALUE, invalid));

        assertThat(invoiceService.findById(id)).get().extracting("status").isEqualTo(InvoiceStatus.REJECTED);
        assertThat(eventPublicationsFor(id)).isZero();
    }

    /**
     * An event that is saved but not yet sent is kept in the Event Publication Registry. On restart,
     * {@code republish-outstanding-events-on-restart} resubmits it the same way as this test does.
     */
    @Test
    void deliversEventThatCouldNotBeSentToKafka() throws IOException {
        String invoiceNumber = uniqueInvoiceNumber();
        erp.stubFor(erpCall(invoiceNumber).willReturn(aResponse().withStatus(201)));

        UUID id = withKafkaPaused(() -> {
            UUID uploaded = uploadValidInvoice(invoiceNumber);
            awaitFailedExternalization(uploaded);
            return uploaded;
        });
        assertThat(invoiceService.findById(id)).get().extracting("status").isEqualTo(InvoiceStatus.VALID);

        incompleteEventPublications.resubmitIncompletePublications(publication -> true);

        awaitStatus(id, InvoiceStatus.DELIVERED);
    }

    private interface KafkaAction<T> {
        T run() throws IOException;
    }

    private <T> T withKafkaPaused(KafkaAction<T> action) throws IOException {
        var docker = kafka.getDockerClient();
        docker.pauseContainerCmd(kafka.getContainerId()).exec();
        try {
            return action.run();
        } finally {
            docker.unpauseContainerCmd(kafka.getContainerId()).exec();
        }
    }

    private void awaitFailedExternalization(UUID id) {
        await().atMost(DELIVERY_TIMEOUT).until(() -> jdbcClient.sql("""
                        select count(*) from event_publication
                        where serialized_event like :invoiceId and status = 'FAILED'
                        """)
                .param("invoiceId", "%" + id + "%")
                .query(Long.class)
                .single() == 1);
    }

    private long eventPublicationsFor(UUID id) {
        return jdbcClient.sql("select count(*) from event_publication where serialized_event like :invoiceId")
                .param("invoiceId", "%" + id + "%")
                .query(Long.class)
                .single();
    }

    private void awaitStatus(UUID id, InvoiceStatus expected) {
        await().atMost(DELIVERY_TIMEOUT).untilAsserted(() ->
                assertThat(invoiceService.findById(id)).get().extracting("status").isEqualTo(expected));
    }

    private void stubResponsesInOrder(String invoiceNumber, ResponseDefinitionBuilder... responses) {
        String state = Scenario.STARTED;
        for (int index = 0; index < responses.length; index++) {
            String nextState = index == responses.length - 1 ? state : invoiceNumber + "-" + (index + 1);
            erp.stubFor(erpCall(invoiceNumber)
                    .inScenario(invoiceNumber)
                    .whenScenarioStateIs(state)
                    .willSetStateTo(nextState)
                    .willReturn(responses[index]));
            state = nextState;
        }
    }

    // Matching by invoice number keeps the stubs of one test apart from retries of other tests.
    private static MappingBuilder erpCall(String invoiceNumber) {
        return post(urlEqualTo("/erp/invoices"))
                .withRequestBody(matchingJsonPath("$.invoice.invoiceNumber", equalTo(invoiceNumber)));
    }

    private static RequestPatternBuilder erpRequest(String invoiceNumber) {
        return postRequestedFor(urlEqualTo("/erp/invoices"))
                .withRequestBody(matchingJsonPath("$.invoice.invoiceNumber", equalTo(invoiceNumber)));
    }

    private List<String> keysInDeadLetterTopic() {
        Map<String, Object> config = Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers(),
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class);
        try (var consumer = new KafkaConsumer<String, byte[]>(config)) {
            List<TopicPartition> partitions = consumer.partitionsFor(DEAD_LETTER_TOPIC).stream()
                    .map(partition -> new TopicPartition(DEAD_LETTER_TOPIC, partition.partition()))
                    .toList();
            consumer.assign(partitions);
            consumer.seekToBeginning(partitions);
            Map<TopicPartition, Long> endOffsets = consumer.endOffsets(partitions);
            List<String> keys = new ArrayList<>();
            while (partitions.stream().anyMatch(partition -> consumer.position(partition) < endOffsets.get(partition))) {
                consumer.poll(Duration.ofMillis(200)).forEach(record -> keys.add(record.key()));
            }
            return keys;
        }
    }

    private UUID uploadValidInvoice(String invoiceNumber) throws IOException {
        String xml = new ClassPathResource("samples/xrechnung-ubl-valid.xml").getContentAsString(StandardCharsets.UTF_8)
                .replace("<cbc:ID>123456XX</cbc:ID>", "<cbc:ID>" + invoiceNumber + "</cbc:ID>");
        UUID id = upload(new MockMultipartFile("file", invoiceNumber + ".xml", MediaType.APPLICATION_XML_VALUE,
                xml.getBytes(StandardCharsets.UTF_8)));
        assertThat(invoiceService.findById(id)).get().extracting("status").isNotEqualTo(InvoiceStatus.DUPLICATE);
        return id;
    }

    private UUID upload(MockMultipartFile file) throws IOException {
        String response = mockMvc.post().uri("/api/invoices").multipart().file(file).exchange()
                .getResponse().getContentAsString();
        return UUID.fromString(JsonPath.read(response, "$.id"));
    }

    private static String uniqueInvoiceNumber() {
        return "R-" + UUID.randomUUID();
    }
}
