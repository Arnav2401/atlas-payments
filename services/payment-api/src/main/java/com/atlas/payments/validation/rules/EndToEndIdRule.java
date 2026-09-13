package com.atlas.payments.validation.rules;

import com.atlas.payments.api.dto.PaymentInstructionRequest;
import com.atlas.payments.validation.RejectionReason;
import com.atlas.payments.validation.RuleId;
import com.atlas.payments.validation.ValidationRule;

import java.util.Optional;
import java.util.regex.Pattern;

/**
 * R04 — {@code endToEndId} present, non-blank, bounded, and safe to log.
 *
 * <h2>Decisions made here</h2>
 *
 * <p><b>Max length 35.</b> Borrowed from ISO 20022's
 * {@code EndToEndIdentification35Text}. Free domain vocabulary, and a number
 * with a reason behind it rather than a round one someone picked.
 *
 * <p><b>Restricted character set.</b> ISO 20022's basic Latin set:
 * letters, digits, and {@code / - ? : ( ) . , ' +} and space. This is the one
 * rule here with a security dimension. {@code endToEndId} is the correlation key
 * for structured logging in M5, so it ends up in every log line for the payment.
 * Permitting newlines or control characters in a value that is echoed into logs
 * is log injection — an attacker who controls this field could forge log entries.
 * Bounding the character set at the boundary removes the problem rather than
 * relying on every future log call to escape it.
 *
 * <p><b>Blank is rejected.</b> {@code isBlank()}, not {@code isEmpty()} — a
 * string of spaces is present but carries no identity.
 */
public final class EndToEndIdRule implements ValidationRule {

    private static final String FIELD = "endToEndId";

    /** ISO 20022 EndToEndIdentification35Text. */
    public static final int MAX_LENGTH = 35;

    /** ISO 20022 basic Latin character set. Notably excludes control characters. */
    private static final Pattern PERMITTED = Pattern.compile("^[A-Za-z0-9/\\-?:().,'+ ]+$");

    @Override
    public RuleId id() {
        return RuleId.R04_END_TO_END_ID;
    }

    @Override
    public Optional<RejectionReason> check(PaymentInstructionRequest request) {
        String id = request.endToEndId();

        if (id == null || id.isBlank()) {
            return Optional.of(new RejectionReason(id(), FIELD, "endToEndId is required"));
        }
        if (id.length() > MAX_LENGTH) {
            return Optional.of(new RejectionReason(id(), FIELD,
                    "endToEndId exceeds " + MAX_LENGTH + " characters"));
        }
        if (!PERMITTED.matcher(id).matches()) {
            return Optional.of(new RejectionReason(id(), FIELD,
                    "endToEndId contains characters outside the permitted set"));
        }
        return Optional.empty();
    }
}
