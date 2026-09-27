package io.github.manuscode.invoicehub.validation;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

class FormatDetectorTest {

    @Test
    void detectsUblInvoice() {
        byte[] ubl = """
                <Invoice xmlns="urn:oasis:names:specification:ubl:schema:xsd:Invoice-2"/>
                """.getBytes(UTF_8);

        assertThat(FormatDetector.detect(ubl))
                .hasValueSatisfying(invoice -> {
                    assertThat(invoice.format()).isEqualTo(InvoiceFormat.XRECHNUNG_UBL);
                    assertThat(invoice.xml()).isEqualTo(ubl);
                });
    }

    @Test
    void detectsCiiInvoice() {
        byte[] cii = """
                <rsm:CrossIndustryInvoice xmlns:rsm="urn:un:unece:uncefact:data:standard:CrossIndustryInvoice:100"/>
                """.getBytes(UTF_8);

        assertThat(FormatDetector.detect(cii))
                .hasValueSatisfying(invoice -> {
                    assertThat(invoice.format()).isEqualTo(InvoiceFormat.XRECHNUNG_CII);
                    assertThat(invoice.xml()).isEqualTo(cii);
                });
    }

    @Test
    void detectsZugferdPdfWithEmbeddedCii() throws IOException {
        byte[] pdf = sample("zugferd-en16931.pdf");

        assertThat(FormatDetector.detect(pdf))
                .hasValueSatisfying(invoice -> {
                    assertThat(invoice.format()).isEqualTo(InvoiceFormat.ZUGFERD);
                    assertThat(new String(invoice.xml(), UTF_8)).contains("<rsm:CrossIndustryInvoice");
                });
    }

    @Test
    void doesNotDetectPdfWithoutEmbeddedInvoice() throws IOException {
        assertThat(FormatDetector.detect(sample("pdf-without-invoice.pdf"))).isEmpty();
    }

    @Test
    void doesNotDetectBrokenPdf() {
        byte[] pdf = "%PDF-1.7 no invoice".getBytes(UTF_8);

        assertThat(FormatDetector.detect(pdf)).isEmpty();
    }

    @Test
    void doesNotDetectOtherXml() {
        byte[] otherXml = "<Invoice/>".getBytes(UTF_8);

        assertThat(FormatDetector.detect(otherXml)).isEmpty();
    }

    @Test
    void doesNotDetectNonXml() {
        byte[] text = "Invoice 4711, total 100 EUR".getBytes(UTF_8);

        assertThat(FormatDetector.detect(text)).isEmpty();
    }

    @Test
    void doesNotResolveExternalEntities() {
        byte[] xxe = """
                <?xml version="1.0"?>
                <!DOCTYPE Invoice [<!ENTITY secret SYSTEM "file:///etc/passwd">]>
                <Invoice xmlns="urn:oasis:names:specification:ubl:schema:xsd:Invoice-2">&secret;</Invoice>
                """.getBytes(UTF_8);

        assertThat(FormatDetector.detect(xxe)).isEmpty();
    }

    private static byte[] sample(String name) throws IOException {
        return new ClassPathResource("samples/" + name).getContentAsByteArray();
    }
}
