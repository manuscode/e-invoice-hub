package io.github.manuscode.invoicehub.validation;

import java.io.ByteArrayInputStream;
import java.util.Optional;
import org.mustangproject.ZUGFeRD.ZUGFeRDImporter;

final class ZugferdExtractor {

    private ZugferdExtractor() {
    }

    /**
     * Mustang does not throw for broken PDFs or PDFs without invoice, it logs and returns no XML.
     */
    static Optional<byte[]> extractXml(byte[] pdf) {
        return Optional.ofNullable(new ZUGFeRDImporter(new ByteArrayInputStream(pdf)).getRawXML());
    }
}
