package io.github.manuscode.invoicehub.validation;

import de.kosit.validationtool.api.Check;
import de.kosit.validationtool.api.Configuration;
import de.kosit.validationtool.api.InputFactory;
import de.kosit.validationtool.api.Result;
import de.kosit.validationtool.impl.DefaultCheck;
import de.kosit.validationtool.impl.HtmlExtractor;
import de.kosit.validationtool.impl.xml.ProcessorProvider;
import net.sf.saxon.s9api.Processor;
import org.springframework.stereotype.Component;

@Component
class KosItValidator {

    // Loading the configuration is expensive, the check is thread-safe and reused.
    private final Check check;
    private final HtmlExtractor htmlExtractor;

    KosItValidator(XRechnungConfiguration xRechnungConfiguration) {
        Processor processor = ProcessorProvider.getProcessor();
        Configuration configuration = Configuration.load(xRechnungConfiguration.scenarios().toUri()).build(processor);
        this.check = new DefaultCheck(configuration);
        this.htmlExtractor = new HtmlExtractor(processor);
    }

    ValidationResult.Checked validate(byte[] content, InvoiceFormat format) {
        Result result = check.checkInput(InputFactory.read(content, "invoice"));
        // KoSIT stops processing for XML that is not well-formed. That is a rejected invoice, not a technical failure.
        if (!result.isProcessingSuccessful() && result.isWellformed()) {
            throw new IllegalStateException("KoSIT validation could not be processed: " + result.getProcessingErrors());
        }
        String reportHtml = String.join("\n", htmlExtractor.extractAsString(result.getReport()));
        return new ValidationResult.Checked(format, content, result.isAcceptable(), reportHtml);
    }
}
