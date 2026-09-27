package io.github.manuscode.erpsimulator;

import java.util.random.RandomGenerator;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

@Component
@EnableConfigurationProperties(ErpSimulatorProperties.class)
class SimulatedFailures {

    private final ErpSimulatorProperties properties;
    private final RandomGenerator random = RandomGenerator.getDefault();

    SimulatedFailures(ErpSimulatorProperties properties) {
        this.properties = properties;
    }

    /**
     * @throws ResponseStatusException with {@code 503} for the configured share of calls
     */
    void apply() throws InterruptedException {
        Thread.sleep(properties.latency());
        if (random.nextDouble() < properties.failureRate()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Simulated ERP failure");
        }
    }
}
