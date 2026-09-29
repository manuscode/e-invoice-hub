package io.github.manuscode.invoicehub;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.nio.file.Path;
import java.time.Duration;
import org.springframework.http.MediaType;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.MountableFile;

/**
 * Keycloak like in docker-compose.yml, with the same demo realm.
 */
public class KeycloakContainer extends GenericContainer<KeycloakContainer> {

    public static final Client UPLOADER = new Client("demo-uploader", "demo-uploader-secret");
    public static final Client READER = new Client("demo-reader", "demo-reader-secret");

    private static final Path REALM = Path.of("../docker/keycloak/invoice-hub-realm.json");
    private static final int HTTP_PORT = 8080;

    public KeycloakContainer() {
        super("quay.io/keycloak/keycloak:26.7.4");
        withCommand("start-dev", "--import-realm");
        withCopyFileToContainer(MountableFile.forHostPath(REALM), "/opt/keycloak/data/import/invoice-hub-realm.json");
        withExposedPorts(HTTP_PORT);
        // Keycloak needs about a minute to start, more if the containers of other test contexts are still running.
        waitingFor(Wait.forHttp("/realms/invoice-hub/.well-known/openid-configuration")
                .withStartupTimeout(Duration.ofMinutes(5)));
    }

    public String issuerUri() {
        return "http://%s:%d/realms/invoice-hub".formatted(getHost(), getMappedPort(HTTP_PORT));
    }

    public String accessToken(Client client) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "client_credentials");
        TokenResponse response = RestClient.create().post()
                .uri(issuerUri() + "/protocol/openid-connect/token")
                .headers(headers -> headers.setBasicAuth(client.id(), client.secret()))
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(form)
                .retrieve()
                .body(TokenResponse.class);
        return response.accessToken();
    }

    public record Client(String id, String secret) {
    }

    private record TokenResponse(@JsonProperty("access_token") String accessToken) {
    }
}
