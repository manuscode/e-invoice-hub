package io.github.manuscode.invoicehub.validation;

public sealed interface ValidationResult {

    record UnsupportedFormat() implements ValidationResult {
    }

    record Checked(InvoiceFormat format, boolean acceptable, String reportHtml) implements ValidationResult {
    }
}
