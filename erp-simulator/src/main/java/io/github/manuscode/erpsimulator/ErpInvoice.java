package io.github.manuscode.erpsimulator;

import java.util.UUID;
import tools.jackson.databind.JsonNode;

/**
 * The simulator doesn't interpret the invoice, it only keeps it for inspection.
 */
record ErpInvoice(UUID invoiceId, JsonNode invoice) {
}
