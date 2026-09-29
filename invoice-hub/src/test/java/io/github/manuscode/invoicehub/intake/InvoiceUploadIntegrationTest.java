package io.github.manuscode.invoicehub.intake;

import static io.github.manuscode.invoicehub.AccessTokens.reader;
import static io.github.manuscode.invoicehub.AccessTokens.uploader;
import static org.assertj.core.api.Assertions.assertThat;

import com.jayway.jsonpath.JsonPath;
import io.github.manuscode.invoicehub.TestcontainersConfiguration;
import io.micrometer.core.instrument.MeterRegistry;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.util.Arrays;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class InvoiceUploadIntegrationTest {

    @Autowired
    private MockMvcTester mockMvc;

    @Autowired
    private JdbcClient jdbcClient;

    @Autowired
    private MeterRegistry meterRegistry;

    // The valid samples are the same invoice, without cleanup later uploads would be duplicates.
    @BeforeEach
    void deleteInvoices() {
        jdbcClient.sql("delete from invoice").update();
    }

    @Test
    void acceptsValidXRechnungUbl() throws IOException {
        MvcTestResult upload = upload(sample("xrechnung-ubl-valid.xml"));

        assertThat(upload).hasStatus(HttpStatus.CREATED);
        assertThat(upload).bodyJson().extractingPath("$.status").isEqualTo("VALID");
        assertThat(upload).bodyJson().extractingPath("$.format").isEqualTo("XRECHNUNG_UBL");
    }

    @Test
    void rejectsInvalidXRechnungUblWithReport() throws IOException {
        MvcTestResult upload = upload(sample("xrechnung-ubl-missing-buyer-reference.xml"));

        assertThat(upload).hasStatus(HttpStatus.CREATED);
        assertThat(upload).bodyJson().extractingPath("$.status").isEqualTo("REJECTED");
        assertThat(upload).bodyJson().extractingPath("$.rejectionReason").isEqualTo("VALIDATION_FAILED");
        assertThat(mockMvc.get().uri("/api/invoices/{id}/report", invoiceId(upload)).with(reader()))
                .hasStatusOk()
                .bodyText().contains("BR-DE-15");
    }

    @Test
    void servesReportWithoutScriptsOrExternalContent() throws IOException {
        MvcTestResult upload = upload(sample("xrechnung-ubl-missing-buyer-reference.xml"));

        assertThat(mockMvc.get().uri("/api/invoices/{id}/report", invoiceId(upload)).with(reader()))
                .hasStatusOk()
                .hasHeader("Content-Security-Policy", "default-src 'none'; style-src 'unsafe-inline'; sandbox");
    }

    @Test
    void rejectsXRechnungThatIsNotWellFormedWithReport() throws IOException {
        byte[] valid = sample("xrechnung-ubl-valid.xml").getBytes();
        byte[] truncated = Arrays.copyOf(valid, valid.length / 2);

        MvcTestResult upload = upload(new MockMultipartFile("file", "truncated.xml", "application/xml", truncated));

        assertThat(upload).hasStatus(HttpStatus.CREATED);
        assertThat(upload).bodyJson().extractingPath("$.status").isEqualTo("REJECTED");
        assertThat(upload).bodyJson().extractingPath("$.rejectionReason").isEqualTo("VALIDATION_FAILED");
        assertThat(mockMvc.get().uri("/api/invoices/{id}/report", invoiceId(upload)).with(reader()))
                .hasStatusOk();
    }

    @Test
    void acceptsValidXRechnungCii() throws IOException {
        MvcTestResult upload = upload(sample("xrechnung-cii-valid.xml"));

        assertThat(upload).hasStatus(HttpStatus.CREATED);
        assertThat(upload).bodyJson().extractingPath("$.status").isEqualTo("VALID");
        assertThat(upload).bodyJson().extractingPath("$.format").isEqualTo("XRECHNUNG_CII");
    }

    @Test
    void rejectsInvalidXRechnungCiiWithReport() throws IOException {
        MvcTestResult upload = upload(sample("xrechnung-cii-missing-buyer-reference.xml"));

        assertThat(upload).bodyJson().extractingPath("$.status").isEqualTo("REJECTED");
        assertThat(upload).bodyJson().extractingPath("$.rejectionReason").isEqualTo("VALIDATION_FAILED");
        assertThat(mockMvc.get().uri("/api/invoices/{id}/report", invoiceId(upload)).with(reader()))
                .hasStatusOk()
                .bodyText().contains("BR-DE-15");
    }

    @Test
    void acceptsValidZugferdEn16931Pdf() throws IOException {
        MvcTestResult upload = upload(sample("zugferd-en16931.pdf"));

        assertThat(upload).hasStatus(HttpStatus.CREATED);
        assertThat(upload).bodyJson().extractingPath("$.status").isEqualTo("VALID");
        assertThat(upload).bodyJson().extractingPath("$.format").isEqualTo("ZUGFERD");
    }

    @Test
    void rejectsZugferdMinimumPdfAsUnsupportedProfile() throws IOException {
        MvcTestResult upload = upload(sample("zugferd-minimum.pdf"));

        assertThat(upload).hasStatus(HttpStatus.CREATED);
        assertThat(upload).bodyJson().extractingPath("$.status").isEqualTo("REJECTED");
        assertThat(upload).bodyJson().extractingPath("$.rejectionReason").isEqualTo("UNSUPPORTED_PROFILE");
        assertThat(upload).bodyJson().extractingPath("$.format").isEqualTo("ZUGFERD");
    }

    @Test
    void rejectsPdfWithoutEmbeddedInvoiceAsUnsupportedFormat() throws IOException {
        MvcTestResult upload = upload(sample("pdf-without-invoice.pdf"));

        assertThat(upload).bodyJson().extractingPath("$.status").isEqualTo("REJECTED");
        assertThat(upload).bodyJson().extractingPath("$.rejectionReason").isEqualTo("UNSUPPORTED_FORMAT");
    }

    @Test
    void rejectsPdfAsUnsupportedFormat() {
        MvcTestResult upload = upload(new MockMultipartFile("file", "invoice.pdf", "application/pdf",
                "%PDF-1.7 no invoice".getBytes()));

        assertThat(upload).hasStatus(HttpStatus.CREATED);
        assertThat(upload).bodyJson().extractingPath("$.status").isEqualTo("REJECTED");
        assertThat(upload).bodyJson().extractingPath("$.rejectionReason").isEqualTo("UNSUPPORTED_FORMAT");
    }

    @Test
    void refusesFileThatIsNeitherXmlNorPdf() {
        MvcTestResult upload = upload(new MockMultipartFile("file", "invoice.txt", MediaType.TEXT_PLAIN_VALUE,
                "Invoice 4711, total 100 EUR".getBytes()));

        assertThat(upload).hasStatus(HttpStatus.UNSUPPORTED_MEDIA_TYPE);
    }

    @Test
    void refusesFileWithoutContentType() throws IOException {
        MvcTestResult upload = upload(new MockMultipartFile("file", "invoice.xml", null,
                sample("xrechnung-ubl-valid.xml").getBytes()));

        assertThat(upload).hasStatus(HttpStatus.UNSUPPORTED_MEDIA_TYPE);
    }

    @Test
    void acceptsXmlContentTypeWithCharset() throws IOException {
        MvcTestResult upload = upload(new MockMultipartFile("file", "invoice.xml", "text/xml; charset=UTF-8",
                sample("xrechnung-ubl-valid.xml").getBytes()));

        assertThat(upload).hasStatus(HttpStatus.CREATED);
        assertThat(upload).bodyJson().extractingPath("$.status").isEqualTo("VALID");
    }

    @Test
    void acceptsFilenameLongerThanDatabaseColumn() throws IOException {
        String filename = "x".repeat(300) + ".xml";

        MvcTestResult upload = upload(new MockMultipartFile("file", filename, MediaType.APPLICATION_XML_VALUE,
                sample("xrechnung-ubl-valid.xml").getBytes()));

        assertThat(upload).hasStatus(HttpStatus.CREATED);
        assertThat(upload).bodyJson().extractingPath("$.filename").isEqualTo("x".repeat(255));
    }

    @Test
    void storesInvoiceForLaterRequests() throws IOException {
        MvcTestResult upload = upload(sample("xrechnung-ubl-valid.xml"));

        assertThat(mockMvc.get().uri("/api/invoices/{id}", invoiceId(upload)).with(reader()))
                .hasStatusOk()
                .bodyJson().extractingPath("$.status").isEqualTo("VALID");
    }

    @Test
    void countsReceivedInvoicesByChannelFormatAndStatus() throws IOException {
        double validBefore = receivedCount("XRECHNUNG_UBL", "VALID");
        double rejectedBefore = receivedCount("UNKNOWN", "REJECTED");

        upload(sample("xrechnung-ubl-valid.xml"));
        upload(sample("pdf-without-invoice.pdf"));

        assertThat(receivedCount("XRECHNUNG_UBL", "VALID")).isEqualTo(validBefore + 1);
        assertThat(receivedCount("UNKNOWN", "REJECTED")).isEqualTo(rejectedBefore + 1);
    }

    @Test
    void doesNotCountSameDocumentTwice() throws IOException {
        upload(sample("xrechnung-ubl-valid.xml"));
        double countAfterFirstUpload = receivedCount("XRECHNUNG_UBL", "VALID");

        upload(sample("xrechnung-ubl-valid.xml"));

        assertThat(receivedCount("XRECHNUNG_UBL", "VALID")).isEqualTo(countAfterFirstUpload);
    }

    @Test
    void rejectsEmptyFile() {
        MvcTestResult upload = upload(new MockMultipartFile("file", "empty.xml", "application/xml", new byte[0]));

        assertThat(upload).hasStatus(HttpStatus.BAD_REQUEST);
    }

    @Test
    void returnsNotFoundForUnknownInvoice() {
        assertThat(mockMvc.get().uri("/api/invoices/{id}", UUID.randomUUID()).with(reader()))
                .hasStatus(HttpStatus.NOT_FOUND);
        assertThat(mockMvc.get().uri("/api/invoices/{id}/report", UUID.randomUUID()).with(reader()))
                .hasStatus(HttpStatus.NOT_FOUND);
    }

    private double receivedCount(String format, String status) {
        return meterRegistry.counter("invoicehub.invoices.received",
                "channel", "REST", "format", format, "status", status).count();
    }

    private MvcTestResult upload(MockMultipartFile file) {
        return mockMvc.post().uri("/api/invoices").with(uploader()).multipart().file(file).exchange();
    }

    private static MockMultipartFile sample(String name) throws IOException {
        byte[] content = new ClassPathResource("samples/" + name).getContentAsByteArray();
        String contentType = name.endsWith(".pdf") ? MediaType.APPLICATION_PDF_VALUE : MediaType.APPLICATION_XML_VALUE;
        return new MockMultipartFile("file", name, contentType, content);
    }

    private static String invoiceId(MvcTestResult upload) throws UnsupportedEncodingException {
        return JsonPath.read(upload.getResponse().getContentAsString(), "$.id");
    }
}
