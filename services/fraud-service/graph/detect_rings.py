"""Runs Louvain community detection + degree centrality over the whole graph
(real repeat-destination accounts + graph/plant_rings.py's synthetic rings),
then measures whether the planted mule accounts actually surface near the top
of a suspicion ranking - not whether they end up in *a* community, which is
true by construction and would not test anything.

    uv run python -m graph.detect_rings

<h2>Why "same community" alone is not the test</h2>

Every planted ring's accounts only ever transact with each other - there is
no edge connecting a `RINGk-*` account to any real PaySim account. Louvain
therefore puts each ring in its own community *by definition*, as a
disconnected component; reporting "6 of 6 rings share one community" would
be reporting a fact about how the data was constructed, not a result. The
real question - the one an analyst actually has - is: out of every community
Louvain finds across the whole graph (thousands of them, almost all real,
ordinary repeat-merchant neighbourhoods), does a ranking built from
centrality and temporal structure actually surface the six planted rings
near the top, rather than requiring someone to already know which six to
look for?

<h2>The suspicion score</h2>

For each community's highest-in-degree account (`gds.degree`, its
GDS-computed in-degree - a stand-in for "receives from many senders", the
same fan-in shape `dest_prior_distinct_senders_24h` already captures at row
level in the M3 model), the score is:

    in_degree / (temporal_spread_hours + 1)

Real repeat destinations (merchants, exchanges) in this dataset accumulate
senders across the whole 743-step simulation; graph/plant_rings.py's rings
are deliberately coordinated within a `STEP_WINDOW`-hour window. High
in-degree concentrated into a short window scores higher than the same
in-degree spread over months - not a hand-picked threshold, but the same
"count of dth window" idea the M3 feature ``dest_prior_txn_count_24h``
already uses, just applied over the account's *entire* observed span instead
of one causal 24h lookback.

<h2>MIN_SUSPICIOUS_IN_DEGREE, added after the first real run scored 0%</h2>

The first version of this ranking had no in-degree floor and scored 0 of 6
planted mules in the top 15 - not a bug, a real result worth keeping the
record of: an ordinary account that happens to receive from just 4-7
distinct senders within the *same* PaySim step (common - median volume is
112 rows/step, per training/data.py's own measurement, so incidental
same-hour overlap among a handful of senders is not rare) scores highest
under a pure ratio, because a small in-degree needs only a small - easily
coincidental - temporal spread to look "concentrated". Measured directly
(`MATCH (a) WHERE a.inDegree >= 8 ...`): 96,638 real accounts clear an
in-degree of 8, with a *mean* temporal spread of 252 hours - so the ratio
score is doing its job once accounts too small to be a meaningful ring
candidate are excluded first. The floor is `min(RING_SIZES)`, not a tuned
constant: it says "as suspicious as the smallest ring actually planted",
which is the honest bar an analyst without a ground-truth ring size to tune
against would also have to guess at.
"""

from __future__ import annotations

import argparse

from graph.neo4j_client import add_connection_args, connect
from graph.plant_rings import RING_ACCOUNT_PREFIX, RING_SIZES, planted_account_id
from graph.schema import ACCOUNT_LABEL, GDS_GRAPH_NAME, PROP_ACCOUNT_ID, TRANSACTED_TO

TOP_K = 15  # community "mule candidates" inspected - a real analyst's shortlist size, not a tuned constant
MIN_SUSPICIOUS_IN_DEGREE = min(RING_SIZES)  # see module docstring


def project_graph(gds):
    """Returns the projected `Graph` handle - the graphdatascience Python
    client's algorithm procedures (gds.louvain.write, gds.degree.write, ...)
    take that object directly, not the projection's name string, despite
    Cypher's own `gds.louvain.write('name', ...)` accepting a string; the two
    APIs are not symmetric, found by the client library raising a TypeError
    the first time this ran rather than assumed from the docs.
    """
    if gds.graph.exists(GDS_GRAPH_NAME)["exists"]:
        gds.graph.drop(GDS_GRAPH_NAME)
    graph, _result = gds.graph.project(
        GDS_GRAPH_NAME,
        ACCOUNT_LABEL,
        {TRANSACTED_TO: {"orientation": "NATURAL"}},
    )
    return graph


def run_louvain_and_degree(gds, graph) -> None:
    gds.louvain.write(graph, writeProperty="community")
    # REVERSE orientation for degree: an account's *in*-degree (how many
    # distinct counterparties sent it money) is the fan-in signal that
    # matters for mule identification, not out-degree.
    gds.degree.write(graph, writeProperty="inDegree", orientation="REVERSE")


def rank_mule_candidates(driver, top_k: int, min_in_degree: int = MIN_SUSPICIOUS_IN_DEGREE) -> list[dict]:
    query = """
    MATCH (a:Account)
    WHERE a.inDegree >= $min_in_degree
    MATCH (sender)-[t:TRANSACTED_TO]->(a)
    WITH a, a.community AS community, a.inDegree AS inDegree,
         min(t.firstStep) AS minStep, max(t.lastStep) AS maxStep
    WITH a, community, inDegree, (maxStep - minStep) AS temporalSpread
    RETURN a.accountId AS accountId, community, inDegree, temporalSpread,
           toFloat(inDegree) / (temporalSpread + 1) AS suspicionScore
    ORDER BY suspicionScore DESC
    LIMIT $top_k
    """
    with driver.session() as session:
        return [dict(record) for record in session.run(query, top_k=top_k, min_in_degree=min_in_degree)]


def main() -> None:
    from graphdatascience import GraphDataScience

    parser = argparse.ArgumentParser(description=__doc__)
    add_connection_args(parser)
    parser.add_argument("--top-k", type=int, default=TOP_K)
    args = parser.parse_args()

    driver = connect(args.neo4j_uri, args.neo4j_user, args.neo4j_password)
    gds = GraphDataScience(driver)
    try:
        graph = project_graph(gds)
        run_louvain_and_degree(gds, graph)
        ranked = rank_mule_candidates(driver, args.top_k)
    finally:
        gds.graph.drop(GDS_GRAPH_NAME) if gds.graph.exists(GDS_GRAPH_NAME)["exists"] else None
        driver.close()

    planted_mules = {planted_account_id(i, "mule") for i in range(len(RING_SIZES))}

    print(f"\ntop {args.top_k} mule candidates by suspicion score:")
    print(f"{'accountId':<20}{'community':<12}{'inDegree':<10}{'spread(h)':<12}{'score':<10}{'planted?'}")
    hits = 0
    for row in ranked:
        is_planted = row["accountId"] in planted_mules
        hits += is_planted
        marker = "PLANTED" if is_planted else ""
        print(
            f"{row['accountId']:<20}{row['community']:<12}{row['inDegree']:<10}"
            f"{row['temporalSpread']:<12}{row['suspicionScore']:<10.2f}{marker}"
        )

    recall = hits / len(planted_mules)
    precision = hits / len(ranked) if ranked else 0.0
    print(
        f"\n{hits} of {len(planted_mules)} planted mule accounts appeared in the top {args.top_k} "
        f"(recall={recall:.0%}, precision@{args.top_k}={precision:.0%})"
    )


if __name__ == "__main__":
    main()
