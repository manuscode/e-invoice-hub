package io.github.manuscode.invoicehub.validation;

/**
 * @param xml the invoice XML to validate, for ZUGFeRD the CII extracted from the PDF
 */
record DetectedInvoice(InvoiceFormat format, byte[] xml) {
}
