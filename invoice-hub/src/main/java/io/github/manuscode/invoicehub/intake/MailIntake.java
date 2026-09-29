package io.github.manuscode.invoicehub.intake;

import jakarta.mail.Folder;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import java.io.Closeable;
import java.io.IOException;
import java.util.List;
import java.util.Optional;
import org.eclipse.angus.mail.imap.IMAPFolder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.integration.StaticMessageHeaderAccessor;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageHandler;
import org.springframework.messaging.MessageHandlingException;
import org.springframework.stereotype.Component;

/**
 * Hands every XML or PDF attachment of a mail to the intake. Afterwards the mail is moved out of the inbox. If this
 * fails, the mail stays in the inbox and is processed again with the next poll. That is safe, because the intake
 * recognizes documents it received before. Mails without such attachment and mails that can't be read, e.g. broken
 * or with an attachment that is too big, are moved to the unprocessed folder instead.
 */
@Component
class MailIntake implements MessageHandler {

    private static final Logger log = LoggerFactory.getLogger(MailIntake.class);

    private final IntakeService intakeService;
    private final MailIntakeProperties properties;

    MailIntake(IntakeService intakeService, MailIntakeProperties properties) {
        this.intakeService = intakeService;
        this.properties = properties;
    }

    @Override
    public void handleMessage(Message<?> message) {
        MimeMessage mail = (MimeMessage) message.getPayload();
        try (Closeable folder = StaticMessageHeaderAccessor.getCloseableResource(message)) {
            receive(mail);
        } catch (MessagingException | IOException e) {
            throw new MessageHandlingException(message, "Mail could not be processed, it stays in the inbox", e);
        }
    }

    private void receive(MimeMessage mail) throws MessagingException {
        // Read before the move, afterwards the mail is no longer accessible in the inbox.
        String messageId = mail.getMessageID();
        Optional<List<RawDocument>> documents = read(mail, messageId);
        if (documents.isEmpty()) {
            // Not readable, the reason is already logged.
            moveTo(mail, properties.unprocessedFolder());
            return;
        }
        if (documents.get().isEmpty()) {
            moveTo(mail, properties.unprocessedFolder());
            log.warn("Mail {} has no XML or PDF attachment, moved to folder '{}'",
                    messageId, properties.unprocessedFolder());
            return;
        }
        documents.get().forEach(intakeService::receive);
        moveTo(mail, properties.processedFolder());
        log.info("Received {} document(s) from mail {}", documents.get().size(), messageId);
    }

    // A mail that can't be read would fail with every poll. It would also block all mails behind it, because every
    // poll starts with the first mail of the inbox.
    private Optional<List<RawDocument>> read(MimeMessage mail, String messageId) {
        try {
            return Optional.of(MailAttachments.invoiceDocuments(mail, properties.maxAttachmentSize()));
        } catch (MessagingException | IOException e) {
            log.error("Mail {} could not be read, moving it to folder '{}'", messageId, properties.unprocessedFolder(), e);
            return Optional.empty();
        }
    }

    private static void moveTo(MimeMessage mail, String folderName) throws MessagingException {
        IMAPFolder inbox = (IMAPFolder) mail.getFolder();
        Folder target = inbox.getStore().getFolder(folderName);
        if (!target.exists() && !target.create(Folder.HOLDS_MESSAGES)) {
            throw new MessagingException("Folder '" + folderName + "' could not be created");
        }
        // MOVE (RFC 6851) instead of copy and delete, so a crash in between can't leave the mail in both folders.
        inbox.moveMessages(new MimeMessage[] {mail}, target);
    }
}
