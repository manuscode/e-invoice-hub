package io.github.manuscode.invoicehub.invoice;

import java.util.Locale;

// Normalized, so formatting differences between documents don't hide a duplicate.
record BusinessKey(String seller, String invoiceNumber) {

    static BusinessKey of(InvoiceData data) {
        String vatId = data.seller().vatId();
        String seller = vatId != null
                ? "VAT:" + vatId.replaceAll("\\s+", "").toUpperCase(Locale.ROOT)
                : "NAME:" + data.seller().name().strip().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
        return new BusinessKey(seller, data.invoiceNumber().strip());
    }
}
