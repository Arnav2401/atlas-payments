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

@Configuration
public class ValidationConfig {
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
