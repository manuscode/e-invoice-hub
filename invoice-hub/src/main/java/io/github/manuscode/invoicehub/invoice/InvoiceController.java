package io.github.manuscode.invoicehub.invoice;

import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/invoices")
class InvoiceController {

    private final InvoiceService invoiceService;

    InvoiceController(InvoiceService invoiceService) {
        this.invoiceService = invoiceService;
    }

    @GetMapping("/{id}")
    ResponseEntity<Invoice> invoice(@PathVariable UUID id) {
        return ResponseEntity.of(invoiceService.findById(id));
    }

    @GetMapping(path = "/{id}/report", produces = MediaType.TEXT_HTML_VALUE)
    ResponseEntity<String> validationReport(@PathVariable UUID id) {
        return ResponseEntity.of(invoiceService.findValidationReport(id));
    }
}
