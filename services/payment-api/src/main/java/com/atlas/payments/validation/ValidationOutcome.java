package com.atlas.payments.validation;

import com.atlas.payments.domain.PaymentInstruction;

import java.util.List;

/**
 * The result of running the rule set.
 *
 * <p>Sealed, so the controller can {@code switch} over it exhaustively and the
 * compiler catches an unhandled case. Rejection is an expected outcome of a
 * payments API, not an exceptional one, which is why this is a return type and
 * not a thrown {@code ValidationException}.
 *
 * <p>The throwing alternative is more common in Spring codebases and is not
 * wrong — it keeps the happy path uncluttered and centralises the response
 * shape in {@code @ControllerAdvice}. Be ready to say why you chose otherwise.
 *
 * <p>{@link Accepted} carries a {@link PaymentInstruction}, a type that cannot
 * exist unless validation passed. That is the real payoff: it is structurally
 * impossible to hand unvalidated data to the persistence layer.
 */
public sealed interface ValidationOutcome {

    record Accepted(PaymentInstruction instruction) implements ValidationOutcome {}

    record Rejected(List<RejectionReason> reasons) implements ValidationOutcome {
        public Rejected {
            reasons = List.copyOf(reasons);
        }
    }
}
