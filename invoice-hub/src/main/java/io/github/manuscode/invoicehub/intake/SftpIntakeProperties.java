package io.github.manuscode.invoicehub.intake;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.core.io.Resource;
import org.springframework.util.unit.DataSize;

@ConfigurationProperties("invoice-hub.sftp")
record SftpIntakeProperties(
        String host,
        int port,
        String username,
        Resource privateKey,
        Resource knownHosts,
        String inboxDirectory,
        String processedDirectory,
        String failedDirectory,
        DataSize maxFileSize,
        Duration minFileAge,
        Duration pollInterval,
        Duration timeout) {
}
