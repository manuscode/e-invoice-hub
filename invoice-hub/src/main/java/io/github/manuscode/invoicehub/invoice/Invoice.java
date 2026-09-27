package io.github.manuscode.invoicehub.invoice;

import io.github.manuscode.invoicehub.validation.InvoiceFormat;
import io.github.manuscode.invoicehub.validation.ValidationResult;
import java.time.Instant;
import java.util.UUID;

public record Invoice(
        UUID id,
        Channel channel,
        String filename,
        InvoiceFormat format,
        InvoiceStatus status,
        RejectionReason rejectionReason,
        Instant receivedAt) {

    static Invoice received(UUID id, Channel channel, String filename, Instant receivedAt) {
        return new Invoice(id, channel, filename, null, InvoiceStatus.RECEIVED, null, receivedAt);
    }

    Invoice validated(ValidationResult result) {
        return switch (result) {
            case ValidationResult.UnsupportedFormat() ->
                    withOutcome(null, InvoiceStatus.REJECTED, RejectionReason.UNSUPPORTED_FORMAT);
            case ValidationResult.Checked checked when checked.acceptable() ->
                    withOutcome(checked.format(), InvoiceStatus.VALID, null);
            case ValidationResult.Checked checked ->
                    withOutcome(checked.format(), InvoiceStatus.REJECTED, RejectionReason.VALIDATION_FAILED);
        };
    }

    private Invoice withOutcome(InvoiceFormat format, InvoiceStatus status, RejectionReason rejectionReason) {
        return new Invoice(id, channel, filename, format, status, rejectionReason, receivedAt);
    }
}
