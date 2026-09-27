package io.github.manuscode.invoicehub.validation;

import java.io.ByteArrayInputStream;
import java.util.Optional;
import java.util.Set;
import javax.xml.namespace.QName;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;

/**
 * The profile is read from the XML and not from the PDF metadata, because the XML is what gets validated.
 */
final class ZugferdProfile {

    private static final QName GUIDELINE = new QName(
            "urn:un:unece:uncefact:data:standard:ReusableAggregateBusinessInformationEntity:100",
            "GuidelineSpecifiedDocumentContextParameter");

    // MINIMUM and BASIC WL don't carry all information required by EN 16931, so they are no e-invoice in Germany.
    private static final Set<String> NO_E_INVOICE = Set.of(
            "urn:factur-x.eu:1p0:minimum",
            "urn:factur-x.eu:1p0:basicwl",
            "urn:zugferd.de:2p0:minimum",
            "urn:zugferd.de:2p0:basicwl");

    private ZugferdProfile() {
    }

    static boolean isEInvoice(byte[] cii) {
        return guidelineId(cii)
                .map(guidelineId -> !NO_E_INVOICE.contains(guidelineId))
                .orElse(true);
    }

    private static Optional<String> guidelineId(byte[] cii) {
        try {
            XMLStreamReader reader = SecureXml.inputFactory().createXMLStreamReader(new ByteArrayInputStream(cii));
            try {
                return nextGuidelineId(reader);
            } finally {
                reader.close();
            }
        } catch (XMLStreamException e) {
            throw new IllegalArgumentException("CII is not well-formed XML", e);
        }
    }

    private static Optional<String> nextGuidelineId(XMLStreamReader reader) throws XMLStreamException {
        while (reader.hasNext()) {
            if (reader.next() == XMLStreamConstants.START_ELEMENT && GUIDELINE.equals(reader.getName())) {
                reader.nextTag();
                return Optional.of(reader.getElementText().strip());
            }
        }
        return Optional.empty();
    }
}
