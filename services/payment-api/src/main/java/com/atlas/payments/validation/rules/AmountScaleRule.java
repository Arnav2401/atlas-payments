package com.atlas.payments.validation.rules;

import com.atlas.payments.api.dto.PaymentInstructionRequest;
import com.atlas.payments.validation.RejectionReason;
import com.atlas.payments.validation.RuleId;
import com.atlas.payments.validation.ValidationRule;

import java.util.Optional;

/**
 * R02_AMOUNT_SCALE_MATCHES_CURRENCY — decimal places must match the currency's minor unit.
 *
 * <p>Depends on R03 having passed, which is why this is SEMANTIC. java.util.Currency#getDefaultFractionDigits gives the minor unit (JPY 0, most 2, and note the three-decimal currencies such as BHD and KWD). BigDecimal#scale is the value to check — and be careful, 10.00 and 10 are equal but have different scales, so decide which one you are actually asserting about.
 */
public final class AmountScaleRule implements ValidationRule {

    @Override
    public RuleId id() {
        return RuleId.R02_AMOUNT_SCALE_MATCHES_CURRENCY;
    }

    @Override
    public Phase phase() {
        return Phase.SEMANTIC;
    }

    @Override
    public Optional<RejectionReason> check(PaymentInstructionRequest request) {
        throw new UnsupportedOperationException("TODO(M1): implement R02_AMOUNT_SCALE_MATCHES_CURRENCY");
    }
}
