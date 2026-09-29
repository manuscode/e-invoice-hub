package io.github.manuscode.invoicehub;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static io.github.manuscode.invoicehub.KeycloakContainer.READER;
import static io.github.manuscode.invoicehub.KeycloakContainer.UPLOADER;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import com.jayway.jsonpath.JsonPath;
import io.github.manuscode.invoicehub.KeycloakContainer.Client;
import java.io.IOException;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistrar;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.util.unit.DataSize;
import org.springframework.web.client.RestClient;

/**
 * Runs against a real server with tokens from Keycloak, because MockMvc checks neither real tokens nor the upload
 * limit.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import({TestcontainersConfiguration.class, SecurityIntegrationTest.KeycloakConfiguration.class})
@ExtendWith(OutputCaptureExtension.class)
// Stops Keycloak afterwards. Otherwise it stays running for all later tests and slows them down.
@DirtiesContext
class SecurityIntegrationTest {

    private static final Duration DELIVERY_TIMEOUT = Duration.ofSeconds(30);
    private static final String IBAN = "DE79000000001234567890";

    @RegisterExtension
    static WireMockExtension erp = WireMockExtension.newInstance().options(wireMockConfig().dynamicPort()).build();

    @DynamicPropertySource
    static void erpProperties(DynamicPropertyRegistry registry) {
        registry.add("invoice-hub.erp.base-url", erp::baseUrl);
    }

    @LocalServerPort
    private int port;

    @Autowired
    private KeycloakContainer keycloak;

    @Value("${spring.servlet.multipart.max-file-size}")
    private DataSize maxFileSize;

    @Test
    void refusesRequestsWithoutToken() throws IOException {
        assertThat(upload(validInvoice(), MediaType.APPLICATION_XML, null).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(get("/api/invoices/{id}", UUID.randomUUID(), null).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void refusesInvalidToken() throws IOException {
        String manipulatedToken = tokenOf(UPLOADER) + "x";

        assertThat(upload(validInvoice(), MediaType.APPLICATION_XML, manipulatedToken).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void refusesUploadWithReaderToken() throws IOException {
        ResponseEntity<String> upload = upload(validInvoice(), MediaType.APPLICATION_XML, tokenOf(READER));

        assertThat(upload.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void refusesReadWithUploaderToken() {
        ResponseEntity<String> invoice = get("/api/invoices/{id}", UUID.randomUUID(), tokenOf(UPLOADER));

        assertThat(invoice.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void acceptsUploadWithUploaderTokenAndReadWithReaderToken() throws IOException {
        String invoiceId = uploadedInvoiceId(validInvoice());

        assertThat(get("/api/invoices/{id}", invoiceId, tokenOf(READER)).getStatusCode())
                .isEqualTo(HttpStatus.OK);
    }

    @Test
    void refusesUploadBiggerThanLimit() {
        byte[] tooBig = new byte[Math.toIntExact(maxFileSize.toBytes()) + 1];

        ResponseEntity<String> upload = upload(tooBig, MediaType.APPLICATION_PDF, tokenOf(UPLOADER));

        assertThat(upload.getStatusCode()).isEqualTo(HttpStatus.CONTENT_TOO_LARGE);
    }

    @Test
    void logsNoIbanAndNoInvoiceContent(CapturedOutput output) throws IOException {
        erp.stubFor(post(urlEqualTo("/erp/invoices")).willReturn(aResponse().withStatus(201)));

        String validInvoiceId = uploadedInvoiceId(validInvoice());
        String rejectedInvoiceId = uploadedInvoiceId(sample("xrechnung-cii-missing-buyer-reference.xml"));
        await().atMost(DELIVERY_TIMEOUT).until(() -> status(validInvoiceId), "DELIVERED"::equals);
        get("/api/invoices/{id}/report", rejectedInvoiceId, tokenOf(READER));

        assertThat(output.getAll())
                .contains("with status VALID", "with status REJECTED", "Delivered invoice to the ERP")
                .doesNotContain(IBAN, "[Seller name]", "[Buyer name]", "buyer@info.de");
    }

    private String status(String invoiceId) {
        return JsonPath.read(get("/api/invoices/{id}", invoiceId, tokenOf(READER)).getBody(),
                "$.status");
    }

    private String uploadedInvoiceId(byte[] invoice) {
        ResponseEntity<String> upload = upload(invoice, MediaType.APPLICATION_XML, tokenOf(UPLOADER));
        assertThat(upload.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return JsonPath.read(upload.getBody(), "$.id");
    }

    private ResponseEntity<String> upload(byte[] content, MediaType contentType, String token) {
        MultipartBodyBuilder body = new MultipartBodyBuilder();
        body.part("file", new ByteArrayResource(content)).filename("invoice").contentType(contentType);
        return api().post()
                .uri("/api/invoices")
                .headers(headers -> bearer(headers, token))
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(body.build())
                .retrieve()
                // The status is checked by the tests.
                .onStatus(status -> true, (request, response) -> { })
                .toEntity(String.class);
    }

    private ResponseEntity<String> get(String path, Object id, String token) {
        return api().get()
                .uri(path, id)
                .headers(headers -> bearer(headers, token))
                .retrieve()
                .onStatus(status -> true, (request, response) -> { })
                .toEntity(String.class);
    }

    private static void bearer(HttpHeaders headers, String token) {
        if (token != null) {
            headers.setBearerAuth(token);
        }
    }

    private RestClient api() {
        // Like in ErpClient: without this, the JDK client tries an HTTP/2 upgrade on plain HTTP.
        HttpClient httpClient = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
        return RestClient.builder()
                .baseUrl("http://localhost:" + port)
                .requestFactory(new JdkClientHttpRequestFactory(httpClient))
                .build();
    }

    private String tokenOf(Client client) {
        return keycloak.accessToken(client);
    }

    // Unique invoice number, so the upload is no duplicate of other tests.
    private static byte[] validInvoice() throws IOException {
        return new String(sample("xrechnung-ubl-valid.xml"), StandardCharsets.UTF_8)
                .replace("<cbc:ID>123456XX</cbc:ID>", "<cbc:ID>R-" + UUID.randomUUID() + "</cbc:ID>")
                .getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] sample(String name) throws IOException {
        return new ClassPathResource("samples/" + name).getContentAsByteArray();
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class KeycloakConfiguration {

        @Bean
        KeycloakContainer keycloak() {
            return new KeycloakContainer();
        }

        @Bean
        DynamicPropertyRegistrar issuerProperties(KeycloakContainer keycloak) {
            return registry -> registry.add("spring.security.oauth2.resourceserver.jwt.issuer-uri",
                    keycloak::issuerUri);
        }
    }
}
