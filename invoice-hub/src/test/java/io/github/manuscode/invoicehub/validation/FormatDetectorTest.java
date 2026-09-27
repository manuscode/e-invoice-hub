package io.github.manuscode.invoicehub.validation;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class FormatDetectorTest {

    @Test
    void detectsUblInvoice() {
        byte[] ubl = """
                <Invoice xmlns="urn:oasis:names:specification:ubl:schema:xsd:Invoice-2"/>
                """.getBytes(UTF_8);

        assertThat(FormatDetector.detect(ubl)).contains(InvoiceFormat.XRECHNUNG_UBL);
    }

    @Test
    void doesNotDetectOtherXml() {
        byte[] otherXml = "<Invoice/>".getBytes(UTF_8);

        assertThat(FormatDetector.detect(otherXml)).isEmpty();
    }

    @Test
    void doesNotDetectNonXml() {
        byte[] pdf = "%PDF-1.7 no invoice".getBytes(UTF_8);

        assertThat(FormatDetector.detect(pdf)).isEmpty();
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
}
