package io.github.manuscode.invoicehub.delivery;

import io.github.manuscode.invoicehub.invoice.InvoiceAccepted;
import io.github.manuscode.invoicehub.invoice.InvoiceService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

    ErpDeliveryListener(ErpClient erpClient, InvoiceService invoiceService) {
        this.erpClient = erpClient;
        this.invoiceService = invoiceService;
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
        erpClient.send(invoice);
        invoiceService.markDelivered(invoice.invoiceId());
    }

    @DltHandler
    void deliveryFailed(InvoiceAccepted invoice) {
        // The cause is already logged by Spring Kafka when the event is sent to the DLT.
        log.error("Delivery of invoice {} to the ERP failed finally", invoice.invoiceId());
        invoiceService.markDeliveryFailed(invoice.invoiceId());
    }
}
