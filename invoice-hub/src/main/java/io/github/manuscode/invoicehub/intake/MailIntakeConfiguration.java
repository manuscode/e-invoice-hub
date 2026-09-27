package io.github.manuscode.invoicehub.intake;

import jakarta.mail.Authenticator;
import jakarta.mail.Flags;
import jakarta.mail.PasswordAuthentication;
import jakarta.mail.search.FlagTerm;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.integration.dsl.IntegrationFlow;
import org.springframework.integration.dsl.Pollers;
import org.springframework.integration.mail.dsl.Mail;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(MailIntakeProperties.class)
class MailIntakeConfiguration {

    private static final int MAILS_PER_POLL = 10;

    @Bean
    IntegrationFlow mailIntakeFlow(MailIntakeProperties properties, MailIntake mailIntake) {
        String timeout = String.valueOf(properties.timeout().toMillis());
        return IntegrationFlow
                .from(Mail.imapInboundAdapter(properties.inboxUrl())
                                .javaMailAuthenticator(authenticator(properties))
                                .javaMailProperties(javaMail -> javaMail
                                        .put("mail." + properties.protocol() + ".connectiontimeout", timeout)
                                        .put("mail." + properties.protocol() + ".timeout", timeout))
                                // Processed mails leave the inbox, so every mail still there is open. Flags like SEEN or
                                // RECENT would skip mails after a failed attempt or a restart.
                                .searchTermStrategy((supportedFlags, folder) ->
                                        new FlagTerm(new Flags(Flags.Flag.DELETED), false))
                                .shouldMarkMessagesAsRead(false)
                                // MailIntake needs the open folder to move the mail and closes it afterwards. Closing
                                // it would break further mails of the same fetch, so only one is fetched at a time.
                                .autoCloseFolder(false)
                                .maxFetchSize(1),
                        adapter -> adapter.poller(
                                Pollers.fixedDelay(properties.pollInterval()).maxMessagesPerPoll(MAILS_PER_POLL)))
                .handle(mailIntake)
                .get();
    }

    private static Authenticator authenticator(MailIntakeProperties properties) {
        return new Authenticator() {
            @Override
            protected PasswordAuthentication getPasswordAuthentication() {
                return new PasswordAuthentication(properties.username(), properties.password());
            }
        };
    }
}
