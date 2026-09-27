package io.github.manuscode.invoicehub.validation;

import java.io.ByteArrayInputStream;
import java.util.Optional;
import javax.xml.namespace.QName;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;

final class FormatDetector {

    private static final QName UBL_INVOICE =
            new QName("urn:oasis:names:specification:ubl:schema:xsd:Invoice-2", "Invoice");

    private FormatDetector() {
    }

    static Optional<InvoiceFormat> detect(byte[] content) {
        return rootElement(content)
                .filter(UBL_INVOICE::equals)
                .map(rootElement -> InvoiceFormat.XRECHNUNG_UBL);
    }

    private static Optional<QName> rootElement(byte[] content) {
        try {
            XMLStreamReader reader = secureInputFactory().createXMLStreamReader(new ByteArrayInputStream(content));
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

    private static XMLInputFactory secureInputFactory() {
        XMLInputFactory factory = XMLInputFactory.newFactory();
        factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
        factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
        return factory;
    }
}
