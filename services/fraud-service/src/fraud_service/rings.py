"""Serves the same suspicion ranking graph/detect_rings.py prints from the
command line, but as a read-only query against properties GDS has already
written (`community`, `inDegree`) — this module never runs Louvain or
degree centrality itself. Re-running community detection on every HTTP
request would make GET /rings' latency depend on the size of the whole
counterparty graph; the write side (graph/detect_rings.py or
graph/graph_features.py's compute_and_write_algorithms) is a batch job run
ahead of time, same division of labour as the M3 model itself being trained
offline and only scored online.
"""

from __future__ import annotations

from dataclasses import dataclass

from neo4j import Driver

# Matches graph/detect_rings.py's MIN_SUSPICIOUS_IN_DEGREE (min(RING_SIZES) there) -
# not imported, for the same package-boundary reason as the copied query below,
# so kept as a literal with the reasoning restated: without an in-degree floor,
# a real account with a handful of senders who coincidentally transacted within
# the same PaySim step scores as "suspicious" as a genuine fan-in ring, because
# a small in-degree only needs a small (easily accidental) temporal spread to
# look concentrated. See detect_rings.py's module docstring for the measured
# numbers (0/6 planted mules surfaced without this floor; 6/6 did with it).
MIN_SUSPICIOUS_IN_DEGREE = 8


@dataclass(frozen=True)
class RingCandidate:
    account_id: str
    community: int
    in_degree: int
    temporal_spread_hours: int
    suspicion_score: float
    planted: bool


# Deliberately the same Cypher as graph/detect_rings.py's rank_mule_candidates,
# copied rather than imported: `graph/` is offline training-time tooling (it
# imports pandas, xgboost, shap — nothing this served app should pull into its
# runtime image), and `fraud_service/` is the served app. Two small, separately
# reviewable copies of one query beats a cross-package import that would blur
# a boundary the rest of this codebase (training/ vs src/fraud_service/)
# already keeps deliberately sharp.
_QUERY = """
MATCH (a:Account)
WHERE a.inDegree IS NOT NULL AND a.inDegree >= $min_in_degree
MATCH (sender)-[t:TRANSACTED_TO]->(a)
WITH a, a.community AS community, a.inDegree AS inDegree,
     min(t.firstStep) AS minStep, max(t.lastStep) AS maxStep,
     coalesce(a.planted, false) AS planted
WITH a, community, inDegree, (maxStep - minStep) AS temporalSpread, planted
RETURN a.accountId AS accountId, community, inDegree, temporalSpread,
       toFloat(inDegree) / (temporalSpread + 1) AS suspicionScore, planted
ORDER BY suspicionScore DESC
LIMIT $top_k
"""


def top_ring_candidates(driver: Driver, top_k: int = 15, min_in_degree: int = MIN_SUSPICIOUS_IN_DEGREE) -> list[RingCandidate]:
    with driver.session() as session:
        rows = session.run(_QUERY, top_k=top_k, min_in_degree=min_in_degree)
        return [
            RingCandidate(
                account_id=row["accountId"],
                community=row["community"],
                in_degree=row["inDegree"],
                temporal_spread_hours=row["temporalSpread"],
                suspicion_score=row["suspicionScore"],
                planted=row["planted"],
            )
            for row in rows
        ]
