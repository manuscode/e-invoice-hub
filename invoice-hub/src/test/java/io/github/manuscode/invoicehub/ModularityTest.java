package io.github.manuscode.invoicehub;

import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;

class ModularityTest {

    private final ApplicationModules modules = ApplicationModules.of(InvoiceHubApplication.class);

    @Test
    void modulesDoNotViolateTheirBoundaries() {
        modules.verify();
    }
}
