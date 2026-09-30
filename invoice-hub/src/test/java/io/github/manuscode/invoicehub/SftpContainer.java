package io.github.manuscode.invoicehub;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import org.apache.sshd.common.config.keys.PublicKeyEntry;
import org.apache.sshd.common.config.keys.writer.openssh.OpenSSHKeyPairResourceWriter;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.Transferable;

/**
 * SFTP server like in docker-compose.yml, with keys created for each container like docker/sftp/generate-keys.sh does.
 */
public class SftpContainer extends GenericContainer<SftpContainer> {

    public static final String USERNAME = "invoices";

    private static final int SSH_PORT = 22;
    private static final int OWNER_ONLY = 0600;

    private final KeyPair clientKey = generateKeyPair("EC", 256);
    // Only RSA, like in docker/sftp/generate-keys.sh.
    private final KeyPair hostKey = generateKeyPair("RSA", 3072);

    public SftpContainer() {
        super("atmoz/sftp:alpine@sha256:a81ea210713555be76075b4b2788a4addfaa54d137cd881f3a99ac539f0be2c5");
        withCommand(USERNAME + "::1001:100:inbox,processed,failed");
        withCopyToContainer(Transferable.of(PublicKeyEntry.toString(clientKey.getPublic())),
                "/home/" + USERNAME + "/.ssh/keys/invoice-hub_ecdsa.pub");
        withCopyToContainer(Transferable.of(encodePrivateKey(hostKey), OWNER_ONLY), "/etc/ssh/ssh_host_rsa_key");
        withExposedPorts(SSH_PORT);
        waitingFor(Wait.forLogMessage(".*Server listening on.*", 1));
    }

    public int sshPort() {
        return getMappedPort(SSH_PORT);
    }

    public Path writePrivateKey() {
        return writeTempFile("invoice-hub_ecdsa", encodePrivateKey(clientKey));
    }

    public Path writeKnownHosts() {
        String entry = "[%s]:%d %s".formatted(getHost(), sshPort(), PublicKeyEntry.toString(hostKey.getPublic()));
        return writeTempFile("known_hosts", entry.getBytes());
    }

    private static KeyPair generateKeyPair(String algorithm, int keySize) {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance(algorithm);
            generator.initialize(keySize);
            return generator.generateKeyPair();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    private static byte[] encodePrivateKey(KeyPair keyPair) {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            OpenSSHKeyPairResourceWriter.INSTANCE.writePrivateKey(keyPair, "test only", null, out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    private static Path writeTempFile(String prefix, byte[] content) {
        try {
            return Files.write(Files.createTempFile(prefix, null), content);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
