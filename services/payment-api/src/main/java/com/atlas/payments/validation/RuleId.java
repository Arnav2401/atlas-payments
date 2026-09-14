package com.atlas.payments.validation;

public enum RuleId {
    R01_AMOUNT_POSITIVE,

    R02_AMOUNT_SCALE_MATCHES_CURRENCY,

    R03_CURRENCY_SUPPORTED,

    R04_END_TO_END_ID,

    R05_AGENT_BIC_FORMAT,

    R06_ACCOUNTS_PRESENT_AND_DISTINCT,

    R07_CHARGE_BEARER_SUPPORTED,

    R08_SETTLEMENT_DATE_WINDOW,

    R09_DEBTOR_COUNTRY,

    R10_FIELD_LENGTH_BOUNDS;

    public String code() {
        return "ATLAS-V%03d".formatted(ordinal() + 1);
    }
}
