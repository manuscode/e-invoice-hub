package io.github.manuscode.erpsimulator;

import java.net.URI;
import java.util.Collection;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/erp/invoices")
class ErpInvoiceController {

    private final ErpInvoiceStore store;
    private final SimulatedFailures failures;

    ErpInvoiceController(ErpInvoiceStore store, SimulatedFailures failures) {
        this.store = store;
        this.failures = failures;
    }

    /**
     * Idempotent by invoice id: the same invoice again returns {@code 200} and keeps the first one.
     */
    @PostMapping
    ResponseEntity<Void> receive(@RequestBody ErpInvoice invoice) throws InterruptedException {
        if (invoice.invoiceId() == null || invoice.invoice() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invoiceId and invoice are required");
        }
        failures.apply();
        if (store.storeIfNew(invoice)) {
            return ResponseEntity.created(URI.create("/erp/invoices/" + invoice.invoiceId())).build();
        }
        return ResponseEntity.ok().build();
    }

    @GetMapping
    Collection<ErpInvoice> invoices() {
        return store.findAll();
    }

    @GetMapping("/{invoiceId}")
    ResponseEntity<ErpInvoice> invoice(@PathVariable UUID invoiceId) {
        return ResponseEntity.of(store.find(invoiceId));
    }
}
