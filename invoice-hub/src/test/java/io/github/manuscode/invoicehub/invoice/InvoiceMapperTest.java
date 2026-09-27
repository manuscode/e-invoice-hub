package io.github.manuscode.invoicehub.invoice;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.manuscode.invoicehub.validation.InvoiceFormat;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

class InvoiceMapperTest {

    @Test
    void mapsXRechnungUbl() throws IOException {
        InvoiceData data = InvoiceMapper.map(InvoiceFormat.XRECHNUNG_UBL, sample("xrechnung-ubl-valid.xml"));

        assertThat(data.invoiceNumber()).isEqualTo("123456XX");
        assertThat(data.issueDate()).isEqualTo(LocalDate.of(2016, 4, 4));
        assertThat(data.dueDate()).isNull();
        assertThat(data.currency()).isEqualTo("EUR");
        assertThat(data.seller()).isEqualTo(new InvoiceData.Party("[Seller name]", "DE 123456789"));
        assertThat(data.buyer()).isEqualTo(new InvoiceData.Party("[Buyer name]", null));
        assertThat(data.totals()).isEqualTo(new InvoiceData.Totals(
                new BigDecimal("314.86"), new BigDecimal("22.04"), new BigDecimal("336.9"), new BigDecimal("336.9")));
        assertThat(data.lines()).containsExactly(
                new InvoiceData.Line("Zeitschrift [...]", "Zeitschrift [...]", BigDecimal.ONE, "XPP",
                        new BigDecimal("288.79"), new BigDecimal("288.79")),
                new InvoiceData.Line("Porto + Versandkosten", "Porto + Versandkosten", BigDecimal.ONE, "XPP",
                        new BigDecimal("26.07"), new BigDecimal("26.07")));
    }

    @Test
    void mapsXRechnungCiiToSameDataAsUbl() throws IOException {
        // Both samples are the same invoice from the XRechnung testsuite.
        InvoiceData ubl = InvoiceMapper.map(InvoiceFormat.XRECHNUNG_UBL, sample("xrechnung-ubl-valid.xml"));
        InvoiceData cii = InvoiceMapper.map(InvoiceFormat.XRECHNUNG_CII, sample("xrechnung-cii-valid.xml"));

        assertThat(cii).isEqualTo(ubl);
    }

    @Test
    void mapsCiiDueDate() {
        String cii = """
                <rsm:CrossIndustryInvoice xmlns:rsm="urn:un:unece:uncefact:data:standard:CrossIndustryInvoice:100"
                    xmlns:ram="urn:un:unece:uncefact:data:standard:ReusableAggregateBusinessInformationEntity:100"
                    xmlns:udt="urn:un:unece:uncefact:data:standard:UnqualifiedDataType:100">
                  <rsm:ExchangedDocument>
                    <ram:ID>R-1</ram:ID>
                    <ram:IssueDateTime><udt:DateTimeString format="102">20260901</udt:DateTimeString></ram:IssueDateTime>
                  </rsm:ExchangedDocument>
                  <rsm:SupplyChainTradeTransaction>
                    <ram:ApplicableHeaderTradeAgreement>
                      <ram:SellerTradeParty><ram:Name>Seller</ram:Name></ram:SellerTradeParty>
                      <ram:BuyerTradeParty><ram:Name>Buyer</ram:Name></ram:BuyerTradeParty>
                    </ram:ApplicableHeaderTradeAgreement>
                    <ram:ApplicableHeaderTradeSettlement>
                      <ram:InvoiceCurrencyCode>EUR</ram:InvoiceCurrencyCode>
                      <ram:SpecifiedTradePaymentTerms>
                        <ram:DueDateDateTime><udt:DateTimeString format="102">20261001</udt:DateTimeString></ram:DueDateDateTime>
                      </ram:SpecifiedTradePaymentTerms>
                      <ram:SpecifiedTradeSettlementHeaderMonetarySummation>
                        <ram:TaxBasisTotalAmount>100.00</ram:TaxBasisTotalAmount>
                        <ram:TaxTotalAmount currencyID="USD">21.00</ram:TaxTotalAmount>
                        <ram:GrandTotalAmount>119.00</ram:GrandTotalAmount>
                        <ram:DuePayableAmount>119.00</ram:DuePayableAmount>
                      </ram:SpecifiedTradeSettlementHeaderMonetarySummation>
                    </ram:ApplicableHeaderTradeSettlement>
                  </rsm:SupplyChainTradeTransaction>
                </rsm:CrossIndustryInvoice>
                """;

        InvoiceData data = InvoiceMapper.map(InvoiceFormat.XRECHNUNG_CII, cii.getBytes());

        assertThat(data.dueDate()).isEqualTo(LocalDate.of(2026, 10, 1));
        assertThat(data.seller().vatId()).isNull();
        // Only VAT in invoice currency counts, USD is the accounting currency here.
        assertThat(data.totals().taxAmount()).isNull();
        assertThat(data.lines()).isEmpty();
    }

    private static byte[] sample(String name) throws IOException {
        return new ClassPathResource("samples/" + name).getContentAsByteArray();
    }
}
