package com.atlas.payments.config;

import com.atlas.payments.validation.PaymentValidator;
import com.atlas.payments.validation.ValidationRule;
import com.atlas.payments.validation.rules.AccountsRule;
import com.atlas.payments.validation.rules.AgentBicFormatRule;
import com.atlas.payments.validation.rules.AmountPositiveRule;
import com.atlas.payments.validation.rules.AmountScaleRule;
import com.atlas.payments.validation.rules.ChargeBearerRule;
import com.atlas.payments.validation.rules.CurrencySupportedRule;
import com.atlas.payments.validation.rules.DebtorCountryRule;
import com.atlas.payments.validation.rules.EndToEndIdRule;
import com.atlas.payments.validation.rules.FieldLengthBoundsRule;
import com.atlas.payments.validation.rules.SettlementDateWindowRule;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * The rule set, registered explicitly.
 *
 * <p>Deliberately not classpath scanning for {@code ValidationRule} beans. This
 * list is the authoritative definition of what "validated" means in this system:
 * it is ordered, it is reviewable in one diff, and a rule cannot join the set by
 * accident. Scanning would also put a Spring annotation on every rule class,
 * which is exactly what keeps the validation package framework-free today.
 *
 * <p>Rule set v1. A v2 is a different list — not an edit to this one.
 */
@Configuration
public class ValidationConfig {

    @Bean
    public PaymentValidator paymentValidator() {
        List<ValidationRule> rules = List.of(
                new AmountPositiveRule(),
                new AmountScaleRule(),
                new CurrencySupportedRule(),
                new EndToEndIdRule(),
                new AgentBicFormatRule(),
                new AccountsRule(),
                new ChargeBearerRule(),
                new SettlementDateWindowRule(),
                new DebtorCountryRule(),
                new FieldLengthBoundsRule());

        return new PaymentValidator(rules);
    }
}
