package io.github.manuscode.invoicehub.invoice;

import io.github.manuscode.invoicehub.validation.ValidationResult;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class InvoiceService {

    private final InvoiceRepository repository;

    InvoiceService(InvoiceRepository repository) {
        this.repository = repository;
    }

    public Invoice receive(String filename, byte[] content, Channel channel) {
        Invoice invoice = Invoice.received(UUID.randomUUID(), channel, filename, Instant.now());
        repository.insert(invoice, content);
        return invoice;
    }

    public Invoice completeValidation(Invoice received, ValidationResult result) {
        Invoice validated = received.validated(result);
        String report = result instanceof ValidationResult.Checked checked ? checked.reportHtml() : null;
        repository.updateValidation(validated, report);
        return validated;
    }

    public Optional<Invoice> findById(UUID id) {
        return repository.findById(id);
    }

    public Optional<String> findValidationReport(UUID id) {
        return repository.findValidationReport(id);
    }
}
