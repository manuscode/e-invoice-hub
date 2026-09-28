package io.github.manuscode.invoicehub.intake;

import org.apache.sshd.sftp.client.SftpClient;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.integration.dsl.IntegrationFlow;
import org.springframework.integration.dsl.Pollers;
import org.springframework.integration.file.remote.gateway.AbstractRemoteFileOutboundGateway.Command;
import org.springframework.integration.file.remote.gateway.AbstractRemoteFileOutboundGateway.Option;
import org.springframework.integration.file.remote.session.SessionFactory;
import org.springframework.integration.sftp.dsl.Sftp;
import org.springframework.integration.sftp.filters.SftpLastModifiedFileListFilter;
import org.springframework.integration.sftp.session.DefaultSftpSessionFactory;
import org.springframework.integration.sftp.session.SftpRemoteFileTemplate;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(SftpIntakeProperties.class)
class SftpIntakeConfiguration {

    @Bean
    DefaultSftpSessionFactory sftpSessionFactory(SftpIntakeProperties properties) {
        DefaultSftpSessionFactory sessionFactory = new DefaultSftpSessionFactory();
        sessionFactory.setHost(properties.host());
        sessionFactory.setPort(properties.port());
        sessionFactory.setUser(properties.username());
        sessionFactory.setPrivateKey(properties.privateKey());
        // Unknown host keys are rejected, the server must be listed in known_hosts.
        sessionFactory.setKnownHostsResource(properties.knownHosts());
        sessionFactory.setTimeout(Math.toIntExact(properties.timeout().toMillis()));
        return sessionFactory;
    }

    @Bean
    SftpRemoteFileTemplate sftpTemplate(SessionFactory<SftpClient.DirEntry> sftpSessionFactory) {
        return new SftpRemoteFileTemplate(sftpSessionFactory);
    }

    // The files are listed with LS instead of the inbound adapter, because the adapter reads the file before
    // SftpIntake gets it. A file that can't be read would stay in the inbox and fail with every poll.
    @Bean
    IntegrationFlow sftpIntakeFlow(SftpIntakeProperties properties, SftpRemoteFileTemplate sftpTemplate,
            SftpIntake sftpIntake) {
        return IntegrationFlow
                .fromSupplier(properties::inboxDirectory,
                        adapter -> adapter.poller(Pollers.fixedDelay(properties.pollInterval())))
                .handle(Sftp.outboundGateway(sftpTemplate, Command.LS, "payload")
                        .options(Option.NAME_ONLY)
                        .filter(new SftpLastModifiedFileListFilter(properties.minFileAge())))
                .split()
                .handle(sftpIntake)
                .get();
    }
}
