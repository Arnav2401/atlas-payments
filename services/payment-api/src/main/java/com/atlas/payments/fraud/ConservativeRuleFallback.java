package com.atlas.payments.fraud;

import java.math.BigDecimal;

public final class ConservativeRuleFallback {
    static final BigDecimal DRAIN_RATIO_THRESHOLD = new BigDecimal("0.90");

    private ConservativeRuleFallback() {
    }

    public static FraudAssessment assess(FraudAssessmentRequest request) {
        BigDecimal balanceBefore = request.debtorBalanceBefore();

        if (balanceBefore.signum() <= 0) {
            return FraudAssessment.fromFallback(true);
        }

        BigDecimal drainRatio = request.amount().divide(balanceBefore, 4, java.math.RoundingMode.HALF_UP);
        boolean flagged = drainRatio.compareTo(DRAIN_RATIO_THRESHOLD) >= 0;
        return FraudAssessment.fromFallback(flagged);
    }
}
