package io.github.manuscode.invoicehub.intake;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.core.io.Resource;

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
        Duration minFileAge,
        Duration pollInterval,
        Duration timeout) {
}
