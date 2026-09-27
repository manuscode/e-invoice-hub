package io.github.manuscode.invoicehub.intake;

import static org.assertj.core.api.Assertions.assertThat;

import com.jayway.jsonpath.JsonPath;
import io.github.manuscode.invoicehub.TestcontainersConfiguration;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
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
        assertThat(mockMvc.get().uri("/api/invoices/{id}/report", invoiceId(upload)))
                .hasStatusOk()
                .bodyText().contains("BR-DE-15");
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
        assertThat(mockMvc.get().uri("/api/invoices/{id}/report", invoiceId(upload)))
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
    void rejectsTextFileAsUnsupportedFormat() {
        MvcTestResult upload = upload(new MockMultipartFile("file", "invoice.txt", "text/plain",
                "Invoice 4711, total 100 EUR".getBytes()));

        assertThat(upload).bodyJson().extractingPath("$.rejectionReason").isEqualTo("UNSUPPORTED_FORMAT");
    }

    @Test
    void storesInvoiceForLaterRequests() throws IOException {
        MvcTestResult upload = upload(sample("xrechnung-ubl-valid.xml"));

        assertThat(mockMvc.get().uri("/api/invoices/{id}", invoiceId(upload)))
                .hasStatusOk()
                .bodyJson().extractingPath("$.status").isEqualTo("VALID");
    }

    @Test
    void rejectsEmptyFile() {
        MvcTestResult upload = upload(new MockMultipartFile("file", "empty.xml", "application/xml", new byte[0]));

        assertThat(upload).hasStatus(HttpStatus.BAD_REQUEST);
    }

    @Test
    void returnsNotFoundForUnknownInvoice() {
        assertThat(mockMvc.get().uri("/api/invoices/{id}", UUID.randomUUID())).hasStatus(HttpStatus.NOT_FOUND);
        assertThat(mockMvc.get().uri("/api/invoices/{id}/report", UUID.randomUUID())).hasStatus(HttpStatus.NOT_FOUND);
    }

    private MvcTestResult upload(MockMultipartFile file) {
        return mockMvc.post().uri("/api/invoices").multipart().file(file).exchange();
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
