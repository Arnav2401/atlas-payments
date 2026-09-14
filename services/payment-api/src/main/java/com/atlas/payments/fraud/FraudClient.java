package com.atlas.payments.fraud;

public interface FraudClient {
    FraudAssessment assess(FraudAssessmentRequest request);
}
