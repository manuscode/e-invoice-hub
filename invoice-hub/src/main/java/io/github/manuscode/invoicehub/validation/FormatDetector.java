package io.github.manuscode.invoicehub.validation;

import static java.nio.charset.StandardCharsets.US_ASCII;

import java.io.ByteArrayInputStream;
import java.util.Arrays;
import java.util.Optional;
import javax.xml.namespace.QName;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;

final class FormatDetector {

    private static final QName UBL_INVOICE =
            new QName("urn:oasis:names:specification:ubl:schema:xsd:Invoice-2", "Invoice");
    private static final QName CII_INVOICE =
            new QName("urn:un:unece:uncefact:data:standard:CrossIndustryInvoice:100", "CrossIndustryInvoice");
    private static final byte[] PDF_SIGNATURE = "%PDF-".getBytes(US_ASCII);

    private FormatDetector() {
    }

    static Optional<DetectedInvoice> detect(byte[] content) {
        return isPdf(content) ? detectInPdf(content) : detectInXml(content);
    }

    private static Optional<DetectedInvoice> detectInPdf(byte[] pdf) {
        return ZugferdExtractor.extractXml(pdf)
                .filter(xml -> rootElement(xml).filter(CII_INVOICE::equals).isPresent())
                .map(cii -> new DetectedInvoice(InvoiceFormat.ZUGFERD, cii));
    }

    private static Optional<DetectedInvoice> detectInXml(byte[] xml) {
        return rootElement(xml)
                .flatMap(FormatDetector::xmlFormat)
                .map(format -> new DetectedInvoice(format, xml));
    }

    private static Optional<InvoiceFormat> xmlFormat(QName rootElement) {
        if (UBL_INVOICE.equals(rootElement)) {
            return Optional.of(InvoiceFormat.XRECHNUNG_UBL);
        }
        if (CII_INVOICE.equals(rootElement)) {
            return Optional.of(InvoiceFormat.XRECHNUNG_CII);
        }
        return Optional.empty();
    }

    private static boolean isPdf(byte[] content) {
        return content.length >= PDF_SIGNATURE.length
                && Arrays.equals(content, 0, PDF_SIGNATURE.length, PDF_SIGNATURE, 0, PDF_SIGNATURE.length);
    }

    private static Optional<QName> rootElement(byte[] content) {
        try {
            XMLStreamReader reader = SecureXml.inputFactory().createXMLStreamReader(new ByteArrayInputStream(content));
            try {
                reader.nextTag();
                return Optional.of(reader.getName());
            } finally {
                reader.close();
            }
        } catch (XMLStreamException notXml) {
            // Anything that is not well-formed XML is simply not a supported format.
            return Optional.empty();
        }
    }
}
