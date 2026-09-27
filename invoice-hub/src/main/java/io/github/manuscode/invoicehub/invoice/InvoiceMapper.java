package io.github.manuscode.invoicehub.invoice;

import io.github.manuscode.invoicehub.validation.InvoiceFormat;

final class InvoiceMapper {

    private InvoiceMapper() {
    }

    /**
     * Only for invoices that passed validation, so mandatory fields are expected to be there.
     */
    static InvoiceData map(InvoiceFormat format, byte[] xml) {
        XmlDocument document = XmlDocument.parse(xml);
        return switch (format) {
            case XRECHNUNG_UBL -> UblMapper.map(document);
            case XRECHNUNG_CII, ZUGFERD -> CiiMapper.map(document);
        };
    }
}
