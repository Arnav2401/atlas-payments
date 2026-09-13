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

import java.time.Clock;
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

    /**
     * UTC, explicitly. R08 asks whether a settlement date is in the past, which is
     * a timezone-dependent question — inheriting the server's default zone would
     * make the answer depend on where the container happens to run.
     */
    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    public PaymentValidator paymentValidator(Clock clock) {
        List<ValidationRule> rules = List.of(
                new AmountPositiveRule(),
                new AmountScaleRule(),
                new CurrencySupportedRule(),
                new EndToEndIdRule(),
                new AgentBicFormatRule(),
                new AccountsRule(),
                new ChargeBearerRule(),
                new SettlementDateWindowRule(clock, SettlementDateWindowRule.DEFAULT_MAX_DAYS_FORWARD),
                new DebtorCountryRule(),
                new FieldLengthBoundsRule());

        return new PaymentValidator(rules);
    }
}
