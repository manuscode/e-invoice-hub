package io.github.manuscode.invoicehub.intake;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import io.github.manuscode.invoicehub.SftpContainer;
import io.github.manuscode.invoicehub.TestcontainersConfiguration;
import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ClassPathResource;
import org.springframework.integration.sftp.session.SftpRemoteFileTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;

@SpringBootTest(properties = {"invoice-hub.sftp.poll-interval=200ms", "invoice-hub.sftp.min-file-age=1s"})
@Import(TestcontainersConfiguration.class)
@ExtendWith(OutputCaptureExtension.class)
class SftpIntakeIntegrationTest {

    private static final Duration PROCESSING_TIMEOUT = Duration.ofSeconds(30);
    private static final String HOME = "/home/" + SftpContainer.USERNAME;

    @Autowired
    private SftpContainer sftp;

    @Autowired
    private SftpRemoteFileTemplate sftpTemplate;

    @Autowired
    private JdbcClient jdbcClient;

    @BeforeEach
    void deleteInvoicesAndFiles() throws Exception {
        jdbcClient.sql("delete from invoice").update();
        sftp.execInContainer("sh", "-c", "rm -f %1$s/inbox/* %1$s/processed/* %1$s/failed/*".formatted(HOME));
    }

    @Test
    void createsInvoiceAndMovesFileToProcessed() throws Exception {
        upload("xrechnung-ubl-valid.xml");

        await().atMost(PROCESSING_TIMEOUT).untilAsserted(() ->
                assertThat(filesIn("processed")).singleElement().asString().endsWith("_xrechnung-ubl-valid.xml"));
        assertThat(invoices()).containsExactly(new StoredInvoice("SFTP", "xrechnung-ubl-valid.xml", "VALID"));
        assertThat(filesIn("inbox")).isEmpty();
    }

    @Test
    void movesFileWithUnsupportedFormatToProcessed() throws Exception {
        upload("pdf-without-invoice.pdf");

        await().atMost(PROCESSING_TIMEOUT).untilAsserted(() ->
                assertThat(filesIn("processed")).singleElement().asString().endsWith("_pdf-without-invoice.pdf"));
        assertThat(invoices()).extracting(StoredInvoice::status).containsExactly("REJECTED");
    }

    @Test
    void movesUnreadableFileToFailed(CapturedOutput output) throws Exception {
        // Created by root without permissions, so the SFTP user can list and move the file, but not read it.
        sftp.execInContainer("sh", "-c", "umask 777 && echo '<Invoice/>' > %s/inbox/unreadable.xml".formatted(HOME));

        await().atMost(PROCESSING_TIMEOUT).untilAsserted(() ->
                assertThat(filesIn("failed")).singleElement().asString().endsWith("_unreadable.xml"));
        assertThat(invoices()).isEmpty();
        assertThat(output).contains("File unreadable.xml could not be read, moving it to directory '/failed'");
    }

    @Test
    void createsNoSecondInvoiceForSameFileUploadedAgain() throws Exception {
        upload("xrechnung-ubl-valid.xml");
        await().atMost(PROCESSING_TIMEOUT).untilAsserted(() -> assertThat(filesIn("processed")).hasSize(1));

        upload("xrechnung-ubl-valid.xml");

        await().atMost(PROCESSING_TIMEOUT).untilAsserted(() -> assertThat(filesIn("processed")).hasSize(2));
        assertThat(invoices()).hasSize(1);
    }

    private void upload(String sampleName) throws IOException {
        try (InputStream content = new ClassPathResource("samples/" + sampleName).getInputStream()) {
            sftpTemplate.execute(session -> {
                session.write(content, "/inbox/" + sampleName);
                return null;
            });
        }
    }

    // Looks directly into the container, so the check doesn't depend on the SFTP client under test.
    private List<String> filesIn(String directory) throws Exception {
        String listing = sftp.execInContainer("ls", HOME + "/" + directory).getStdout();
        return listing.lines().toList();
    }

    private List<StoredInvoice> invoices() {
        return jdbcClient.sql("select channel, filename, status from invoice")
                .query(StoredInvoice.class)
                .list();
    }

    private record StoredInvoice(String channel, String filename, String status) {
    }
}
