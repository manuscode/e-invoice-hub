package io.github.manuscode.invoicehub.intake;

import io.github.manuscode.invoicehub.invoice.Channel;
import io.github.manuscode.invoicehub.invoice.Invoice;
import io.github.manuscode.invoicehub.invoice.ReceivedInvoice;
import java.io.IOException;
import java.net.URI;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.http.InvalidMediaTypeException;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

@RestController
class InvoiceUploadController {

    private static final Set<MediaType> INVOICE_TYPES =
            Set.of(MediaType.APPLICATION_XML, MediaType.TEXT_XML, MediaType.APPLICATION_PDF);

    private final IntakeService intakeService;

    InvoiceUploadController(IntakeService intakeService) {
        this.intakeService = intakeService;
    }

    @PostMapping(path = "/api/invoices", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    ResponseEntity<Invoice> upload(@RequestParam("file") MultipartFile file) throws IOException {
        if (file.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "File is empty");
        }
        if (!isInvoiceType(file.getContentType())) {
            throw new ResponseStatusException(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "Only XML and PDF files are accepted");
        }
        ReceivedInvoice received =
                intakeService.receive(new RawDocument(file.getOriginalFilename(), file.getBytes(), Channel.REST));
        if (received.alreadyKnown()) {
            return ResponseEntity.ok(received.invoice());
        }
        Invoice invoice = received.invoice();
        return ResponseEntity.created(URI.create("/api/invoices/" + invoice.id())).body(invoice);
    }

    // Parameters like charset don't matter, the format is detected from the content anyway.
    private static boolean isInvoiceType(String contentType) {
        if (contentType == null) {
            return false;
        }
        try {
            MediaType mediaType = MediaType.parseMediaType(contentType);
            return INVOICE_TYPES.stream().anyMatch(mediaType::equalsTypeAndSubtype);
        } catch (InvalidMediaTypeException e) {
            return false;
        }
    }
}
