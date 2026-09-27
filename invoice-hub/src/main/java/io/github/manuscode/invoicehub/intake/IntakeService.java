package io.github.manuscode.invoicehub.intake;

import io.github.manuscode.invoicehub.invoice.Invoice;
import io.github.manuscode.invoicehub.invoice.InvoiceService;
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

    public Invoice receive(RawDocument document) {
        // Stored before validation, so the document is not lost if validation fails for technical reasons.
        Invoice received = invoiceService.receive(document.filename(), document.content(), document.channel());
        ValidationResult result = validationService.validate(document.content());
        return invoiceService.completeValidation(received, result);
    }
}
