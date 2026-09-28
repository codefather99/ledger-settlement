package com.ledger.settlement;

import static org.assertj.core.api.Assertions.assertThat;

import com.ledger.settlement.web.PaymentResponse;
import com.ledger.settlement.web.RecordPaymentRequest;
import com.ledger.settlement.web.SettlementResponse;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * End-to-end proof against a real PostgreSQL instance, not an in-memory substitute: the
 * whole point of this lab is that Ledger's payments used to live only in memory and a
 * restart on 4 March 2026 lost an evening of MR-4471's records.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@Testcontainers
class PaymentControllerIT {

    /**
     * @ServiceConnection reads the running container's JDBC URL, username and password
     * and wires them into spring.datasource.* automatically - no manual property
     * registration. See research.md for the exact documentation sentence this relies on.
     */
    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17-alpine");

    @Autowired
    private TestRestTemplate restTemplate;

    @Test
    @DisplayName("POST of 128450 minor units GBP for MR-4471 settles at 124469 after the fee")
    void postingAPaymentProducesTheExpectedSettlement() {
        RecordPaymentRequest request = new RecordPaymentRequest("MR-4471", 128_450L, "GBP");

        ResponseEntity<PaymentResponse> postResponse =
                restTemplate.postForEntity("/payments", request, PaymentResponse.class);

        assertThat(postResponse.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(postResponse.getHeaders().getLocation()).isNotNull();
        assertThat(postResponse.getBody()).isNotNull();
        assertThat(postResponse.getBody().merchantId()).isEqualTo("MR-4471");
        assertThat(postResponse.getBody().amountMinor()).isEqualTo(128_450L);

        ResponseEntity<SettlementResponse> settlementResponse = restTemplate.getForEntity(
                "/payments/settlement?merchantId=MR-4471", SettlementResponse.class);

        assertThat(settlementResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(settlementResponse.getBody()).isNotNull();
        assertThat(settlementResponse.getBody().amountOwedMinor()).isEqualTo(124_469L);
    }

    @Test
    @DisplayName("A negative amount is rejected with a 400 problem detail naming the field")
    void negativeAmountIsRejected() {
        RecordPaymentRequest request = new RecordPaymentRequest("MR-4471", -5_000L, "GBP");

        ResponseEntity<ProblemDetail> response =
                restTemplate.postForEntity("/payments", request, ProblemDetail.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getHeaders().getContentType()).hasToString("application/problem+json");
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getDetail()).contains("amountMinor");
    }

    @Test
    @DisplayName("A blank merchantId is rejected with a 400 problem detail naming the field")
    void blankMerchantIdIsRejected() {
        RecordPaymentRequest request = new RecordPaymentRequest(" ", 5_000L, "GBP");

        ResponseEntity<ProblemDetail> response =
                restTemplate.postForEntity("/payments", request, ProblemDetail.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getHeaders().getContentType()).hasToString("application/problem+json");
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getDetail()).contains("merchantId");
    }

    @Test
    @DisplayName("An unknown merchant's settlement request returns a 404 problem detail naming the id")
    void unknownMerchantSettlementIsNotFound() {
        ResponseEntity<ProblemDetail> response = restTemplate.getForEntity(
                "/payments/settlement?merchantId=MR-0000", ProblemDetail.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getHeaders().getContentType()).hasToString("application/problem+json");
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getDetail()).contains("MR-0000");
    }

    @Test
    @DisplayName("GET /payments/{id} round-trips the payment just recorded, as the DTO not the entity")
    void getPaymentByIdRoundTrips() {
        RecordPaymentRequest request = new RecordPaymentRequest("MR-4471", 60_150L, "GBP");
        ResponseEntity<PaymentResponse> created =
                restTemplate.postForEntity("/payments", request, PaymentResponse.class);
        String id = created.getBody().id();

        ResponseEntity<PaymentResponse> fetched =
                restTemplate.getForEntity("/payments/" + id, PaymentResponse.class);

        assertThat(fetched.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(fetched.getBody()).isNotNull();
        assertThat(fetched.getBody().id()).isEqualTo(id);
        assertThat(fetched.getBody().amountMinor()).isEqualTo(60_150L);
    }

    @Test
    @DisplayName("GET /payments/{id} for an unknown id returns a 404 problem detail naming the id")
    void getUnknownPaymentByIdIsNotFound() {
        ResponseEntity<ProblemDetail> response =
                restTemplate.getForEntity("/payments/PAY-does-not-exist", ProblemDetail.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getHeaders().getContentType()).hasToString("application/problem+json");
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getDetail()).contains("PAY-does-not-exist");
    }
}