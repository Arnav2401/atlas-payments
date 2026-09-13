package com.atlas.payments.validation;

/**
 * The ten validation rules, by stable internal identifier.
 *
 * <p>These names are internal. The wire-facing reason code is a separate,
 * published contract — see {@link #code()} — so that renaming a rule here is
 * not a breaking API change.
 *
 * <p>Rule set version 1. When this set changes, the versioning question from the
 * brief ("how would you version these rules?") becomes real: a rule set is a
 * {@code List<ValidationRule>}, so a v2 is a different list, not an edit to this
 * enum. Decide whether you version the set, the individual rule, or neither, and
 * record it in DESIGN_NOTES.md.
 */
public enum RuleId {

    /** Instructed amount must be strictly positive. */
    R01_AMOUNT_POSITIVE,

    /** Decimal places must match the currency's minor unit (JPY 0, most 2). */
    R02_AMOUNT_SCALE_MATCHES_CURRENCY,

    /** Instructed currency must be a live ISO 4217 code. */
    R03_CURRENCY_SUPPORTED,

    /** endToEndId present, non-empty, within max length. */
    R04_END_TO_END_ID,

    /** Debtor and creditor agent BICs must match ISO 9362 (8 or 11 alphanumeric). */
    R05_AGENT_BIC_FORMAT,

    /** Debtor and creditor accounts present and distinct. */
    R06_ACCOUNTS_PRESENT_AND_DISTINCT,

    /** chargeBearer must be one of the supported set. */
    R07_CHARGE_BEARER_SUPPORTED,

    /** Settlement date not in the past, not more than N days forward. */
    R08_SETTLEMENT_DATE_WINDOW,

    /** Debtor country must be a valid ISO 3166-1 alpha-2 code. */
    R09_DEBTOR_COUNTRY,

    /** Payload size and individual field lengths bounded. */
    R10_FIELD_LENGTH_BOUNDS;

    /**
     * The published reason code for this rule.
     *
     * <p>TODO(M1): you define this scheme — the brief is explicit that it is
     * yours, and the README must document all ten. Two shapes worth weighing:
     * opaque and stable ({@code ATLAS-V007}), which survives any rename or
     * re-scoping of the rule; or mnemonic ({@code AMOUNT_NOT_POSITIVE}), which
     * is self-describing in a client log but tempts you to change it when the
     * rule's meaning drifts. Pick one, then answer: does the code stay fixed if
     * the rule's logic changes?
     */
    public String code() {
        throw new UnsupportedOperationException(
                "TODO(M1): define the reason-code scheme for " + name());
    }
}
