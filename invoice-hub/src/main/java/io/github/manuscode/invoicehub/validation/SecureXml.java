package io.github.manuscode.invoicehub.validation;

import javax.xml.stream.XMLInputFactory;

final class SecureXml {

    private SecureXml() {
    }

    static XMLInputFactory inputFactory() {
        XMLInputFactory factory = XMLInputFactory.newFactory();
        factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
        factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
        return factory;
    }
}
