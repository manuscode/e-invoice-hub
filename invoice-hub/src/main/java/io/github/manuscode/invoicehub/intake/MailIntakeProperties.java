package io.github.manuscode.invoicehub.intake;

import jakarta.mail.URLName;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.unit.DataSize;

@ConfigurationProperties("invoice-hub.mail")
record MailIntakeProperties(
        String protocol,
        String host,
        int port,
        String username,
        String password,
        String inboxFolder,
        String processedFolder,
        String unprocessedFolder,
        DataSize maxAttachmentSize,
        Duration pollInterval,
        Duration timeout) {

    String inboxUrl() {
        return new URLName(protocol, host, port, inboxFolder, null, null).toString();
    }

    @Override
    public String toString() {
        return "MailIntakeProperties[protocol=%s, host=%s, port=%d, username=%s, inboxFolder=%s]"
                .formatted(protocol, host, port, username, inboxFolder);
    }
}
