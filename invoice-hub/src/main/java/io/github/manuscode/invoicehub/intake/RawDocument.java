package io.github.manuscode.invoicehub.intake;

import io.github.manuscode.invoicehub.invoice.Channel;
import java.util.regex.Pattern;

/**
 * @param filename as sent by the sender, without control characters and cut to the length of the database column.
 *                 It is only information, so it is cleaned instead of rejected: a mail or SFTP file that fails every
 *                 time would stay in the inbox and block the channel.
 */
public record RawDocument(String filename, byte[] content, Channel channel) {

    private static final int MAX_FILENAME_LENGTH = 255;
    // Line breaks would forge log lines, Postgres rejects NUL in text.
    private static final Pattern CONTROL_CHARACTERS = Pattern.compile("\\p{Cc}");

    public RawDocument {
        filename = filename == null ? null : cleaned(filename);
    }

    private static String cleaned(String filename) {
        return CONTROL_CHARACTERS.matcher(filename).replaceAll("")
                .codePoints()
                .limit(MAX_FILENAME_LENGTH)
                .collect(StringBuilder::new, StringBuilder::appendCodePoint, StringBuilder::append)
                .toString();
    }
}
