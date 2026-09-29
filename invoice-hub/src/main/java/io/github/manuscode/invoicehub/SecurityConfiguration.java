package io.github.manuscode.invoicehub;

import jakarta.servlet.DispatcherType;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

/**
 * The API accepts only JWTs from Keycloak. Roles come from the realm roles of the token, see application.yml.
 */
@Configuration(proxyBeanMethods = false)
class SecurityConfiguration {

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http) {
        return http
                .authorizeHttpRequests(requests -> requests
                        .requestMatchers(HttpMethod.POST, "/api/invoices").hasRole("invoice-uploader")
                        .requestMatchers(HttpMethod.GET, "/api/invoices/**").hasRole("invoice-reader")
                        .requestMatchers(HttpMethod.GET, "/actuator/health/**").permitAll()
                        // Otherwise errors like 413 are hidden behind a 401, because the error page has no token.
                        .dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()
                        .anyRequest().denyAll())
                .oauth2ResourceServer(resourceServer -> resourceServer.jwt(Customizer.withDefaults()))
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                // The token comes in a header, not in a cookie, so a browser can't send it for another site.
                .csrf(AbstractHttpConfigurer::disable)
                .build();
    }
}
