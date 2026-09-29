package io.github.manuscode.invoicehub.delivery;

import io.github.manuscode.invoicehub.invoice.InvoiceAccepted;
import io.github.manuscode.invoicehub.invoice.InvoiceService;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import java.time.Duration;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.kafka.annotation.BackOff;
import org.springframework.kafka.annotation.DltHandler;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.annotation.RetryableTopic;
import org.springframework.kafka.retrytopic.DltStrategy;
import org.springframework.stereotype.Component;

/**
 * Delivers accepted invoices to the ERP. Failed attempts are retried via retry topics, so the main topic is not
 * blocked meanwhile. After the last attempt, or directly if the ERP rejects the invoice, the event goes to the DLT.
 */
@Component
class ErpDeliveryListener {

    private static final Logger log = LoggerFactory.getLogger(ErpDeliveryListener.class);

    private final ErpClient erpClient;
    private final InvoiceService invoiceService;
    private final ObservationRegistry observationRegistry;
    private final Timer deliveryDuration;
    private final Counter deadLettered;

    ErpDeliveryListener(ErpClient erpClient, InvoiceService invoiceService, ObservationRegistry observationRegistry,
            MeterRegistry meterRegistry) {
        this.erpClient = erpClient;
        this.invoiceService = invoiceService;
        this.observationRegistry = observationRegistry;
        this.deliveryDuration = Timer.builder("invoicehub.invoices.delivery.duration")
                .description("Time from receipt of an invoice until the ERP accepted it, including retries")
                .publishPercentileHistogram()
                // Covers all retries with the default backoff (10s, 30s, 90s) and a slow ERP.
                .maximumExpectedValue(Duration.ofMinutes(10))
                .register(meterRegistry);
        this.deadLettered = Counter.builder("invoicehub.invoices.dead.lettered")
                .description("Invoices that could not be delivered to the ERP and went to the DLT")
                .register(meterRegistry);
    }

    @RetryableTopic(
            attempts = "4",
            backOff = @BackOff(
                    delayString = "${invoice-hub.delivery.retry.delay}",
                    multiplierString = "${invoice-hub.delivery.retry.multiplier}"),
            exclude = ErpRejectedException.class,
            dltStrategy = DltStrategy.FAIL_ON_ERROR)
    @KafkaListener(topics = InvoiceAccepted.TOPIC, groupId = "invoice-hub-delivery")
    void deliver(InvoiceAccepted invoice) {
        observe("invoicehub.delivery", invoice, () -> {
            erpClient.send(invoice);
            invoiceService.markDelivered(invoice.invoiceId());
            deliveryDuration.record(Duration.between(invoice.receivedAt(), Instant.now()));
            log.info("Delivered invoice to the ERP");
        });
    }

    @DltHandler
    void deliveryFailed(InvoiceAccepted invoice) {
        observe("invoicehub.delivery.failed", invoice, () -> {
            // The cause is already logged by Spring Kafka when the event is sent to the DLT.
            log.error("Delivery of invoice {} to the ERP failed finally", invoice.invoiceId());
            invoiceService.markDeliveryFailed(invoice.invoiceId());
            deadLettered.increment();
        });
    }

    private void observe(String name, InvoiceAccepted invoice, Runnable action) {
        String invoiceId = invoice.invoiceId().toString();
        try (MDC.MDCCloseable ignored = MDC.putCloseable("invoiceId", invoiceId)) {
            Observation.createNotStarted(name, observationRegistry)
                    .highCardinalityKeyValue("invoice.id", invoiceId)
                    .observe(action);
        }
    }
}
