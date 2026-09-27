package io.github.manuscode.erpsimulator;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

@Component
class ErpInvoiceStore {

    private final Map<UUID, ErpInvoice> invoices = new ConcurrentHashMap<>();

    /**
     * @return {@code false} if an invoice with the same id is already stored, it is kept unchanged
     */
    boolean storeIfNew(ErpInvoice invoice) {
        return invoices.putIfAbsent(invoice.invoiceId(), invoice) == null;
    }

    Optional<ErpInvoice> find(UUID invoiceId) {
        return Optional.ofNullable(invoices.get(invoiceId));
    }

    Collection<ErpInvoice> findAll() {
        return List.copyOf(invoices.values());
    }
}
