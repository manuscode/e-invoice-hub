package io.github.manuscode.invoicehub.delivery;

import io.github.manuscode.invoicehub.invoice.InvoiceAccepted;
import io.github.manuscode.invoicehub.invoice.InvoiceData;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.HttpClientSettings;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Sends invoices to the ERP. Server errors and network problems are thrown as they are, so the caller retries them.
 */
@Component
@EnableConfigurationProperties(ErpProperties.class)
class ErpClient {

    private final RestClient restClient;

    ErpClient(RestClient.Builder restClientBuilder, ErpProperties properties) {
        HttpClientSettings settings = HttpClientSettings.defaults()
                .withTimeouts(properties.connectTimeout(), properties.readTimeout());
        this.restClient = restClientBuilder
                .baseUrl(properties.baseUrl())
                // Without this, the JDK client tries an HTTP/2 upgrade on plain HTTP, which many servers cancel.
                .requestFactory(ClientHttpRequestFactoryBuilder.jdk()
                        .withHttpClientCustomizer(client -> client.version(HttpClient.Version.HTTP_1_1))
                        .build(settings))
                .build();
    }

    /**
     * @throws ErpRejectedException if the ERP rejects the invoice, a retry would fail again
     */
    void send(InvoiceAccepted invoice) {
        restClient.post()
                .uri("/erp/invoices")
                .body(new ErpInvoice(invoice.invoiceId(), invoice.data()))
                .retrieve()
                .onStatus(ErpClient::isRejection, (request, response) -> {
                    throw new ErpRejectedException(invoice.invoiceId(), response.getStatusCode(),
                            new String(response.getBody().readAllBytes(), StandardCharsets.UTF_8));
                })
                .toBodilessEntity();
    }

    // Timeout and rate limit are temporary, although they are client errors.
    private static boolean isRejection(HttpStatusCode status) {
        return status.is4xxClientError()
                && !status.isSameCodeAs(HttpStatus.REQUEST_TIMEOUT)
                && !status.isSameCodeAs(HttpStatus.TOO_MANY_REQUESTS);
    }

    private record ErpInvoice(UUID invoiceId, InvoiceData invoice) {
    }
}
