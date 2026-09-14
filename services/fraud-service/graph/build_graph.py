"""Loads the training-period counterparty graph into Neo4j.

    uv run python -m graph.build_graph --data /path/to/paysim.csv

<h2>Why training-period only, not the whole file</h2>

The M3 model's own temporal_split (training/data.py) holds out the last 20%
of fraud-eligible rows, by row count, as a test set the model has never seen.
Graph features derived in graph_features.py are joined onto that same
train/test split for the uplift experiment in
training/train_graph_uplift.py - so the graph they come from must be built
from training-period edges only. A graph built from the *whole* file would
let a test-set account's centrality score reflect test-period transactions
that happen chronologically after the payment being scored - the graph
analogue of the leak training/features.py's docstring warns about for the
row-level velocity features, and just as real: PageRank and community
membership are global properties of whatever edges are in the graph, so
"don't use future data" has to be enforced at graph-construction time, not
per-query.

<h2>Why edges are scoped to repeat destinations, not the full graph</h2>

training/features.py's own module docstring already measured this dataset:
origin accounts are almost entirely one-shot (2,768,630 of 2,770,409 rows
have a unique nameOrig), so the vast majority of (orig, dest) pairs are a
single edge between two degree-1 nodes. A node with degree 1 cannot be part
of any multi-node community Louvain would find, and contributes nothing to
another account's centrality score either - it is graph-theoretic dead
weight for this specific analysis (finding fan-in mule clusters), not a
signal Louvain or PageRank could ever use. Restricting the loaded graph to
edges whose destination has received from at least MIN_DEST_IN_DEGREE
distinct senders keeps every edge that could possibly matter to community
detection or centrality - the same "the data itself tells you what to keep"
reasoning training/features.py already uses to skip one-shot nameOrig groups
in its own rolling-window loop.

Measured against the real training-period data (not assumed from the
one-shot-*origin* finding above, which is a different measurement): this cut
2,216,327 aggregated training rows to 2,116,737 edges - only a 4.5%
reduction, not the "much smaller" one might expect by analogy with origin
accounts. The two measurements are not the same claim: nameOrig is almost
always unique, but the ~304,868 distinct destination accounts each average
~7 distinct senders, so the MIN_DEST_IN_DEGREE=2 bar is a low one that most
edges already clear. The filter is kept anyway - it is still correct (a true
degree-1 destination genuinely cannot be part of any multi-node community),
it costs nothing, and a filter that turns out to remove less than expected
is a reason to state the real number, not to assume the code was wrong.
"""

from __future__ import annotations

import argparse
import time

import pandas as pd

from graph.neo4j_client import add_connection_args, connect
from graph.schema import (
    ACCOUNT_LABEL,
    PROP_ACCOUNT_ID,
    PROP_FIRST_STEP,
    PROP_LAST_STEP,
    PROP_TOTAL_AMOUNT,
    PROP_WEIGHT,
    TRANSACTED_TO,
)
from training.data import load_raw, scope_to_fraud_eligible_types, temporal_split

MIN_DEST_IN_DEGREE = 2  # see module docstring
BATCH_SIZE = 5_000


def build_edges(csv_path: str) -> pd.DataFrame:
    raw = load_raw(csv_path)
    scoped = scope_to_fraud_eligible_types(raw)
    train_df, _test_df = temporal_split(scoped, train_fraction=0.8)
    print(f"training-period rows for the graph: {len(train_df):,}")

    edges = (
        train_df.groupby(["nameOrig", "nameDest"])
        .agg(weight=("amount", "size"), totalAmount=("amount", "sum"), firstStep=("step", "min"), lastStep=("step", "max"))
        .reset_index()
    )
    print(f"aggregated to {len(edges):,} distinct (origin, destination) edges")

    dest_in_degree = edges.groupby("nameDest")["nameOrig"].nunique() if len(edges) else pd.Series(dtype=int)
    repeat_destinations = dest_in_degree[dest_in_degree >= MIN_DEST_IN_DEGREE].index
    scoped_edges = edges[edges["nameDest"].isin(repeat_destinations)]
    coverage = f"{len(scoped_edges) / len(edges):.1%}" if len(edges) else "n/a"
    print(
        f"scoped to destinations with >= {MIN_DEST_IN_DEGREE} distinct senders: "
        f"{len(scoped_edges):,} edges ({coverage} of the aggregated total), "
        f"{scoped_edges['nameDest'].nunique():,} distinct destination accounts"
    )
    return scoped_edges


def load_into_neo4j(driver, edges: pd.DataFrame) -> None:
    with driver.session() as session:
        session.run(f"CREATE CONSTRAINT account_id IF NOT EXISTS FOR (a:{ACCOUNT_LABEL}) REQUIRE a.{PROP_ACCOUNT_ID} IS UNIQUE")

        rows = edges.to_dict("records")
        t0 = time.time()
        for i in range(0, len(rows), BATCH_SIZE):
            batch = rows[i : i + BATCH_SIZE]
            session.run(
                f"""
                UNWIND $rows AS row
                MERGE (o:{ACCOUNT_LABEL} {{{PROP_ACCOUNT_ID}: row.nameOrig}})
                MERGE (d:{ACCOUNT_LABEL} {{{PROP_ACCOUNT_ID}: row.nameDest}})
                MERGE (o)-[t:{TRANSACTED_TO}]->(d)
                SET t.{PROP_WEIGHT} = row.weight,
                    t.{PROP_TOTAL_AMOUNT} = row.totalAmount,
                    t.{PROP_FIRST_STEP} = row.firstStep,
                    t.{PROP_LAST_STEP} = row.lastStep
                """,
                rows=batch,
            )
            if (i // BATCH_SIZE) % 20 == 0:
                print(f"  loaded {min(i + BATCH_SIZE, len(rows)):,} / {len(rows):,} edges")
        print(f"load complete in {time.time() - t0:.1f}s")

        counts = session.run(
            f"MATCH (a:{ACCOUNT_LABEL}) RETURN count(a) AS accounts"
        ).single()
        rel_counts = session.run(
            f"MATCH ()-[t:{TRANSACTED_TO}]->() RETURN count(t) AS edges"
        ).single()
        print(f"graph now holds {counts['accounts']:,} accounts, {rel_counts['edges']:,} edges")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--data", required=True, help="Path to the raw PaySim CSV")
    add_connection_args(parser)
    args = parser.parse_args()

    edges = build_edges(args.data)
    driver = connect(args.neo4j_uri, args.neo4j_user, args.neo4j_password)
    try:
        load_into_neo4j(driver, edges)
    finally:
        driver.close()


if __name__ == "__main__":
    main()
