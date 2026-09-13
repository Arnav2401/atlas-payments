package com.atlas.payments.api;

import com.atlas.payments.config.ValidationConfig;
import com.atlas.payments.persistence.PaymentStore;
import com.atlas.payments.persistence.StoredPayment;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The HTTP contract: status codes, response shapes, and what never reaches the store. */
@WebMvcTest(PaymentController.class)
@Import(ValidationConfig.class)
class PaymentControllerTest {

    /**
     * Computed rather than hard-coded. ValidationConfig supplies a real
     * Clock.systemUTC(), so a literal date here would silently start failing R08
     * once wall-clock time passed it. R08's own boundaries are asserted against a
     * fixed Clock in SettlementDateWindowRuleTest; this test is about HTTP.
     */
    private static final String SETTLEMENT_DATE =
            LocalDate.now(ZoneOffset.UTC).plusDays(7).toString();

    private static final String VALID_BODY = """
            {
              "endToEndId": "E2E-0000000001",
              "instructedAmount": 100.00,
              "instructedCurrency": "USD",
              "debtorAgent": "DEUTDEFF",
              "creditorAgent": "CHASUS33XXX",
              "debtorAccount": "DE89370400440532013000",
              "creditorAccount": "GB29NWBK60161331926819",
              "debtorCountry": "DE",
              "chargeBearer": "SHAR",
              "settlementDate": "%s"
            }
            """.formatted(SETTLEMENT_DATE);

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private PaymentStore paymentStore;

    @Test
    void accepts_a_valid_payment_and_records_it_under_the_idempotency_key() throws Exception {
        when(paymentStore.record(any(), eq("key-1")))
                .thenReturn(new StoredPayment("pay-123", Instant.now(), false));

        mockMvc.perform(post("/payments")
                        .header("Idempotency-Key", "key-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACCEPTED"))
                .andExpect(jsonPath("$.paymentId").value("pay-123"))
                .andExpect(jsonPath("$.rejections").doesNotExist());

        verify(paymentStore).record(any(), eq("key-1"));
    }

    /** DECISION 1: a rejection is a decision, so it rides on 200 with a status discriminator. */
    @Test
    void returns_200_with_rejected_status_and_the_published_reason_code() throws Exception {
        String negativeAmount = VALID_BODY.replace("100.00", "-100.00");

        mockMvc.perform(post("/payments")
                        .header("Idempotency-Key", "key-2")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(negativeAmount))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REJECTED"))
                .andExpect(jsonPath("$.paymentId").doesNotExist())
                .andExpect(jsonPath("$.rejections[0].code").value("ATLAS-V001"))
                .andExpect(jsonPath("$.rejections[0].field").value("instructedAmount"));
    }

    /** The invariant that makes the sealed ValidationOutcome worth having. */
    @Test
    void a_rejected_payment_never_reaches_the_store() throws Exception {
        mockMvc.perform(post("/payments")
                        .header("Idempotency-Key", "key-3")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY.replace("\"USD\"", "\"XYZ\"")))
                .andExpect(jsonPath("$.status").value("REJECTED"));

        verifyNoInteractions(paymentStore);
    }

    @Test
    void collects_every_structural_rejection_in_one_response() throws Exception {
        String multiplyInvalid = VALID_BODY
                .replace("100.00", "-1")
                .replace("\"DE\",", "\"ZZ\",")
                .replace("\"SHAR\"", "\"BOGUS\"");

        mockMvc.perform(post("/payments")
                        .header("Idempotency-Key", "key-4")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(multiplyInvalid))
                .andExpect(jsonPath("$.rejections.length()").value(3))
                .andExpect(jsonPath("$.rejections[0].code").value("ATLAS-V001"))
                .andExpect(jsonPath("$.rejections[1].code").value("ATLAS-V007"))
                .andExpect(jsonPath("$.rejections[2].code").value("ATLAS-V009"));
    }

    /** A malformed body produced no decision, so it is a 400 in a different shape. */
    @Test
    void malformed_json_is_a_400_with_an_error_envelope_not_a_rejection() throws Exception {
        mockMvc.perform(post("/payments")
                        .header("Idempotency-Key", "key-5")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{ not json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("ATLAS-E001"))
                .andExpect(jsonPath("$.status").doesNotExist());
    }

    /** An unparseable date is a shape failure, not a rule failure — R08 never runs. */
    @Test
    void an_unparseable_date_is_a_shape_failure() throws Exception {
        mockMvc.perform(post("/payments")
                        .header("Idempotency-Key", "key-6")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY.replace(SETTLEMENT_DATE, "not-a-date")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("ATLAS-E001"));
    }

    @Test
    void a_misspelled_field_is_rejected_rather_than_silently_dropped() throws Exception {
        mockMvc.perform(post("/payments")
                        .header("Idempotency-Key", "key-7")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY.replace("instructedAmount", "instructedAmt")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("ATLAS-E001"));
    }

    @Test
    void a_missing_idempotency_key_is_a_400() throws Exception {
        mockMvc.perform(post("/payments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("ATLAS-E002"));
    }

    /** Jackson's message names internal classes and echoes bad values. It must not leak. */
    @Test
    void the_error_response_does_not_leak_jackson_internals() throws Exception {
        String body = mockMvc.perform(post("/payments")
                        .header("Idempotency-Key", "key-8")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY.replace(SETTLEMENT_DATE, "not-a-date")))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andReturn().getResponse().getContentAsString();

        org.junit.jupiter.api.Assertions.assertFalse(body.contains("com.atlas"), body);
        org.junit.jupiter.api.Assertions.assertFalse(body.contains("not-a-date"), body);
        org.junit.jupiter.api.Assertions.assertFalse(body.toLowerCase().contains("jackson"), body);
    }
}
