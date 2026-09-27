package io.github.manuscode.invoicehub.invoice;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public record InvoiceData(
        String invoiceNumber,
        LocalDate issueDate,
        LocalDate dueDate,
        String currency,
        Party seller,
        Party buyer,
        Totals totals,
        List<Line> lines) {

    public InvoiceData {
        lines = List.copyOf(lines);
    }

    public record Party(String name, String vatId) {
    }

    public record Totals(BigDecimal netAmount, BigDecimal taxAmount, BigDecimal grossAmount, BigDecimal payableAmount) {
    }

    public record Line(
            String id, String name, BigDecimal quantity, String unitCode, BigDecimal netPrice, BigDecimal netAmount) {
    }
}
