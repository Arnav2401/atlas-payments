"""Reads per-account graph features back out of Neo4j, for
training/train_graph_uplift.py to join onto the M3 feature set by
`nameDest`.

<h2>Causality, restated from build_graph.py</h2>

These features come from a graph built from training-period edges only (see
build_graph.py's module docstring for why), so a static "as of the end of
the training period" account profile is safe to join onto both the train and
test frames without leaking test-period structure into either - the same
account-level generalisation the row-level velocity features in
training/features.py make when they use a fixed 24h window rather than
"everything up to now", just courser-grained: one snapshot instead of a
per-row causal recomputation. Documented as a real, deliberate
simplification for a first version of graph features, not an oversight -
a fully causal graph feature (recomputed as of each row's own timestamp)
is the natural next iteration, at the cost of re-running GDS per time slice.

<h2>Three features, one per GDS algorithm the brief names</h2>

- `dest_graph_in_degree` - fan-in count (gds.degree, REVERSE orientation).
  A graph-native version of `dest_prior_distinct_senders_24h`, over the
  whole training period rather than a rolling 24h window.
- `dest_pagerank` - PageRank, weighted by how *important* an account's
  senders are, not just how many there are - a mule fed by other
  already-suspicious accounts should outrank one fed by ordinary customers,
  which a plain in-degree count cannot distinguish.
- `dest_community_size` - Louvain community size. A small, tight community
  is structurally closer to graph/plant_rings.py's planted shape than a
  large one; a huge community (a well-known exchange with thousands of
  ordinary depositors) is exactly what this feature should NOT flag, and
  size alone lets the model learn that distinction rather than assuming it.

Accounts absent from the graph (below build_graph.py's MIN_DEST_IN_DEGREE
threshold, or the training frame's origin-only accounts) get NaN, not a
zero-filled default - see training/train.py's own comment on why this
codebase leaves missingness for XGBoost's native sparsity-aware splits
rather than imputing a value that never happened.
"""

from __future__ import annotations

import pandas as pd

from graph.schema import ACCOUNT_LABEL, GDS_GRAPH_NAME, TRANSACTED_TO


def compute_and_write_algorithms(gds) -> None:
    """Idempotent: (re)projects the graph and (re)runs both algorithms,
    writing `community` and `inDegree`/`pagerank` node properties back to
    Neo4j - the same GDS calls detect_rings.py makes, kept separate here so
    graph_features.py can be run standalone against an already-built graph
    without needing plant_rings.py's synthetic rings present at all (the
    uplift experiment uses the real graph only - see train_graph_uplift.py).
    """
    if gds.graph.exists(GDS_GRAPH_NAME)["exists"]:
        gds.graph.drop(GDS_GRAPH_NAME)
    # The client's algo procedures take the `Graph` object project() returns,
    # not the name string - see detect_rings.py's project_graph docstring.
    graph, _result = gds.graph.project(GDS_GRAPH_NAME, ACCOUNT_LABEL, {TRANSACTED_TO: {"orientation": "NATURAL"}})
    try:
        gds.louvain.write(graph, writeProperty="community")
        gds.degree.write(graph, writeProperty="inDegree", orientation="REVERSE")
        gds.pageRank.write(graph, writeProperty="pagerank")
    finally:
        gds.graph.drop(GDS_GRAPH_NAME)


def fetch_account_features(driver) -> pd.DataFrame:
    """One row per account that has at least one incoming edge in the graph
    (i.e., every node build_graph.py's MIN_DEST_IN_DEGREE scoping kept),
    with its Louvain community's size joined in.
    """
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
    """Left-joins graph features onto `df` by `nameDest`. Rows with no match
    (see module docstring) keep NaN in all three new columns."""
    return df.merge(account_features, on="nameDest", how="left")


GRAPH_FEATURE_NAMES: list[str] = ["dest_graph_in_degree", "dest_pagerank", "dest_community_size"]
