package io.github.manuscode.invoicehub.intake;

import io.github.manuscode.invoicehub.invoice.Channel;

public record RawDocument(String filename, byte[] content, Channel channel) {
}
