package io.github.manuscode.invoicehub.delivery;

import java.net.URI;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("invoice-hub.erp")
record ErpProperties(URI baseUrl, Duration connectTimeout, Duration readTimeout) {
}
