package com.atlas.payments.fraud;

import java.math.BigDecimal;

public record FraudAssessmentRequest(
        String endToEndId,
        BigDecimal amount,
        boolean isCashOut,
        String debtorAccount,
        String creditorAccount,
        BigDecimal debtorBalanceBefore,
        BigDecimal creditorBalanceBefore,
        int hourOfDay
) {}
