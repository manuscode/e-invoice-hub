package io.github.manuscode.invoicehub.intake;

import io.github.manuscode.invoicehub.invoice.Channel;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Optional;
import org.apache.sshd.sftp.client.SftpClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.integration.file.remote.session.Session;
import org.springframework.integration.sftp.session.SftpRemoteFileTemplate;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageHandler;
import org.springframework.stereotype.Component;

/**
 * Hands a file of the inbox to the intake and moves it to the processed directory afterwards. If this fails, the file
 * stays in the inbox and is processed again with the next poll. That is safe, because the intake recognizes documents
 * it received before. Only files that can't be read are moved to the failed directory, they would fail every time.
 */
@Component
class SftpIntake implements MessageHandler {

    private static final Logger log = LoggerFactory.getLogger(SftpIntake.class);

    private static final DateTimeFormatter TIMESTAMP_FORMAT =
            DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmssSSS'Z'").withZone(ZoneOffset.UTC);

    private final IntakeService intakeService;
    private final SftpRemoteFileTemplate sftpTemplate;
    private final SftpIntakeProperties properties;

    SftpIntake(IntakeService intakeService, SftpRemoteFileTemplate sftpTemplate, SftpIntakeProperties properties) {
        this.intakeService = intakeService;
        this.sftpTemplate = sftpTemplate;
        this.properties = properties;
    }

    @Override
    public void handleMessage(Message<?> message) {
        String filename = (String) message.getPayload();
        sftpTemplate.execute(session -> {
            receive(session, filename);
            return null;
        });
    }

    private void receive(Session<SftpClient.DirEntry> session, String filename) throws IOException {
        Optional<byte[]> content = read(session, filename);
        if (content.isEmpty()) {
            moveTo(session, filename, properties.failedDirectory());
            return;
        }
        intakeService.receive(new RawDocument(filename, content.get(), Channel.SFTP));
        moveTo(session, filename, properties.processedDirectory());
        log.info("Received file {}", filename);
    }

    private Optional<byte[]> read(Session<SftpClient.DirEntry> session, String filename) {
        ByteArrayOutputStream content = new ByteArrayOutputStream();
        try {
            session.read(inboxPath(filename), content);
            return Optional.of(content.toByteArray());
        } catch (IOException e) {
            log.error("File {} could not be read, moving it to directory '{}'",
                    filename, properties.failedDirectory(), e);
            return Optional.empty();
        }
    }

    private void moveTo(Session<SftpClient.DirEntry> session, String filename, String directory) throws IOException {
        if (!session.exists(directory)) {
            session.mkdir(directory);
        }
        session.rename(inboxPath(filename), directory + "/" + archivedFilename(filename, Instant.now()));
    }

    private String inboxPath(String filename) {
        return properties.inboxDirectory() + "/" + filename;
    }

    // Rename overwrites existing files on SFTP. The timestamp keeps a file that is uploaded again under the same name.
    private static String archivedFilename(String filename, Instant movedAt) {
        return TIMESTAMP_FORMAT.format(movedAt) + "_" + filename;
    }
}
