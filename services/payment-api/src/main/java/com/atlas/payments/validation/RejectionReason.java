package com.atlas.payments.validation;

/**
 * One rule failure, in domain terms.
 *
 * <p>Deliberately carries {@link RuleId} rather than the wire code: the mapping
 * to a published code happens at the API boundary, which keeps the validation
 * package free of any knowledge about how rejections are serialised.
 *
 * @param ruleId  the rule that failed
 * @param field   the request field at fault, for the caller to locate
 * @param message human-readable explanation; safe to show a caller
 */
public record RejectionReason(RuleId ruleId, String field, String message) {}
