package io.github.manuscode.invoicehub.validation;

import org.springframework.stereotype.Service;

@Service
public class ValidationService {

    private final KosItValidator kosItValidator;

    ValidationService(KosItValidator kosItValidator) {
        this.kosItValidator = kosItValidator;
    }

    public ValidationResult validate(byte[] content) {
        return FormatDetector.detect(content)
                .map(this::validate)
                .orElseGet(ValidationResult.UnsupportedFormat::new);
    }

    private ValidationResult validate(DetectedInvoice invoice) {
        if (invoice.format() == InvoiceFormat.ZUGFERD && !ZugferdProfile.isEInvoice(invoice.xml())) {
            return new ValidationResult.UnsupportedProfile(invoice.format());
        }
        return kosItValidator.validate(invoice.xml(), invoice.format());
    }
}
