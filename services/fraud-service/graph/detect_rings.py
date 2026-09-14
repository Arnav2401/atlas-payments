from __future__ import annotations

import argparse

from graph.neo4j_client import add_connection_args, connect
from graph.plant_rings import RING_SIZES, planted_account_id
from graph.schema import ACCOUNT_LABEL, GDS_GRAPH_NAME, TRANSACTED_TO

TOP_K = 15  # an analyst's shortlist length
# Without a floor, an ordinary account with a handful of senders who happened to
# transact in the same step outranks a real ring: a small in-degree only needs a
# small, easily coincidental spread to look concentrated.
MIN_SUSPICIOUS_IN_DEGREE = min(RING_SIZES)


def project_graph(gds):
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
        if gds.graph.exists(GDS_GRAPH_NAME)["exists"]:
            gds.graph.drop(GDS_GRAPH_NAME)
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
