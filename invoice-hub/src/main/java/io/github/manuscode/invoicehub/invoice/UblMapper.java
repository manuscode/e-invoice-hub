package io.github.manuscode.invoicehub.invoice;

import java.time.LocalDate;
import org.w3c.dom.Node;

final class UblMapper {

    private static final String INVOICE = "/ubl:Invoice";

    private UblMapper() {
    }

    static InvoiceData map(XmlDocument xml) {
        Node root = xml.root();
        String dueDate = xml.text(root, INVOICE + "/cbc:DueDate");
        return new InvoiceData(
                xml.requiredText(root, INVOICE + "/cbc:ID"),
                LocalDate.parse(xml.requiredText(root, INVOICE + "/cbc:IssueDate")),
                dueDate == null ? null : LocalDate.parse(dueDate),
                xml.requiredText(root, INVOICE + "/cbc:DocumentCurrencyCode"),
                party(xml, INVOICE + "/cac:AccountingSupplierParty/cac:Party"),
                party(xml, INVOICE + "/cac:AccountingCustomerParty/cac:Party"),
                totals(xml, root),
                xml.nodes(root, INVOICE + "/cac:InvoiceLine").stream().map(line -> line(xml, line)).toList());
    }

    private static InvoiceData.Party party(XmlDocument xml, String path) {
        Node party = xml.nodes(xml.root(), path).getFirst();
        return new InvoiceData.Party(
                xml.requiredText(party, "cac:PartyLegalEntity/cbc:RegistrationName"),
                xml.text(party, "cac:PartyTaxScheme[cac:TaxScheme/cbc:ID = 'VAT']/cbc:CompanyID"));
    }

    private static InvoiceData.Totals totals(XmlDocument xml, Node root) {
        String monetaryTotal = INVOICE + "/cac:LegalMonetaryTotal";
        return new InvoiceData.Totals(
                xml.requiredAmount(root, monetaryTotal + "/cbc:TaxExclusiveAmount"),
                // BT-110, a second TaxTotal may carry the VAT in accounting currency (BT-111).
                xml.amount(root, INVOICE + "/cac:TaxTotal/cbc:TaxAmount[@currencyID = /ubl:Invoice/cbc:DocumentCurrencyCode]"),
                xml.requiredAmount(root, monetaryTotal + "/cbc:TaxInclusiveAmount"),
                xml.requiredAmount(root, monetaryTotal + "/cbc:PayableAmount"));
    }

    private static InvoiceData.Line line(XmlDocument xml, Node line) {
        return new InvoiceData.Line(
                xml.requiredText(line, "cbc:ID"),
                xml.requiredText(line, "cac:Item/cbc:Name"),
                xml.requiredAmount(line, "cbc:InvoicedQuantity"),
                xml.text(line, "cbc:InvoicedQuantity/@unitCode"),
                xml.amount(line, "cac:Price/cbc:PriceAmount"),
                xml.requiredAmount(line, "cbc:LineExtensionAmount"));
    }
}
