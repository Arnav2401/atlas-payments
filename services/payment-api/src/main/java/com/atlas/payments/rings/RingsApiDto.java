package com.atlas.payments.rings;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

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
