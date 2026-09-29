package io.github.manuscode.invoicehub.intake;

import io.github.manuscode.invoicehub.invoice.Channel;
import jakarta.mail.MessagingException;
import jakarta.mail.Multipart;
import jakarta.mail.Part;
import jakarta.mail.internet.MimeUtility;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.springframework.util.unit.DataSize;

final class MailAttachments {

    private MailAttachments() {
    }

    /**
     * @throws MessagingException if the mail is broken or an attachment is bigger than {@code maxAttachmentSize}
     */
    static List<RawDocument> invoiceDocuments(Part part, DataSize maxAttachmentSize)
            throws MessagingException, IOException {
        if (part.isMimeType("multipart/*")) {
            Multipart multipart = (Multipart) part.getContent();
            List<RawDocument> documents = new ArrayList<>();
            for (int index = 0; index < multipart.getCount(); index++) {
                documents.addAll(invoiceDocuments(multipart.getBodyPart(index), maxAttachmentSize));
            }
            return List.copyOf(documents);
        }
        // Parts without file name are the text of the mail, not attachments.
        if (part.getFileName() == null) {
            return List.of();
        }
        String filename = MimeUtility.decodeText(part.getFileName());
        if (!isInvoice(part, filename)) {
            return List.of();
        }
        try (InputStream content = part.getInputStream()) {
            return List.of(new RawDocument(filename, limitedContent(content, filename, maxAttachmentSize), Channel.MAIL));
        }
    }

    // The size of a part is only known after decoding, so the limit is checked while reading.
    private static byte[] limitedContent(InputStream content, String filename, DataSize maxAttachmentSize)
            throws MessagingException, IOException {
        int maxBytes = Math.toIntExact(maxAttachmentSize.toBytes());
        byte[] bytes = content.readNBytes(maxBytes + 1);
        if (bytes.length > maxBytes) {
            throw new MessagingException("Attachment " + filename + " is bigger than " + maxBytes + " bytes");
        }
        return bytes;
    }

    // Mail clients often send XML as application/octet-stream, so the file extension counts as well.
    private static boolean isInvoice(Part part, String filename) throws MessagingException {
        String lowerCaseFilename = filename.toLowerCase(Locale.ROOT);
        return part.isMimeType("application/pdf")
                || part.isMimeType("application/xml")
                || part.isMimeType("text/xml")
                || lowerCaseFilename.endsWith(".pdf")
                || lowerCaseFilename.endsWith(".xml");
    }
}
