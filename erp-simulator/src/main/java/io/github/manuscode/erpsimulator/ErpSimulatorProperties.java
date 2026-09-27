package io.github.manuscode.erpsimulator;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param latency     added to every request, to simulate a slow ERP
 * @param failureRate share of requests answered with {@code 503}, from {@code 0.0} (never) to {@code 1.0} (always)
 */
@ConfigurationProperties("erp-simulator")
record ErpSimulatorProperties(Duration latency, double failureRate) {

    ErpSimulatorProperties {
        if (latency.isNegative()) {
            throw new IllegalArgumentException("Latency must not be negative: " + latency);
        }
        if (failureRate < 0.0 || failureRate > 1.0) {
            throw new IllegalArgumentException("Failure rate must be between 0.0 and 1.0: " + failureRate);
        }
    }
}
