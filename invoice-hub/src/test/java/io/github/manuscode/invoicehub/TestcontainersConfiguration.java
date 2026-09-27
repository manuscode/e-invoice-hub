package io.github.manuscode.invoicehub;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.DynamicPropertyRegistrar;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;

@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {

    @Bean
    @ServiceConnection
    PostgreSQLContainer postgres() {
        return new PostgreSQLContainer("postgres:18-alpine");
    }

    @Bean
    @ServiceConnection
    KafkaContainer kafka() {
        return new KafkaContainer("apache/kafka:4.3.1");
    }

    @Bean
    GreenMailContainer greenMail() {
        return new GreenMailContainer();
    }

    @Bean
    DynamicPropertyRegistrar mailProperties(GreenMailContainer greenMail) {
        return registry -> {
            registry.add("invoice-hub.mail.host", greenMail::getHost);
            registry.add("invoice-hub.mail.port", greenMail::imapPort);
            registry.add("invoice-hub.mail.username", () -> GreenMailContainer.USERNAME);
            registry.add("invoice-hub.mail.password", () -> GreenMailContainer.PASSWORD);
        };
    }
}
