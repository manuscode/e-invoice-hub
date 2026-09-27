package io.github.manuscode.invoicehub.invoice;

/**
 * @param alreadyKnown the same document (same SHA-256) was received before, {@link #invoice()} is that invoice
 */
public record ReceivedInvoice(Invoice invoice, boolean alreadyKnown) {
}
