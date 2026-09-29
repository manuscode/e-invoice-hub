package io.github.manuscode.invoicehub.intake;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.manuscode.invoicehub.invoice.Channel;
import org.junit.jupiter.api.Test;

class RawDocumentTest {

    @Test
    void removesControlCharactersFromFilename() {
        RawDocument document = document("invoice\r\n2026-01-01 INFO forged\u0000.xml");

        assertThat(document.filename()).isEqualTo("invoice2026-01-01 INFO forged.xml");
    }

    @Test
    void cutsFilenameToLengthOfDatabaseColumn() {
        // Each emoji is one character in Postgres, but two chars in Java.
        RawDocument document = document("🧾".repeat(300) + ".xml");

        assertThat(document.filename().codePointCount(0, document.filename().length())).isEqualTo(255);
        assertThat(document.filename()).isEqualTo("🧾".repeat(255));
    }

    @Test
    void keepsNormalFilename() {
        assertThat(document("Rechnung 2026-0001 (Müller GmbH).pdf").filename())
                .isEqualTo("Rechnung 2026-0001 (Müller GmbH).pdf");
    }

    @Test
    void allowsMissingFilename() {
        assertThat(document(null).filename()).isNull();
    }

    private static RawDocument document(String filename) {
        return new RawDocument(filename, new byte[0], Channel.REST);
    }
}
