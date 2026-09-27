package io.github.manuscode.invoicehub.invoice;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

class BusinessKeyTest {

    @Test
    void usesNormalizedSellerVatId() {
        BusinessKey key = BusinessKey.of(invoice(new InvoiceData.Party("ACME GmbH", "de 123 456 789"), " R-1 "));

        assertThat(key).isEqualTo(new BusinessKey("VAT:DE123456789", "R-1"));
    }

    @Test
    void fallsBackToNormalizedSellerNameWithoutVatId() {
        BusinessKey key = BusinessKey.of(invoice(new InvoiceData.Party("  ACME   GmbH ", null), "R-1"));

        assertThat(key).isEqualTo(new BusinessKey("NAME:acme gmbh", "R-1"));
    }

    private static InvoiceData invoice(InvoiceData.Party seller, String invoiceNumber) {
        return new InvoiceData(invoiceNumber, LocalDate.of(2026, 9, 1), null, "EUR", seller,
                new InvoiceData.Party("Buyer", null),
                new InvoiceData.Totals(BigDecimal.TEN, BigDecimal.ZERO, BigDecimal.TEN, BigDecimal.TEN), List.of());
    }
}
