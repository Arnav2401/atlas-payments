package com.atlas.payments.outbox;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.math.BigDecimal;

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
