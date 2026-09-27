package io.github.manuscode.invoicehub.validation;

import org.springframework.stereotype.Service;

@Service
public class ValidationService {

    private final KosItValidator kosItValidator;

    ValidationService(KosItValidator kosItValidator) {
        this.kosItValidator = kosItValidator;
    }

    public ValidationResult validate(byte[] content) {
        return FormatDetector.detect(content)
                .<ValidationResult>map(format -> kosItValidator.validate(content, format))
                .orElseGet(ValidationResult.UnsupportedFormat::new);
    }
}
