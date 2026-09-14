package com.atlas.payments.rings;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * The fraud service's {@code GET /rings} wire schema — snake_case, matching
 * services/fraud-service/src/fraud_service/api/schemas.py's
 * {@code RingsResponse} exactly. Same reasoning as {@code ScoreApiDto} in
 * the {@code fraud} package: this is the HTTP contract with an external
 * service, kept separate from this API's own camelCase response to its own
 * callers ({@link RingCandidateResponse} below).
 */
final class RingsApiDto {

    private RingsApiDto() {
    }

    record Response(
            boolean enabled,
            List<Candidate> candidates
    ) {}

    record Candidate(
            @JsonProperty("account_id") String accountId,
            int community,
            @JsonProperty("in_degree") int inDegree,
            @JsonProperty("temporal_spread_hours") int temporalSpreadHours,
            @JsonProperty("suspicion_score") double suspicionScore,
            boolean planted
    ) {}
}
