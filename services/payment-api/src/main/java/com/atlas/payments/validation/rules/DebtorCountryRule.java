package com.atlas.payments.validation.rules;

import com.atlas.payments.api.dto.PaymentInstructionRequest;
import com.atlas.payments.validation.RejectionReason;
import com.atlas.payments.validation.RuleId;
import com.atlas.payments.validation.ValidationRule;

import java.util.Locale;
import java.util.Optional;
import java.util.Set;

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
