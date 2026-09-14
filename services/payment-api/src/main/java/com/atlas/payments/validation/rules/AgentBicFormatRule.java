package com.atlas.payments.validation.rules;

import com.atlas.payments.api.dto.PaymentInstructionRequest;
import com.atlas.payments.validation.RejectionReason;
import com.atlas.payments.validation.RuleId;
import com.atlas.payments.validation.ValidationRule;

import java.util.Optional;
import java.util.regex.Pattern;

public final class AgentBicFormatRule implements ValidationRule {
    private static final Pattern BIC = Pattern.compile("^[A-Z]{4}[A-Z]{2}[A-Z0-9]{2}([A-Z0-9]{3})?$");

    @Override
    public RuleId id() {
        return RuleId.R05_AGENT_BIC_FORMAT;
    }

    @Override
    public Optional<RejectionReason> check(PaymentInstructionRequest request) {
        return checkOne("debtorAgent", request.debtorAgent())
                .or(() -> checkOne("creditorAgent", request.creditorAgent()));
    }

    private Optional<RejectionReason> checkOne(String field, String bic) {
        if (bic == null || bic.isBlank()) {
            return Optional.of(new RejectionReason(id(), field, field + " is required"));
        }
        if (!BIC.matcher(bic).matches()) {
            return Optional.of(new RejectionReason(id(), field,
                    field + " is not a valid ISO 9362 BIC"));
        }
        return Optional.empty();
    }
}
