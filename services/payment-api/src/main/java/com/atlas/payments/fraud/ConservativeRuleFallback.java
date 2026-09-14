package com.atlas.payments.fraud;

import java.math.BigDecimal;

/**
 * What runs when the fraud service is unreachable and the circuit is open.
 *
 * <h2>Conservative means high recall, not high precision</h2>
 *
 * <p>The two failure modes available when a fraud check cannot be performed
 * are not symmetric. Flagging a legitimate payment costs a manual review — an
 * inconvenience. Waving through a fraudulent one costs the money, and it
 * costs it while the actual fraud model is offline and unable to catch it any
 * other way. For a payments system specifically, that asymmetry is the whole
 * argument for "conservative" meaning "flag more, not less" here — the
 * opposite of, say, a spam filter or a content recommender, where the safe
 * default under uncertainty is to show nothing rather than something.
 *
 * <h2>This rule is not an arbitrary stand-in — it is the model's own answer, simplified</h2>
 *
 * <p>Training measured (services/fraud-service/training/train.py,
 * services/fraud-service/docs/sources.md): 97.8% of fraudulent transactions
 * in the training data drain the debtor's available balance to within 1%,
 * versus 0.15% of legitimate ones, and {@code orig_balance_ratio} is the
 * model's single largest SHAP contributor by a wide margin — roughly 3.5x the
 * next feature. So the fallback is not a guess made independently of the
 * model; it is that one finding, turned into a rule simple enough to run with
 * no ML dependency at all. A zero balance is flagged outright, since the
 * ratio itself is undefined there (see the same balance-was-zero handling in
 * the model's own feature set).
 *
 * <h2>What this rule deliberately does not attempt</h2>
 *
 * <p>No velocity, no counterparty history, no time-of-day pattern — those need
 * the state the fraud service's Redis holds, which is exactly what is
 * unreachable when this class runs. A fallback that tried to approximate the
 * full feature set without that state would be guessing at signals it cannot
 * actually compute; a fallback that uses the one signal it CAN compute
 * correctly, from data already in hand, is the honest scope for this class.
 */
public final class ConservativeRuleFallback {

    /** Drains ≥ this fraction of the available balance: flag. */
    static final BigDecimal DRAIN_RATIO_THRESHOLD = new BigDecimal("0.90");

    private ConservativeRuleFallback() {
    }

    public static FraudAssessment assess(FraudAssessmentRequest request) {
        BigDecimal balanceBefore = request.debtorBalanceBefore();

        if (balanceBefore.signum() <= 0) {
            // Cannot compute a ratio, and a zero-or-negative available balance
            // paying out at all is itself the anomaly the model's
            // orig_balance_was_zero feature exists to flag.
            return FraudAssessment.fromFallback(true);
        }

        BigDecimal drainRatio = request.amount().divide(balanceBefore, 4, java.math.RoundingMode.HALF_UP);
        boolean flagged = drainRatio.compareTo(DRAIN_RATIO_THRESHOLD) >= 0;
        return FraudAssessment.fromFallback(flagged);
    }
}
