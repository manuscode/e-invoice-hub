package io.github.manuscode.invoicehub.intake;

import io.github.manuscode.invoicehub.invoice.Channel;
import io.github.manuscode.invoicehub.invoice.Invoice;
import java.io.IOException;
import java.net.URI;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

@RestController
class InvoiceUploadController {

    private final IntakeService intakeService;

    InvoiceUploadController(IntakeService intakeService) {
        this.intakeService = intakeService;
    }

    @PostMapping(path = "/api/invoices", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    ResponseEntity<Invoice> upload(@RequestParam("file") MultipartFile file) throws IOException {
        if (file.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "File is empty");
        }
        Invoice invoice = intakeService.receive(new RawDocument(file.getOriginalFilename(), file.getBytes(), Channel.REST));
        return ResponseEntity.created(URI.create("/api/invoices/" + invoice.id())).body(invoice);
    }
}
