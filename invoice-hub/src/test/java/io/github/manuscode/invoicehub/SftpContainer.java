package io.github.manuscode.invoicehub;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.MountableFile;

/**
 * SFTP server like in docker-compose.yml, with the same keys.
 */
public class SftpContainer extends GenericContainer<SftpContainer> {

    public static final String USERNAME = "invoices";

    private static final Path KEYS = Path.of("../docker/sftp");
    private static final String HOST_KEY = "ssh_host_rsa_key";
    private static final int SSH_PORT = 22;

    public SftpContainer() {
        super("atmoz/sftp:alpine@sha256:a81ea210713555be76075b4b2788a4addfaa54d137cd881f3a99ac539f0be2c5");
        withCommand(USERNAME + "::1001:100:inbox,processed,failed");
        withCopyFileToContainer(MountableFile.forHostPath(KEYS.resolve("invoice-hub_ecdsa.pub")),
                "/home/" + USERNAME + "/.ssh/keys/invoice-hub_ecdsa.pub");
        withCopyFileToContainer(MountableFile.forHostPath(KEYS.resolve(HOST_KEY)), "/etc/ssh/" + HOST_KEY);
        withExposedPorts(SSH_PORT);
        waitingFor(Wait.forLogMessage(".*Server listening on.*", 1));
    }

    public int sshPort() {
        return getMappedPort(SSH_PORT);
    }

    public Path privateKey() {
        return KEYS.resolve("invoice-hub_ecdsa").toAbsolutePath();
    }

    // docker/sftp/known_hosts only fits the port from docker-compose.yml, not the mapped port.
    public Path writeKnownHosts() {
        try {
            String publicKey = Files.readString(KEYS.resolve(HOST_KEY + ".pub")).strip();
            String entry = "[%s]:%d %s".formatted(getHost(), sshPort(), publicKey);
            return Files.writeString(Files.createTempFile("known_hosts", null), entry);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
