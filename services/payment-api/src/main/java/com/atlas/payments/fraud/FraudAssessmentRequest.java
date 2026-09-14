package com.atlas.payments.fraud;

import java.math.BigDecimal;

/**
 * What the fraud service needs to score a payment — field names matching its
 * own wire contract (services/fraud-service/src/fraud_service/api/schemas.py),
 * not this API's ISO-20022-flavoured names, because this is a call to a
 * different service's boundary, not a re-export of this one's.
 *
 * <p>{@code isCashOut} is always {@code false} for every payment this system
 * sends. <b>Known scoping limitation, stated plainly:</b> the model was
 * trained on PaySim's TRANSFER and CASH_OUT population, and atlas-payments has
 * no cash-withdrawal concept — every payment here is the TRANSFER half of
 * that population. The model's CASH_OUT-specific learned behaviour is
 * therefore never exercised by this system's real traffic. Introducing a real
 * cash-out product concept, or retraining on TRANSFER-only data, are the two
 * honest fixes; hard-coding {@code false} without saying so would not be.
 *
 * <p>Balances are minor-unit longs converted to decimal, matching the
 * training data's units (PaySim's balances are plain decimal amounts, not
 * integer cents) and matching what the fraud service's schema declares.
 */
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
