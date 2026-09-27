package io.github.manuscode.invoicehub.invoice;

import io.github.manuscode.invoicehub.validation.InvoiceFormat;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class InvoiceRepository {

    private final JdbcClient jdbcClient;

    InvoiceRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    void insert(Invoice invoice, byte[] content) {
        jdbcClient.sql("""
                        insert into invoice (id, channel, filename, content, status, received_at)
                        values (:id, :channel, :filename, :content, :status, :receivedAt)
                        """)
                .param("id", invoice.id())
                .param("channel", invoice.channel().name())
                .param("filename", invoice.filename())
                .param("content", content)
                .param("status", invoice.status().name())
                .param("receivedAt", Timestamp.from(invoice.receivedAt()))
                .update();
    }

    void updateValidation(Invoice invoice, String validationReport) {
        int updatedRows = jdbcClient.sql("""
                        update invoice
                        set format = :format, status = :status, rejection_reason = :rejectionReason,
                            validation_report = :validationReport
                        where id = :id
                        """)
                .param("id", invoice.id())
                .param("format", nameOrNull(invoice.format()))
                .param("status", invoice.status().name())
                .param("rejectionReason", nameOrNull(invoice.rejectionReason()))
                .param("validationReport", validationReport)
                .update();
        if (updatedRows != 1) {
            throw new IllegalStateException("Invoice " + invoice.id() + " not found for update");
        }
    }

    Optional<Invoice> findById(UUID id) {
        return jdbcClient.sql("""
                        select id, channel, filename, format, status, rejection_reason, received_at
                        from invoice where id = :id
                        """)
                .param("id", id)
                .query(InvoiceRepository::toInvoice)
                .optional();
    }

    Optional<String> findValidationReport(UUID id) {
        return jdbcClient.sql("select validation_report from invoice where id = :id and validation_report is not null")
                .param("id", id)
                .query(String.class)
                .optional();
    }

    private static Invoice toInvoice(ResultSet row, int rowNumber) throws SQLException {
        String format = row.getString("format");
        String rejectionReason = row.getString("rejection_reason");
        return new Invoice(
                row.getObject("id", UUID.class),
                Channel.valueOf(row.getString("channel")),
                row.getString("filename"),
                format == null ? null : InvoiceFormat.valueOf(format),
                InvoiceStatus.valueOf(row.getString("status")),
                rejectionReason == null ? null : RejectionReason.valueOf(rejectionReason),
                row.getTimestamp("received_at").toInstant());
    }

    private static String nameOrNull(Enum<?> value) {
        return value == null ? null : value.name();
    }
}
