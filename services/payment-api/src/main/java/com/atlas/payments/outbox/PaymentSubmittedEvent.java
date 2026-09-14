package com.atlas.payments.outbox;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.math.BigDecimal;

/**
 * The {@code payments.submitted} event body — snake_case, matching the fraud
 * service's own field naming convention (see
 * {@code com.atlas.payments.fraud.ScoreApiDto}), because the consumer on the
 * other end of this topic is the same Python service and there is no reason
 * for it to parse two different naming conventions depending on whether a
 * request arrived over HTTP or over Kafka.
 *
 * <p>Field-for-field the same information as {@code FraudAssessmentRequest}
 * plus {@code paymentId}, which the HTTP path does not need (the HTTP
 * response correlates by the synchronous call itself) but the async path
 * does — {@code payments.decisioned} must be able to say which payment a
 * verdict belongs to, published potentially seconds or minutes after the
 * request that produced this event returned.
 */
public record PaymentSubmittedEvent(
        @JsonProperty("payment_id") String paymentId,
        @JsonProperty("end_to_end_id") String endToEndId,
        @JsonProperty("amount") BigDecimal amount,
        @JsonProperty("is_cash_out") boolean isCashOut,
        @JsonProperty("debtor_account") String debtorAccount,
        @JsonProperty("creditor_account") String creditorAccount,
        @JsonProperty("debtor_balance_before") BigDecimal debtorBalanceBefore,
        @JsonProperty("creditor_balance_before") BigDecimal creditorBalanceBefore,
        @JsonProperty("hour_of_day") int hourOfDay
) {

    public static final String TOPIC = "payments.submitted";
}
