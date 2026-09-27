package io.github.manuscode.invoicehub.invoice;

import io.github.manuscode.invoicehub.validation.ValidationResult;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

@Service
public class InvoiceService {

    private final InvoiceRepository repository;

    InvoiceService(InvoiceRepository repository) {
        this.repository = repository;
    }

    // Parallel calls with the same document are safe, the unique content hash decides which one stores it.
    public ReceivedInvoice receive(String filename, byte[] content, Channel channel) {
        Invoice invoice = Invoice.received(UUID.randomUUID(), channel, filename, Instant.now());
        byte[] contentHash = sha256(content);
        if (repository.insertIfNew(invoice, content, contentHash)) {
            return new ReceivedInvoice(invoice, false);
        }
        Invoice existing = repository.findByContentHash(contentHash)
                .orElseThrow(() -> new IllegalStateException("Invoice with same content hash vanished"));
        return new ReceivedInvoice(existing, true);
    }

    public Invoice completeValidation(Invoice received, ValidationResult result) {
        Invoice validated = received.validated(result);
        if (!(result instanceof ValidationResult.Checked checked)) {
            repository.updateValidation(validated, null, null);
            return validated;
        }
        if (validated.status() != InvoiceStatus.VALID) {
            repository.updateValidation(validated, null, checked.reportHtml());
            return validated;
        }
        Invoice mapped = validated.mapped(InvoiceMapper.map(checked.format(), checked.xml()));
        BusinessKey key = BusinessKey.of(mapped.data());
        Optional<UUID> original = repository.findOriginal(key);
        if (original.isPresent()) {
            return saveDuplicate(mapped, original.get(), key, checked.reportHtml());
        }
        try {
            repository.updateValidation(mapped, key, checked.reportHtml());
            return mapped;
        } catch (DuplicateKeyException concurrentOriginal) {
            // Another document with the same key became the original between lookup and update.
            UUID winner = repository.findOriginal(key)
                    .orElseThrow(() -> new IllegalStateException("Original for " + key + " vanished", concurrentOriginal));
            return saveDuplicate(mapped, winner, key, checked.reportHtml());
        }
    }

    public Optional<Invoice> findById(UUID id) {
        return repository.findById(id);
    }

    public Optional<String> findValidationReport(UUID id) {
        return repository.findValidationReport(id);
    }

    private Invoice saveDuplicate(Invoice mapped, UUID original, BusinessKey key, String report) {
        Invoice duplicate = mapped.duplicateOf(original);
        repository.updateValidation(duplicate, key, report);
        return duplicate;
    }

    private static byte[] sha256(byte[] content) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(content);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("Every JVM supports SHA-256", e);
        }
    }
}
