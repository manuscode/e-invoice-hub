package io.github.manuscode.invoicehub.invoice;

import io.github.manuscode.invoicehub.validation.ValidationResult;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class InvoiceService {

    private static final Logger log = LoggerFactory.getLogger(InvoiceService.class);

    private final InvoiceRepository repository;
    private final ApplicationEventPublisher events;
    private final TransactionTemplate transaction;

    InvoiceService(InvoiceRepository repository, ApplicationEventPublisher events, TransactionTemplate transaction) {
        this.repository = repository;
        this.events = events;
        this.transaction = transaction;
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
            accept(mapped, key, checked.reportHtml());
            return mapped;
        } catch (DuplicateKeyException concurrentOriginal) {
            // Another document with the same key became the original between lookup and update.
            UUID winner = repository.findOriginal(key)
                    .orElseThrow(() -> new IllegalStateException("Original for " + key + " vanished", concurrentOriginal));
            return saveDuplicate(mapped, winner, key, checked.reportHtml());
        }
    }

    public void markDelivered(UUID id) {
        changeStatus(id, InvoiceStatus.DELIVERED,
                Set.of(InvoiceStatus.VALID, InvoiceStatus.DELIVERED, InvoiceStatus.DELIVERY_FAILED));
    }

    public void markDeliveryFailed(UUID id) {
        // A late failure of a redelivered event must not overwrite a successful delivery.
        changeStatus(id, InvoiceStatus.DELIVERY_FAILED, Set.of(InvoiceStatus.VALID, InvoiceStatus.DELIVERY_FAILED));
    }

    public Optional<Invoice> findById(UUID id) {
        return repository.findById(id);
    }

    public Optional<String> findValidationReport(UUID id) {
        return repository.findValidationReport(id);
    }

    /**
     * Invoice and event publication are committed together (outbox), so no accepted invoice misses the ERP. Only this
     * step runs in a transaction: after a {@link DuplicateKeyException} Postgres aborts the transaction, but the caller
     * still has to store the invoice as duplicate.
     */
    private void accept(Invoice invoice, BusinessKey key, String report) {
        transaction.executeWithoutResult(status -> {
            repository.updateValidation(invoice, key, report);
            events.publishEvent(new InvoiceAccepted(invoice.id(), invoice.receivedAt(), invoice.data()));
        });
    }

    private void changeStatus(UUID id, InvoiceStatus target, Set<InvoiceStatus> allowedCurrent) {
        if (!repository.updateStatus(id, target, allowedCurrent)) {
            log.warn("Status of invoice {} not changed to {}: invoice is missing or its status is not one of {}",
                    id, target, allowedCurrent);
        }
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
