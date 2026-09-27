package io.github.manuscode.invoicehub;

import org.testcontainers.containers.GenericContainer;

public class GreenMailContainer extends GenericContainer<GreenMailContainer> {

    public static final String USERNAME = "invoices";
    public static final String PASSWORD = "invoices";
    public static final String ADDRESS = "invoices@invoice-hub.local";

    private static final int SMTP_PORT = 3025;
    private static final int IMAP_PORT = 3143;

    public GreenMailContainer() {
        super("greenmail/standalone:2.1.13");
        withEnv("GREENMAIL_OPTS", String.join(" ",
                "-Dgreenmail.setup.test.smtp",
                "-Dgreenmail.setup.test.imap",
                "-Dgreenmail.hostname=0.0.0.0",
                "-Dgreenmail.users=" + USERNAME + ":" + PASSWORD + "@invoice-hub.local"));
        withExposedPorts(SMTP_PORT, IMAP_PORT);
    }

    public int smtpPort() {
        return getMappedPort(SMTP_PORT);
    }

    public int imapPort() {
        return getMappedPort(IMAP_PORT);
    }
}
