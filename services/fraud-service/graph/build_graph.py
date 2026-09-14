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

# A destination with a single sender is a degree-1 node: it cannot be part of
# any community Louvain would find, and adds nothing to centrality.
MIN_DEST_IN_DEGREE = 2
BATCH_SIZE = 5_000


def build_edges(csv_path: str) -> pd.DataFrame:
    raw = load_raw(csv_path)
    scoped = scope_to_fraud_eligible_types(raw)
    # Training-period edges only. PageRank and community membership are global
    # properties of whatever is in the graph, so including test-period edges
    # would leak the future into a test row's features - the graph-level version
    # of the leak the row-level velocity features avoid.
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
