package io.github.manuscode.invoicehub.intake;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.awaitility.Awaitility.await;

import io.github.manuscode.invoicehub.GreenMailContainer;
import io.github.manuscode.invoicehub.TestcontainersConfiguration;
import jakarta.mail.Folder;
import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.Session;
import jakarta.mail.Store;
import jakarta.mail.Transport;
import jakarta.mail.internet.MimeMessage;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.InputStreamSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mail.javamail.MimeMessageHelper;

@SpringBootTest(properties = {
        "invoice-hub.mail.poll-interval=200ms",
        "invoice-hub.mail.timeout=2s",
        "invoice-hub.mail.max-attachment-size=100KB"})
@Import(TestcontainersConfiguration.class)
@ExtendWith(OutputCaptureExtension.class)
class MailIntakeIntegrationTest {

    private static final Duration PROCESSING_TIMEOUT = Duration.ofSeconds(30);

    @Autowired
    private GreenMailContainer greenMail;

    @Autowired
    private JdbcClient jdbcClient;

    @BeforeEach
    void deleteInvoices() {
        jdbcClient.sql("delete from invoice").update();
    }

    @Test
    void createsInvoiceFromXRechnungAttachment() throws Exception {
        send("Rechnung 123456XX", "xrechnung-ubl-valid.xml");

        await().atMost(PROCESSING_TIMEOUT).untilAsserted(() ->
                assertThat(subjectsIn("processed")).contains("Rechnung 123456XX"));
        assertThat(invoices()).containsExactly(new StoredInvoice("MAIL", "xrechnung-ubl-valid.xml", "VALID"));
        assertThat(subjectsIn("INBOX")).doesNotContain("Rechnung 123456XX");
    }

    @Test
    void createsOneInvoicePerAttachment() throws Exception {
        send("Rechnung als XML und PDF", "xrechnung-cii-valid.xml", "zugferd-en16931.pdf");

        await().atMost(PROCESSING_TIMEOUT).untilAsserted(() ->
                assertThat(subjectsIn("processed")).contains("Rechnung als XML und PDF"));
        assertThat(invoices())
                .extracting(StoredInvoice::channel, StoredInvoice::filename)
                .containsExactlyInAnyOrder(
                        tuple("MAIL", "xrechnung-cii-valid.xml"),
                        tuple("MAIL", "zugferd-en16931.pdf"));
    }

    @Test
    void movesMailWithoutAttachmentToUnprocessed(CapturedOutput output) throws Exception {
        send("Rechnung folgt");

        await().atMost(PROCESSING_TIMEOUT).untilAsserted(() ->
                assertThat(subjectsIn("unprocessed")).contains("Rechnung folgt"));
        assertThat(invoices()).isEmpty();
        assertThat(output).contains("has no XML or PDF attachment, moved to folder 'unprocessed'");
    }

    @Test
    void movesMailWithAttachmentBiggerThanLimitToUnprocessedAndGoesOn(CapturedOutput output) throws Exception {
        send("Große Rechnung", new ByteArrayResource(new byte[200_000]), "big.pdf");
        send("Rechnung danach", "xrechnung-ubl-valid.xml");

        await().atMost(PROCESSING_TIMEOUT).untilAsserted(() ->
                assertThat(subjectsIn("processed")).contains("Rechnung danach"));
        assertThat(subjectsIn("unprocessed")).contains("Große Rechnung");
        assertThat(invoices()).extracting(StoredInvoice::filename).containsExactly("xrechnung-ubl-valid.xml");
        assertThat(output).contains("could not be read, moving it to folder 'unprocessed'")
                .contains("Attachment big.pdf is bigger than 102400 bytes");
    }

