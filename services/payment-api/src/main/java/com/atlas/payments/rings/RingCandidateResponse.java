package com.atlas.payments.rings;

/** This API's own camelCase contract with the ops console — see {@link RingsApiDto}. */
public record RingCandidateResponse(
        String accountId,
        int community,
        int inDegree,
        int temporalSpreadHours,
        double suspicionScore,
        boolean planted
) {
    static RingCandidateResponse from(RingsApiDto.Candidate candidate) {
        return new RingCandidateResponse(
                candidate.accountId(),
                candidate.community(),
                candidate.inDegree(),
                candidate.temporalSpreadHours(),
                candidate.suspicionScore(),
                candidate.planted());
    }
}
