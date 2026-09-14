package com.atlas.payments.validation;

import com.atlas.payments.domain.PaymentInstruction;

import java.util.List;

public sealed interface ValidationOutcome {
    record Accepted(PaymentInstruction instruction) implements ValidationOutcome {}

    record Rejected(List<RejectionReason> reasons) implements ValidationOutcome {
        public Rejected {
            reasons = List.copyOf(reasons);
        }
    }
}
