package io.github.manuscode.invoicehub;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;

import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * Tokens for MockMvc without Keycloak. Real tokens are tested in {@link SecurityIntegrationTest}.
 */
public final class AccessTokens {

    private AccessTokens() {
    }

    public static RequestPostProcessor uploader() {
        return withRole("invoice-uploader");
    }

    public static RequestPostProcessor reader() {
        return withRole("invoice-reader");
    }

    private static RequestPostProcessor withRole(String role) {
        return jwt().authorities(new SimpleGrantedAuthority("ROLE_" + role));
    }
}
