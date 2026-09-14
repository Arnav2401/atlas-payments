from __future__ import annotations

from dataclasses import dataclass

from neo4j import Driver

MIN_SUSPICIOUS_IN_DEGREE = 8


@dataclass(frozen=True)
class RingCandidate:
    account_id: str
    community: int
    in_degree: int
    temporal_spread_hours: int
    suspicion_score: float
    planted: bool


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
