package io.github.manuscode.invoicehub.intake;

import static io.github.manuscode.invoicehub.AccessTokens.reader;
import static io.github.manuscode.invoicehub.AccessTokens.uploader;
import static org.assertj.core.api.Assertions.assertThat;

import com.jayway.jsonpath.JsonPath;
import io.github.manuscode.invoicehub.TestcontainersConfiguration;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class DuplicateDetectionIntegrationTest {

    @Autowired
    private MockMvcTester mockMvc;

    @Autowired
    private JdbcClient jdbcClient;

    @BeforeEach
    void deleteInvoices() {
        jdbcClient.sql("delete from invoice").update();
    }

    @Test
    void returnsExistingInvoiceForSameDocument() throws IOException {
        MvcTestResult first = upload(sample("xrechnung-ubl-valid.xml"));
        MvcTestResult second = upload(sample("xrechnung-ubl-valid.xml"));

        assertThat(first).hasStatus(201);
        assertThat(second).hasStatus(200);
        assertThat(invoiceId(second)).isEqualTo(invoiceId(first));
        assertThat(second).bodyJson().extractingPath("$.status").isEqualTo("VALID");
        assertThat(invoiceCount()).isEqualTo(1);
    }

    @Test
    void returnsExistingInvoiceForSameRejectedDocument() throws IOException {
        MvcTestResult first = upload(sample("xrechnung-ubl-missing-buyer-reference.xml"));
        MvcTestResult second = upload(sample("xrechnung-ubl-missing-buyer-reference.xml"));

        assertThat(second).hasStatus(200);
        assertThat(invoiceId(second)).isEqualTo(invoiceId(first));
        assertThat(second).bodyJson().extractingPath("$.status").isEqualTo("REJECTED");
    }

    @Test
    void marksOtherDocumentWithSameSellerVatIdAndInvoiceNumberAsDuplicate() throws IOException {
        // Same invoice as UBL, CII and ZUGFeRD, so three different documents.
        String original = invoiceId(upload(sample("xrechnung-ubl-valid.xml")));
        MvcTestResult cii = upload(sample("xrechnung-cii-valid.xml"));
        MvcTestResult zugferd = upload(sample("zugferd-en16931.pdf"));

        assertThat(cii).hasStatus(201);
        assertThat(cii).bodyJson().extractingPath("$.status").isEqualTo("DUPLICATE");
        assertThat(cii).bodyJson().extractingPath("$.duplicateOf").isEqualTo(original);
        assertThat(zugferd).bodyJson().extractingPath("$.status").isEqualTo("DUPLICATE");
        assertThat(zugferd).bodyJson().extractingPath("$.duplicateOf").isEqualTo(original);
        assertThat(mockMvc.get().uri("/api/invoices/{id}", invoiceId(cii)).with(reader()))
                .bodyJson().extractingPath("$.duplicateOf").isEqualTo(original);
    }

    @Test
    void marksDuplicateBySellerNameWithoutVatId() throws IOException {
        // Seller tax number (BT-32) instead of VAT ID keeps the invoice valid.
        String withoutVatId = sampleText("xrechnung-ubl-valid.xml")
                .replace("<cbc:CompanyID>DE 123456789</cbc:CompanyID>", "<cbc:CompanyID>123/456/7890</cbc:CompanyID>")
                .replaceFirst("<cbc:ID>VAT</cbc:ID>", "<cbc:ID>FC</cbc:ID>");
        String otherDocument = withoutVatId.replace("Zahlbar sofort ohne Abzug.", "Zahlbar sofort.");

        MvcTestResult original = upload(xml("original.xml", withoutVatId));
        MvcTestResult duplicate = upload(xml("duplicate.xml", otherDocument));

        assertThat(original).bodyJson().extractingPath("$.status").isEqualTo("VALID");
        assertThat(original).bodyJson().extractingPath("$.data.seller.vatId").isNull();
        assertThat(duplicate).bodyJson().extractingPath("$.status").isEqualTo("DUPLICATE");
        assertThat(duplicate).bodyJson().extractingPath("$.duplicateOf").isEqualTo(invoiceId(original));
    }

    @Test
    void keepsInvoiceWithOtherInvoiceNumber() throws IOException {
        upload(sample("xrechnung-ubl-valid.xml"));
        String otherNumber = sampleText("xrechnung-ubl-valid.xml").replace("<cbc:ID>123456XX</cbc:ID>", "<cbc:ID>123457XX</cbc:ID>");

        assertThat(upload(xml("other.xml", otherNumber))).bodyJson().extractingPath("$.status").isEqualTo("VALID");
    }

    @Test
    void createsOnlyOneInvoiceForParallelUploadsOfSameDocument() throws Exception {
        MockMultipartFile file = sample("xrechnung-ubl-valid.xml");

        List<MvcTestResult> uploads = inParallel(8, index -> upload(file));

        assertThat(uploads).extracting(upload -> upload.getResponse().getStatus()).containsOnlyOnce(201);
        assertThat(uploads).extracting(DuplicateDetectionIntegrationTest::invoiceId).containsOnly(invoiceId(uploads.getFirst()));
        assertThat(invoiceCount()).isEqualTo(1);
    }

    @Test
    void keepsOnlyOneOriginalForParallelUploadsWithSameBusinessKey() throws Exception {
        List<MockMultipartFile> files = List.of(
                sample("xrechnung-ubl-valid.xml"), sample("xrechnung-cii-valid.xml"), sample("zugferd-en16931.pdf"));

        List<MvcTestResult> uploads = inParallel(files.size(), index -> upload(files.get(index)));

        List<String> statuses = uploads.stream().map(upload -> json(upload, "$.status")).toList();
        assertThat(statuses).containsExactlyInAnyOrder("VALID", "DUPLICATE", "DUPLICATE");
        String original = uploads.stream().filter(upload -> json(upload, "$.status").equals("VALID"))
                .map(DuplicateDetectionIntegrationTest::invoiceId).findFirst().orElseThrow();
        assertThat(uploads).filteredOn(upload -> json(upload, "$.status").equals("DUPLICATE"))
                .extracting(upload -> json(upload, "$.duplicateOf")).containsOnly(original);
    }

    @Test
    void showsMainDataOfInvoice() throws IOException {
        String id = invoiceId(upload(sample("zugferd-en16931.pdf")));

        var invoice = assertThat(mockMvc.get().uri("/api/invoices/{id}", id).with(reader())).hasStatusOk().bodyJson();
        invoice.extractingPath("$.data.invoiceNumber").isEqualTo("123456XX");
        invoice.extractingPath("$.data.issueDate").isEqualTo("2016-04-04");
        invoice.extractingPath("$.data.seller.name").isEqualTo("[Seller name]");
        invoice.extractingPath("$.data.seller.vatId").isEqualTo("DE 123456789");
        invoice.extractingPath("$.data.buyer.name").isEqualTo("[Buyer name]");
        invoice.extractingPath("$.data.currency").isEqualTo("EUR");
        invoice.extractingPath("$.data.totals.grossAmount").isEqualTo(336.9);
        invoice.extractingPath("$.data.lines.length()").isEqualTo(2);
    }

    @Test
    void hasNoMainDataForRejectedInvoice() throws IOException {
        MvcTestResult upload = upload(sample("xrechnung-ubl-missing-buyer-reference.xml"));

        assertThat(upload).bodyJson().extractingPath("$.data").isNull();
    }

    private interface IndexedTask<T> {
        T run(int index) throws Exception;
    }

    private static <T> List<T> inParallel(int count, IndexedTask<T> task) throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService executor = Executors.newFixedThreadPool(count)) {
            List<Future<T>> futures = IntStream.range(0, count)
                    .mapToObj(index -> executor.submit(() -> {
                        start.await();
                        return task.run(index);
                    }))
                    .toList();
            start.countDown();
            List<T> results = new ArrayList<>();
            for (Future<T> future : futures) {
                results.add(future.get());
            }
            return results;
        }
    }

    private MvcTestResult upload(MockMultipartFile file) {
        return mockMvc.post().uri("/api/invoices").with(uploader()).multipart().file(file).exchange();
    }

    private long invoiceCount() {
        return jdbcClient.sql("select count(*) from invoice").query(Long.class).single();
    }

    private static MockMultipartFile sample(String name) throws IOException {
        byte[] content = new ClassPathResource("samples/" + name).getContentAsByteArray();
        String contentType = name.endsWith(".pdf") ? MediaType.APPLICATION_PDF_VALUE : MediaType.APPLICATION_XML_VALUE;
        return new MockMultipartFile("file", name, contentType, content);
    }

    private static String sampleText(String name) throws IOException {
        return new ClassPathResource("samples/" + name).getContentAsString(StandardCharsets.UTF_8);
    }

    private static MockMultipartFile xml(String name, String content) {
        return new MockMultipartFile("file", name, MediaType.APPLICATION_XML_VALUE, content.getBytes(StandardCharsets.UTF_8));
    }

    private static String invoiceId(MvcTestResult upload) {
        return json(upload, "$.id");
    }

    private static String json(MvcTestResult upload, String path) {
        try {
            return JsonPath.read(upload.getResponse().getContentAsString(), path);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
