package io.github.manuscode.invoicehub.delivery;

import java.util.UUID;
import org.springframework.http.HttpStatusCode;

class ErpRejectedException extends RuntimeException {

    ErpRejectedException(UUID invoiceId, HttpStatusCode status, String responseBody) {
        super("ERP rejected invoice " + invoiceId + " with " + status + ": " + responseBody);
    }
}
