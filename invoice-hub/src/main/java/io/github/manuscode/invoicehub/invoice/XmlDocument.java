package io.github.manuscode.invoicehub.invoice;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;
import javax.xml.XMLConstants;
import javax.xml.namespace.NamespaceContext;
import javax.xml.namespace.QName;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.xpath.XPath;
import javax.xml.xpath.XPathConstants;
import javax.xml.xpath.XPathExpressionException;
import javax.xml.xpath.XPathFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

// XPath is not thread-safe, so there is one instance per document.
final class XmlDocument {

    private static final Map<String, String> NAMESPACES = Map.of(
            "ubl", "urn:oasis:names:specification:ubl:schema:xsd:Invoice-2",
            "cac", "urn:oasis:names:specification:ubl:schema:xsd:CommonAggregateComponents-2",
            "cbc", "urn:oasis:names:specification:ubl:schema:xsd:CommonBasicComponents-2",
            "rsm", "urn:un:unece:uncefact:data:standard:CrossIndustryInvoice:100",
            "ram", "urn:un:unece:uncefact:data:standard:ReusableAggregateBusinessInformationEntity:100",
            "udt", "urn:un:unece:uncefact:data:standard:UnqualifiedDataType:100");

    private final Document document;
    private final XPath xpath;

    private XmlDocument(Document document) {
        this.document = document;
        // The JDK default, not whatever XPath implementation (e.g. Saxon from KoSIT) is on the classpath.
        this.xpath = XPathFactory.newDefaultInstance().newXPath();
        this.xpath.setNamespaceContext(new FixedNamespaces());
    }

    static XmlDocument parse(byte[] xml) {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newDefaultInstance();
            factory.setNamespaceAware(true);
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);
            return new XmlDocument(factory.newDocumentBuilder().parse(new ByteArrayInputStream(xml)));
        } catch (ParserConfigurationException | SAXException | IOException e) {
            throw new IllegalArgumentException("Invoice XML cannot be parsed", e);
        }
    }

    Node root() {
        return document;
    }

    String text(Node context, String expression) {
        String text = evaluate(context, expression, XPathConstants.STRING).toString().strip();
        return text.isEmpty() ? null : text;
    }

    String requiredText(Node context, String expression) {
        String text = text(context, expression);
        if (text == null) {
            throw new IllegalArgumentException("Invoice XML has no value for " + expression);
        }
        return text;
    }

    BigDecimal amount(Node context, String expression) {
        String text = text(context, expression);
        return text == null ? null : new BigDecimal(text);
    }

    BigDecimal requiredAmount(Node context, String expression) {
        return new BigDecimal(requiredText(context, expression));
    }

    List<Node> nodes(Node context, String expression) {
        NodeList nodeList = (NodeList) evaluate(context, expression, XPathConstants.NODESET);
        return IntStream.range(0, nodeList.getLength()).mapToObj(nodeList::item).toList();
    }

    private Object evaluate(Node context, String expression, QName returnType) {
        try {
            return xpath.evaluate(expression, context, returnType);
        } catch (XPathExpressionException e) {
            throw new IllegalStateException("Invalid XPath " + expression, e);
        }
    }

    private static final class FixedNamespaces implements NamespaceContext {

        @Override
        public String getNamespaceURI(String prefix) {
            return NAMESPACES.getOrDefault(prefix, XMLConstants.NULL_NS_URI);
        }

        @Override
        public String getPrefix(String namespaceUri) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Iterator<String> getPrefixes(String namespaceUri) {
            throw new UnsupportedOperationException();
        }
    }
}
