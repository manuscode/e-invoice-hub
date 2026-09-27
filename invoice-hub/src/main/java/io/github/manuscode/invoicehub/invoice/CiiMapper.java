package io.github.manuscode.invoicehub.invoice;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import org.w3c.dom.Node;

final class CiiMapper {

    private static final String INVOICE = "/rsm:CrossIndustryInvoice";
    private static final String TRANSACTION = INVOICE + "/rsm:SupplyChainTradeTransaction";
    private static final String AGREEMENT = TRANSACTION + "/ram:ApplicableHeaderTradeAgreement";
    private static final String SETTLEMENT = TRANSACTION + "/ram:ApplicableHeaderTradeSettlement";

    private CiiMapper() {
    }

    static InvoiceData map(XmlDocument xml) {
        Node root = xml.root();
        String dueDate = xml.text(root, SETTLEMENT + "/ram:SpecifiedTradePaymentTerms/ram:DueDateDateTime/udt:DateTimeString");
        return new InvoiceData(
                xml.requiredText(root, INVOICE + "/rsm:ExchangedDocument/ram:ID"),
                date(xml.requiredText(root, INVOICE + "/rsm:ExchangedDocument/ram:IssueDateTime/udt:DateTimeString")),
                dueDate == null ? null : date(dueDate),
                xml.requiredText(root, SETTLEMENT + "/ram:InvoiceCurrencyCode"),
                party(xml, AGREEMENT + "/ram:SellerTradeParty"),
                party(xml, AGREEMENT + "/ram:BuyerTradeParty"),
                totals(xml, root),
                xml.nodes(root, TRANSACTION + "/ram:IncludedSupplyChainTradeLineItem").stream()
                        .map(line -> line(xml, line))
                        .toList());
    }

    /**
     * EN 16931 only allows format 102 (yyyyMMdd) for these dates.
     */
    private static LocalDate date(String text) {
        return LocalDate.parse(text, DateTimeFormatter.BASIC_ISO_DATE);
    }

    private static InvoiceData.Party party(XmlDocument xml, String path) {
        Node party = xml.nodes(xml.root(), path).getFirst();
        return new InvoiceData.Party(
                xml.requiredText(party, "ram:Name"),
                xml.text(party, "ram:SpecifiedTaxRegistration/ram:ID[@schemeID = 'VA']"));
    }

    private static InvoiceData.Totals totals(XmlDocument xml, Node root) {
        String summation = SETTLEMENT + "/ram:SpecifiedTradeSettlementHeaderMonetarySummation";
        return new InvoiceData.Totals(
                xml.requiredAmount(root, summation + "/ram:TaxBasisTotalAmount"),
                // BT-110, a second TaxTotalAmount may carry the VAT in accounting currency (BT-111).
                xml.amount(root, summation + "/ram:TaxTotalAmount[@currencyID = " + SETTLEMENT + "/ram:InvoiceCurrencyCode]"),
                xml.requiredAmount(root, summation + "/ram:GrandTotalAmount"),
                xml.requiredAmount(root, summation + "/ram:DuePayableAmount"));
    }

    private static InvoiceData.Line line(XmlDocument xml, Node line) {
        return new InvoiceData.Line(
                xml.requiredText(line, "ram:AssociatedDocumentLineDocument/ram:LineID"),
                xml.requiredText(line, "ram:SpecifiedTradeProduct/ram:Name"),
                xml.requiredAmount(line, "ram:SpecifiedLineTradeDelivery/ram:BilledQuantity"),
                xml.text(line, "ram:SpecifiedLineTradeDelivery/ram:BilledQuantity/@unitCode"),
                xml.amount(line, "ram:SpecifiedLineTradeAgreement/ram:NetPriceProductTradePrice/ram:ChargeAmount"),
                xml.requiredAmount(line,
                        "ram:SpecifiedLineTradeSettlement/ram:SpecifiedTradeSettlementLineMonetarySummation/ram:LineTotalAmount"));
    }
}
