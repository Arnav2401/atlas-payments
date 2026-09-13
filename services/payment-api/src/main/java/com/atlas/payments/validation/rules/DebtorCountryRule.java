package com.atlas.payments.validation.rules;

import com.atlas.payments.api.dto.PaymentInstructionRequest;
import com.atlas.payments.validation.RejectionReason;
import com.atlas.payments.validation.RuleId;
import com.atlas.payments.validation.ValidationRule;

import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * R09 — debtor country must be a valid ISO 3166-1 alpha-2 code.
 *
 * <h2>Decisions made here</h2>
 *
 * <p><b>{@code Locale.getISOCountries()} is acceptable here, where the
 * equivalent JDK lookup was not acceptable for R03.</b> Worth being able to
 * explain the asymmetry, because it looks inconsistent. R03 needed "live",
 * and the JDK's currency data is a superset that includes withdrawn currencies,
 * so it answered the wrong question. Here the rule asks "is this a valid
 * alpha-2 code", which is exactly what this list answers. Same caveat applies —
 * it is bundled data tracking the JDK version, not a live feed — but the
 * consequence is much smaller: country codes are far more stable than
 * currencies, and this rule feeds sanctions-adjacent screening rather than
 * settlement.
 *
 * <p><b>Uppercase only</b> — consistent with R03 and R07.
 *
 * <p>The interesting case is {@code UK}: it is the common abbreviation and it is
 * not an ISO 3166-1 alpha-2 code. {@code GB} is.
 */
public final class DebtorCountryRule implements ValidationRule {

    private static final String FIELD = "debtorCountry";

    private static final Set<String> ISO_COUNTRIES = Set.of(Locale.getISOCountries());

    @Override
    public RuleId id() {
        return RuleId.R09_DEBTOR_COUNTRY;
    }

    @Override
    public Optional<RejectionReason> check(PaymentInstructionRequest request) {
        String country = request.debtorCountry();

        if (country == null || country.isBlank()) {
            return Optional.of(new RejectionReason(id(), FIELD, "debtorCountry is required"));
        }
        if (!ISO_COUNTRIES.contains(country)) {
            return Optional.of(new RejectionReason(id(), FIELD,
                    "debtorCountry is not a valid ISO 3166-1 alpha-2 code"));
        }
        return Optional.empty();
    }
}
