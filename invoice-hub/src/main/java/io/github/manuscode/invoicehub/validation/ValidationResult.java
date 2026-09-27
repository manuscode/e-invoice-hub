package io.github.manuscode.invoicehub.validation;

public sealed interface ValidationResult {

    record UnsupportedFormat() implements ValidationResult {
    }

    record UnsupportedProfile(InvoiceFormat format) implements ValidationResult {
    }

    /**
     * @param xml the validated invoice XML, for ZUGFeRD the CII extracted from the PDF
     */
    record Checked(InvoiceFormat format, byte[] xml, boolean acceptable, String reportHtml) implements ValidationResult {
    }
}
