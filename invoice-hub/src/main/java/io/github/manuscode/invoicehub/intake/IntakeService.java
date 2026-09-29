package io.github.manuscode.invoicehub.intake;

import io.github.manuscode.invoicehub.invoice.Invoice;
import io.github.manuscode.invoicehub.invoice.InvoiceService;
import io.github.manuscode.invoicehub.invoice.ReceivedInvoice;
import io.github.manuscode.invoicehub.validation.ValidationResult;
import io.github.manuscode.invoicehub.validation.ValidationService;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;

@Service
public class IntakeService {

    private static final Logger log = LoggerFactory.getLogger(IntakeService.class);

    private final InvoiceService invoiceService;
    private final ValidationService validationService;
    private final ObservationRegistry observationRegistry;
    private final MeterRegistry meterRegistry;

    IntakeService(InvoiceService invoiceService, ValidationService validationService,
            ObservationRegistry observationRegistry, MeterRegistry meterRegistry) {
        this.invoiceService = invoiceService;
        this.validationService = validationService;
        this.observationRegistry = observationRegistry;
        this.meterRegistry = meterRegistry;
    }

    // Own observation, because mail and SFTP have no request that starts a trace.
    public ReceivedInvoice receive(RawDocument document) {
        Observation observation = Observation.createNotStarted("invoicehub.intake", observationRegistry)
                .lowCardinalityKeyValue("channel", document.channel().name());
        return observation.observe(() -> receive(document, observation));
    }

    private ReceivedInvoice receive(RawDocument document, Observation observation) {
        // Stored before validation, so the document is not lost if validation fails for technical reasons.
        ReceivedInvoice received = invoiceService.receive(document.filename(), document.content(), document.channel());
        String invoiceId = received.invoice().id().toString();
        observation.highCardinalityKeyValue("invoice.id", invoiceId);
        try (MDC.MDCCloseable ignored = MDC.putCloseable("invoiceId", invoiceId)) {
            if (received.alreadyKnown()) {
                log.info("Document {} via {} was received before", document.filename(), document.channel());
                return received;
            }
            ValidationResult result = validationService.validate(document.content());
            Invoice invoice = invoiceService.completeValidation(received.invoice(), result);
            countReceived(invoice);
            log.info("Received document {} via {} with status {}",
                    document.filename(), document.channel(), invoice.status());
            return new ReceivedInvoice(invoice, false);
        }
    }

    private void countReceived(Invoice invoice) {
        meterRegistry.counter("invoicehub.invoices.received",
                        "channel", invoice.channel().name(),
                        "format", invoice.format() == null ? "UNKNOWN" : invoice.format().name(),
                        "status", invoice.status().name())
                .increment();
    }
}
