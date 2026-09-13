package com.atlas.payments.validation.rules;

import com.atlas.payments.api.dto.PaymentInstructionRequest;
import com.atlas.payments.validation.RejectionReason;
import com.atlas.payments.validation.RuleId;
import com.atlas.payments.validation.ValidationRule;

import java.util.Optional;

/**
 * R05_AGENT_BIC_FORMAT — debtor and creditor agent BICs must match ISO 9362.
 *
 * <p>8 or 11 alphanumeric, never 9 or 10. Note this rule covers two fields, so one failure needs to say which agent was at fault — that is what RejectionReason#field is for. Alternative worth considering: split into two rules so the mapping to reason codes stays 1:1.
 */
public final class AgentBicFormatRule implements ValidationRule {

    @Override
    public RuleId id() {
        return RuleId.R05_AGENT_BIC_FORMAT;
    }

    @Override
    public Optional<RejectionReason> check(PaymentInstructionRequest request) {
        throw new UnsupportedOperationException("TODO(M1): implement R05_AGENT_BIC_FORMAT");
    }
}
