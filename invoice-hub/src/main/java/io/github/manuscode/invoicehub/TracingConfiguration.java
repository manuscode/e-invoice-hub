package io.github.manuscode.invoicehub;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.support.ContextPropagatingTaskDecorator;

@Configuration(proxyBeanMethods = false)
class TracingConfiguration {

    /**
     * Spring Modulith sends events to Kafka asynchronously after the commit. Without the trace context of the calling
     * thread, the trace of an upload would end before Kafka. Spring Boot applies the decorator to its task executor.
     */
    @Bean
    ContextPropagatingTaskDecorator contextPropagatingTaskDecorator() {
        return new ContextPropagatingTaskDecorator();
    }
}
