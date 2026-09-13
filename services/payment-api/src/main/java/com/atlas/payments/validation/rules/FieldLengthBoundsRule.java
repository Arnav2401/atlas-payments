package com.atlas.payments.validation.rules;

import com.atlas.payments.api.dto.PaymentInstructionRequest;
import com.atlas.payments.validation.RejectionReason;
import com.atlas.payments.validation.RuleId;
import com.atlas.payments.validation.ValidationRule;

import java.util.Optional;

/**
 * R10_FIELD_LENGTH_BOUNDS — payload size and individual field lengths bounded.
 *
 * <p>This is the defensive rule, and it has an ordering problem worth thinking about: by the time it runs, Jackson has already materialised the payload, so it cannot protect you from a huge body. Total request size belongs at the container (server.max-http-request-header-size and friends) or a filter. Be clear about which half of the job this rule actually does.
 */
public final class FieldLengthBoundsRule implements ValidationRule {

    @Override
    public RuleId id() {
        return RuleId.R10_FIELD_LENGTH_BOUNDS;
    }

    @Override
    public Optional<RejectionReason> check(PaymentInstructionRequest request) {
        throw new UnsupportedOperationException("TODO(M1): implement R10_FIELD_LENGTH_BOUNDS");
    }
}
