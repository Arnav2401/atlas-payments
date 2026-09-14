from __future__ import annotations

import pandas as pd

from graph.schema import ACCOUNT_LABEL, GDS_GRAPH_NAME, TRANSACTED_TO


def compute_and_write_algorithms(gds) -> None:
    if gds.graph.exists(GDS_GRAPH_NAME)["exists"]:
        gds.graph.drop(GDS_GRAPH_NAME)
    graph, _result = gds.graph.project(GDS_GRAPH_NAME, ACCOUNT_LABEL, {TRANSACTED_TO: {"orientation": "NATURAL"}})
    try:
        gds.louvain.write(graph, writeProperty="community")
        gds.degree.write(graph, writeProperty="inDegree", orientation="REVERSE")
        gds.pageRank.write(graph, writeProperty="pagerank")
    finally:
        gds.graph.drop(GDS_GRAPH_NAME)


def fetch_account_features(driver) -> pd.DataFrame:
    query = """
    MATCH (a:Account)
    WHERE a.inDegree IS NOT NULL AND a.inDegree > 0
    MATCH (peer:Account {community: a.community})
    WITH a, count(peer) AS communitySize
    RETURN a.accountId AS nameDest, a.inDegree AS dest_graph_in_degree,
           a.pagerank AS dest_pagerank, communitySize AS dest_community_size
    """
    with driver.session() as session:
        rows = [dict(record) for record in session.run(query)]
    return pd.DataFrame(rows)


def join_graph_features(df: pd.DataFrame, account_features: pd.DataFrame) -> pd.DataFrame:
    """Left-joins graph features onto `df` by `nameDest`.

    Accounts missing from the graph keep NaN rather than a zero default, so
    XGBoost splits on the missingness instead of on a value that never happened.
    """
    return df.merge(account_features, on="nameDest", how="left")


GRAPH_FEATURE_NAMES: list[str] = ["dest_graph_in_degree", "dest_pagerank", "dest_community_size"]
