package com.atlas.payments.fraud;

/**
 * The M3 seam. One implementation calls the real fraud service; the other is
 * the conservative fallback — see {@link RestFraudClient} for which one runs
 * when.
 */
public interface FraudClient {

    FraudAssessment assess(FraudAssessmentRequest request);
}
