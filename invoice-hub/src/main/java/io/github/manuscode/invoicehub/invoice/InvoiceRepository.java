package io.github.manuscode.invoicehub.invoice;

import io.github.manuscode.invoicehub.validation.InvoiceFormat;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.json.JsonMapper;

@Repository
class InvoiceRepository {

    private static final String INVOICE_COLUMNS = """
            id, channel, filename, format, status, rejection_reason, received_at, data, duplicate_of
            """;

    private final JdbcClient jdbcClient;
    private final JsonMapper jsonMapper;
    private final RowMapper<Invoice> invoiceRowMapper = this::toInvoice;

    InvoiceRepository(JdbcClient jdbcClient, JsonMapper jsonMapper) {
        this.jdbcClient = jdbcClient;
        this.jsonMapper = jsonMapper;
    }

    boolean insertIfNew(Invoice invoice, byte[] content, byte[] contentHash) {
        return jdbcClient.sql("""
                        insert into invoice (id, channel, filename, content, content_sha256, status, received_at)
                        values (:id, :channel, :filename, :content, :contentHash, :status, :receivedAt)
                        on conflict (content_sha256) do nothing
                        """)
                .param("id", invoice.id())
                .param("channel", invoice.channel().name())
                .param("filename", invoice.filename())
                .param("content", content)
                .param("contentHash", contentHash)
                .param("status", invoice.status().name())
                .param("receivedAt", Timestamp.from(invoice.receivedAt()))
                .update() == 1;
    }

    /**
     * @throws org.springframework.dao.DuplicateKeyException if another original with the same key exists
     */
    void updateValidation(Invoice invoice, BusinessKey key, String validationReport) {
        int updatedRows = jdbcClient.sql("""
                        update invoice
                        set format = :format, status = :status, rejection_reason = :rejectionReason,
                            validation_report = :validationReport, data = cast(:data as jsonb),
                            seller_key = :sellerKey, invoice_number = :invoiceNumber, duplicate_of = :duplicateOf
                        where id = :id
                        """)
                .param("id", invoice.id())
                .param("format", nameOrNull(invoice.format()))
                .param("status", invoice.status().name())
                .param("rejectionReason", nameOrNull(invoice.rejectionReason()))
                .param("validationReport", validationReport)
                .param("data", invoice.data() == null ? null : jsonMapper.writeValueAsString(invoice.data()))
                .param("sellerKey", key == null ? null : key.seller())
                .param("invoiceNumber", key == null ? null : key.invoiceNumber())
                .param("duplicateOf", invoice.duplicateOf())
                .update();
        if (updatedRows != 1) {
            throw new IllegalStateException("Invoice " + invoice.id() + " not found for update");
        }
    }

    /**
     * @return {@code false} if the invoice does not exist or its current status is not allowed
     */
    boolean updateStatus(UUID id, InvoiceStatus target, Set<InvoiceStatus> allowedCurrent) {
        return jdbcClient.sql("update invoice set status = :target where id = :id and status in (:allowedCurrent)")
                .param("id", id)
                .param("target", target.name())
                .param("allowedCurrent", allowedCurrent.stream().map(InvoiceStatus::name).toList())
                .update() == 1;
    }

    Optional<Invoice> findById(UUID id) {
        return jdbcClient.sql("select " + INVOICE_COLUMNS + " from invoice where id = :id")
                .param("id", id)
                .query(invoiceRowMapper)
                .optional();
    }

    Optional<Invoice> findByContentHash(byte[] contentHash) {
        return jdbcClient.sql("select " + INVOICE_COLUMNS + " from invoice where content_sha256 = :contentHash")
                .param("contentHash", contentHash)
                .query(invoiceRowMapper)
                .optional();
    }

    Optional<UUID> findOriginal(BusinessKey key) {
        return jdbcClient.sql("""
                        select id from invoice
                        where seller_key = :sellerKey and invoice_number = :invoiceNumber and duplicate_of is null
                        """)
                .param("sellerKey", key.seller())
                .param("invoiceNumber", key.invoiceNumber())
                .query(UUID.class)
                .optional();
    }

    Optional<String> findValidationReport(UUID id) {
        return jdbcClient.sql("select validation_report from invoice where id = :id and validation_report is not null")
                .param("id", id)
                .query(String.class)
                .optional();
    }

    private Invoice toInvoice(ResultSet row, int rowNumber) throws SQLException {
        String format = row.getString("format");
        String rejectionReason = row.getString("rejection_reason");
        String data = row.getString("data");
        return new Invoice(
                row.getObject("id", UUID.class),
                Channel.valueOf(row.getString("channel")),
                row.getString("filename"),
                format == null ? null : InvoiceFormat.valueOf(format),
                InvoiceStatus.valueOf(row.getString("status")),
                rejectionReason == null ? null : RejectionReason.valueOf(rejectionReason),
                row.getTimestamp("received_at").toInstant(),
                data == null ? null : jsonMapper.readValue(data, InvoiceData.class),
                row.getObject("duplicate_of", UUID.class));
    }

    private static String nameOrNull(Enum<?> value) {
        return value == null ? null : value.name();
    }
}
