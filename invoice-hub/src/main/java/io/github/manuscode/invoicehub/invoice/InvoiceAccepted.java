package io.github.manuscode.invoicehub.invoice;

import java.util.UUID;
import org.springframework.modulith.events.Externalized;

/**
 * A valid invoice that is no duplicate and has to reach the ERP. Carries all data, so consumers don't need to
 * read the invoice again.
 */
@Externalized(InvoiceAccepted.TOPIC + "::#{invoiceId()}")
public record InvoiceAccepted(UUID invoiceId, InvoiceData data) {

    public static final String TOPIC = "invoice-accepted";
}
