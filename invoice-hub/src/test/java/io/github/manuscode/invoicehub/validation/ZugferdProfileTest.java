package io.github.manuscode.invoicehub.validation;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ZugferdProfileTest {

    @ParameterizedTest
    @ValueSource(strings = {
            "urn:factur-x.eu:1p0:minimum",
            "urn:factur-x.eu:1p0:basicwl",
            "urn:zugferd.de:2p0:minimum",
            "urn:zugferd.de:2p0:basicwl"})
    void minimumAndBasicWlAreNoEInvoice(String guidelineId) {
        assertThat(ZugferdProfile.isEInvoice(ciiWithGuideline(guidelineId))).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "urn:cen.eu:en16931:2017",
            "urn:cen.eu:en16931:2017#compliant#urn:factur-x.eu:1p0:basic",
            "urn:cen.eu:en16931:2017#conformant#urn:factur-x.eu:1p0:extended",
            "urn:cen.eu:en16931:2017#compliant#urn:xeinkauf.de:kosit:xrechnung_3.0"})
    void otherProfilesAreEInvoice(String guidelineId) {
        assertThat(ZugferdProfile.isEInvoice(ciiWithGuideline(guidelineId))).isTrue();
    }

    @Test
    void ignoresWhitespaceAroundGuidelineId() {
        assertThat(ZugferdProfile.isEInvoice(ciiWithGuideline("\n    urn:factur-x.eu:1p0:minimum\n"))).isFalse();
    }

    @Test
    void leavesCiiWithoutGuidelineToKosItValidation() {
        byte[] cii = """
                <rsm:CrossIndustryInvoice xmlns:rsm="urn:un:unece:uncefact:data:standard:CrossIndustryInvoice:100"/>
                """.getBytes(UTF_8);

        assertThat(ZugferdProfile.isEInvoice(cii)).isTrue();
    }

    @Test
    void failsForMalformedXml() {
        assertThatIllegalArgumentException().isThrownBy(() -> ZugferdProfile.isEInvoice("<broken".getBytes(UTF_8)));
    }

    private static byte[] ciiWithGuideline(String guidelineId) {
        return """
                <rsm:CrossIndustryInvoice xmlns:rsm="urn:un:unece:uncefact:data:standard:CrossIndustryInvoice:100"
                        xmlns:ram="urn:un:unece:uncefact:data:standard:ReusableAggregateBusinessInformationEntity:100">
                    <rsm:ExchangedDocumentContext>
                        <ram:GuidelineSpecifiedDocumentContextParameter>
                            <ram:ID>%s</ram:ID>
                        </ram:GuidelineSpecifiedDocumentContextParameter>
                    </rsm:ExchangedDocumentContext>
                </rsm:CrossIndustryInvoice>
                """.formatted(guidelineId).getBytes(UTF_8);
    }
}
