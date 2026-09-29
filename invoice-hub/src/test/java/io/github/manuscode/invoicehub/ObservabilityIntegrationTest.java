package io.github.manuscode.invoicehub;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import com.jayway.jsonpath.JsonPath;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.instrumentation.logback.appender.v1_0.OpenTelemetryAppender;
import io.opentelemetry.sdk.logs.data.LogRecordData;
import io.opentelemetry.sdk.testing.exporter.InMemoryLogRecordExporter;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.data.SpanData;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.micrometer.tracing.test.autoconfigure.AutoConfigureTracing;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

@SpringBootTest
@AutoConfigureMockMvc
@AutoConfigureTracing
@Import({TestcontainersConfiguration.class, ObservabilityIntegrationTest.InMemoryExporters.class})
class ObservabilityIntegrationTest {

    private static final Duration EXPORT_TIMEOUT = Duration.ofSeconds(30);
    private static final AttributeKey<String> INVOICE_ID_ATTRIBUTE = AttributeKey.stringKey("invoice.id");
    private static final AttributeKey<String> INVOICE_ID_LOG_ATTRIBUTE = AttributeKey.stringKey("invoiceId");

    @RegisterExtension
    static WireMockExtension erp = WireMockExtension.newInstance().options(wireMockConfig().dynamicPort()).build();

    @DynamicPropertySource
    static void erpProperties(DynamicPropertyRegistry registry) {
        registry.add("invoice-hub.erp.base-url", erp::baseUrl);
    }

    @Autowired
    private MockMvcTester mockMvc;

    @Autowired
    private OpenTelemetry openTelemetry;

    @Autowired
    private InMemorySpanExporter spanExporter;

    @Autowired
    private InMemoryLogRecordExporter logRecordExporter;

    // The appender is static. Another cached test context may have installed its own SDK meanwhile.
    @BeforeEach
    void installAppender() {
        OpenTelemetryAppender.install(openTelemetry);
    }

    @Test
    void followsUploadAsOneTraceToTheErp() throws IOException {
        erp.stubFor(post(urlEqualTo("/erp/invoices")).willReturn(aResponse().withStatus(201)));

        String invoiceId = uploadValidInvoice();

        SpanData delivery = await().atMost(EXPORT_TIMEOUT).until(
                () -> spanOfInvoice("invoicehub.delivery", invoiceId), span -> span != null);
        SpanData intake = spanOfInvoice("invoicehub.intake", invoiceId);
        assertThat(intake.getTraceId()).isEqualTo(delivery.getTraceId());
        assertThat(spansOfTrace(intake.getTraceId())).extracting(SpanData::getKind)
                .contains(SpanKind.SERVER, SpanKind.PRODUCER, SpanKind.CONSUMER, SpanKind.CLIENT);
        erp.verify(postRequestedFor(urlEqualTo("/erp/invoices"))
                .withHeader("traceparent", containing(intake.getTraceId())));
    }

    @Test
    void addsInvoiceIdToLogs() throws IOException {
        erp.stubFor(post(urlEqualTo("/erp/invoices")).willReturn(aResponse().withStatus(201)));

        String invoiceId = uploadValidInvoice();

        await().atMost(EXPORT_TIMEOUT).untilAsserted(() ->
                assertThat(logsOfInvoice(invoiceId)).extracting(log -> log.getBodyValue().asString())
                        .anyMatch(message -> message.startsWith("Received document"))
                        .contains("Delivered invoice to the ERP"));
    }

    private SpanData spanOfInvoice(String name, String invoiceId) {
        return spanExporter.getFinishedSpanItems().stream()
                .filter(span -> span.getName().equals(name))
                .filter(span -> invoiceId.equals(span.getAttributes().get(INVOICE_ID_ATTRIBUTE)))
                .findFirst()
                .orElse(null);
    }

    private List<SpanData> spansOfTrace(String traceId) {
        return spanExporter.getFinishedSpanItems().stream()
                .filter(span -> span.getTraceId().equals(traceId))
                .toList();
    }

    private List<LogRecordData> logsOfInvoice(String invoiceId) {
        return logRecordExporter.getFinishedLogRecordItems().stream()
                .filter(log -> invoiceId.equals(log.getAttributes().get(INVOICE_ID_LOG_ATTRIBUTE)))
                .toList();
    }

    // Unique invoice number, so the upload is no duplicate of other tests.
    private String uploadValidInvoice() throws IOException {
        String invoiceNumber = "R-" + UUID.randomUUID();
        String xml = new ClassPathResource("samples/xrechnung-ubl-valid.xml").getContentAsString(StandardCharsets.UTF_8)
                .replace("<cbc:ID>123456XX</cbc:ID>", "<cbc:ID>" + invoiceNumber + "</cbc:ID>");
        MockMultipartFile file = new MockMultipartFile("file", invoiceNumber + ".xml", MediaType.APPLICATION_XML_VALUE,
                xml.getBytes(StandardCharsets.UTF_8));
        String response = mockMvc.post().uri("/api/invoices").multipart().file(file).exchange()
                .getResponse().getContentAsString();
        return JsonPath.read(response, "$.id");
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class InMemoryExporters {

        @Bean
        InMemorySpanExporter spanExporter() {
            return InMemorySpanExporter.create();
        }

        @Bean
        InMemoryLogRecordExporter logRecordExporter() {
            return InMemoryLogRecordExporter.create();
        }
    }
}
