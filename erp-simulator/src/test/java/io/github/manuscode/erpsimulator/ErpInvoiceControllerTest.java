package io.github.manuscode.erpsimulator;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

class ErpInvoiceControllerTest {

    @Nested
    @SpringBootTest
    @AutoConfigureMockMvc
    class Available {

        @Autowired
        private MockMvcTester mockMvc;

        @Test
        void storesNewInvoice() {
            UUID invoiceId = UUID.randomUUID();

            MvcTestResult result = send(mockMvc, invoice(invoiceId, "R-1"));

            assertThat(result).hasStatus(201).hasHeader("Location", "/erp/invoices/" + invoiceId);
            assertThat(mockMvc.get().uri("/erp/invoices/{id}", invoiceId))
                    .hasStatusOk().bodyJson().extractingPath("$.invoice.invoiceNumber").isEqualTo("R-1");
        }

        @Test
        void keepsFirstInvoiceForSameInvoiceId() {
            UUID invoiceId = UUID.randomUUID();
            send(mockMvc, invoice(invoiceId, "R-1"));

            MvcTestResult again = send(mockMvc, invoice(invoiceId, "R-2"));

            assertThat(again).hasStatus(200);
            assertThat(mockMvc.get().uri("/erp/invoices/{id}", invoiceId))
                    .bodyJson().extractingPath("$.invoice.invoiceNumber").isEqualTo("R-1");
        }

        @Test
        void rejectsInvoiceWithoutInvoiceId() {
            assertThat(send(mockMvc, """
                    {"invoice": {"invoiceNumber": "R-1"}}
                    """)).hasStatus(400);
        }

        @Test
        void returnsNotFoundForUnknownInvoice() {
            assertThat(mockMvc.get().uri("/erp/invoices/{id}", UUID.randomUUID())).hasStatus(404);
        }
    }

    @Nested
    @SpringBootTest
    @AutoConfigureMockMvc
    @TestPropertySource(properties = "erp-simulator.failure-rate=1.0")
    class Failing {

        @Autowired
        private MockMvcTester mockMvc;

        @Test
        void answersServiceUnavailableAndStoresNothing() {
            UUID invoiceId = UUID.randomUUID();

            assertThat(send(mockMvc, invoice(invoiceId, "R-1"))).hasStatus(503);
            assertThat(mockMvc.get().uri("/erp/invoices/{id}", invoiceId)).hasStatus(404);
        }
    }

    private static MvcTestResult send(MockMvcTester mockMvc, String body) {
        return mockMvc.post().uri("/erp/invoices").contentType(MediaType.APPLICATION_JSON).content(body).exchange();
    }

    private static String invoice(UUID invoiceId, String invoiceNumber) {
        return """
                {"invoiceId": "%s", "invoice": {"invoiceNumber": "%s"}}
                """.formatted(invoiceId, invoiceNumber);
    }
}