    @Test
    void logsMailWithoutMessageIdWithPlaceholder(CapturedOutput output) throws Exception {
        send(new MimeMessageWithoutMessageId(smtpSession()), "Rechnung ohne Message-ID",
                Map.of("xrechnung-ubl-valid.xml", new ClassPathResource("samples/xrechnung-ubl-valid.xml")));

        await().atMost(PROCESSING_TIMEOUT).untilAsserted(() ->
                assertThat(subjectsIn("processed")).contains("Rechnung ohne Message-ID"));
        assertThat(output).contains("Received 1 document(s) from mail <no Message-ID>")
                .doesNotContain("from mail null");
    }

    @Test
    void keepsPollingWhileMailboxIsUnreachable(CapturedOutput output) throws Exception {
        var docker = greenMail.getDockerClient();
        docker.pauseContainerCmd(greenMail.getContainerId()).exec();
        try {
            await().atMost(PROCESSING_TIMEOUT).untilAsserted(() ->
                    assertThat(output).contains("failure occurred while polling for mail"));
        } finally {
            docker.unpauseContainerCmd(greenMail.getContainerId()).exec();
        }

        send("Rechnung nach Ausfall", "xrechnung-ubl-valid.xml");

        await().atMost(PROCESSING_TIMEOUT).untilAsserted(() ->
                assertThat(subjectsIn("processed")).contains("Rechnung nach Ausfall"));
        assertThat(invoices()).extracting(StoredInvoice::status).containsExactly("VALID");
    }

    private void send(String subject, String... sampleNames) throws MessagingException {
        Map<String, InputStreamSource> attachments = new LinkedHashMap<>();
        for (String sampleName : sampleNames) {
            attachments.put(sampleName, new ClassPathResource("samples/" + sampleName));
        }
        send(subject, attachments);
    }

    private void send(String subject, InputStreamSource attachment, String filename) throws MessagingException {
        send(subject, Map.of(filename, attachment));
    }

    private void send(String subject, Map<String, InputStreamSource> attachments) throws MessagingException {
        send(new MimeMessage(smtpSession()), subject, attachments);
    }

    private void send(MimeMessage mail, String subject, Map<String, InputStreamSource> attachments)
            throws MessagingException {
        MimeMessageHelper helper = new MimeMessageHelper(mail, !attachments.isEmpty());
        helper.setFrom("buchhaltung@lieferant.example");
        helper.setTo(GreenMailContainer.ADDRESS);
        helper.setSubject(subject);
        helper.setText("Sehr geehrte Damen und Herren, anbei unsere Rechnung.");
        for (Map.Entry<String, InputStreamSource> attachment : attachments.entrySet()) {
            helper.addAttachment(attachment.getKey(), attachment.getValue());
        }
        Transport.send(mail);
    }

    private Session smtpSession() {
        Properties smtp = new Properties();
        smtp.put("mail.smtp.host", greenMail.getHost());
        smtp.put("mail.smtp.port", String.valueOf(greenMail.smtpPort()));
        return Session.getInstance(smtp);
    }

    private List<String> subjectsIn(String folderName) throws MessagingException {
        try (Store store = Session.getInstance(new Properties()).getStore("imap")) {
            store.connect(greenMail.getHost(), greenMail.imapPort(), GreenMailContainer.USERNAME,
                    GreenMailContainer.PASSWORD);
            Folder folder = store.getFolder(folderName);
            if (!folder.exists()) {
                return List.of();
            }
            folder.open(Folder.READ_ONLY);
            try (folder) {
                List<String> subjects = new ArrayList<>();
                for (Message message : folder.getMessages()) {
                    subjects.add(message.getSubject());
                }
                return subjects;
            }
        }
    }

    private List<StoredInvoice> invoices() {
        return jdbcClient.sql("select channel, filename, status from invoice")
                .query(StoredInvoice.class)
                .list();
    }

    private record StoredInvoice(String channel, String filename, String status) {
    }

    // Transport.send sets a Message-ID, but mails sent by other clients, e.g. curl in the demo, may have none.
    private static class MimeMessageWithoutMessageId extends MimeMessage {

        MimeMessageWithoutMessageId(Session session) {
            super(session);
        }

        @Override
        protected void updateMessageID() {
        }
    }
}
