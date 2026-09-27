package io.github.manuscode.invoicehub.intake;

import io.github.manuscode.invoicehub.invoice.InvoiceService;
import io.github.manuscode.invoicehub.invoice.ReceivedInvoice;
import io.github.manuscode.invoicehub.validation.ValidationResult;
import io.github.manuscode.invoicehub.validation.ValidationService;
import org.springframework.stereotype.Service;

@Service
public class IntakeService {

    private final InvoiceService invoiceService;
    private final ValidationService validationService;

    IntakeService(InvoiceService invoiceService, ValidationService validationService) {
        this.invoiceService = invoiceService;
        this.validationService = validationService;
    }

    public ReceivedInvoice receive(RawDocument document) {
        // Stored before validation, so the document is not lost if validation fails for technical reasons.
        ReceivedInvoice received = invoiceService.receive(document.filename(), document.content(), document.channel());
        if (received.alreadyKnown()) {
            return received;
        }
        ValidationResult result = validationService.validate(document.content());
        return new ReceivedInvoice(invoiceService.completeValidation(received.invoice(), result), false);
    }
}
