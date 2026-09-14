package com.atlas.payments.validation;

public record RejectionReason(RuleId ruleId, String field, String message) {}
