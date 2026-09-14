from __future__ import annotations

import argparse
import random

from graph.neo4j_client import add_connection_args, connect
from graph.schema import ACCOUNT_LABEL, PROP_ACCOUNT_ID, PROP_PLANTED, PROP_TOTAL_AMOUNT, PROP_WEIGHT, TRANSACTED_TO

SEED = 42
RING_SIZES = [8, 10, 12, 15, 18, 22]  # varied on purpose - a detector tuned to one exact size proves less
STEP_WINDOW = 6  # hours the whole ring transacts within - "coordinated", not "similar by chance"
RING_ACCOUNT_PREFIX = "RING"


def planted_account_id(ring_index: int, role: str, member_index: int | None = None) -> str:
    if role == "mule":
        return f"{RING_ACCOUNT_PREFIX}{ring_index}-MULE"
    return f"{RING_ACCOUNT_PREFIX}{ring_index}-SRC-{member_index}"


def generate_rings() -> list[dict]:
    rng = random.Random(SEED)
    rings = []
    for ring_index, size in enumerate(RING_SIZES):
        mule = planted_account_id(ring_index, "mule")
        base_step = rng.randint(100, 700)
        sources = [planted_account_id(ring_index, "src", i) for i in range(size)]
        edges = []
        for i, src in enumerate(sources):
            step = base_step + rng.randint(0, STEP_WINDOW)
            amount = round(rng.uniform(5_000, 50_000), 2)
            edges.append({"nameOrig": src, "nameDest": mule, "step": step, "amount": amount})
        rings.append({"ring_index": ring_index, "mule": mule, "sources": sources, "edges": edges})
    return rings


def load_rings(driver, rings: list[dict]) -> None:
    with driver.session() as session:
        for ring in rings:
            rows = ring["edges"]
            session.run(
                f"""
                UNWIND $rows AS row
                MERGE (o:{ACCOUNT_LABEL} {{{PROP_ACCOUNT_ID}: row.nameOrig}})
                SET o.{PROP_PLANTED} = true
                MERGE (d:{ACCOUNT_LABEL} {{{PROP_ACCOUNT_ID}: row.nameDest}})
                SET d.{PROP_PLANTED} = true
                MERGE (o)-[t:{TRANSACTED_TO}]->(d)
                SET t.{PROP_WEIGHT} = 1, t.{PROP_TOTAL_AMOUNT} = row.amount,
                    t.firstStep = row.step, t.lastStep = row.step
                """,
                rows=rows,
            )
            print(f"  planted ring {ring['ring_index']}: {len(ring['sources'])} sources -> {ring['mule']}")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    add_connection_args(parser)
    args = parser.parse_args()

    rings = generate_rings()
    total_accounts = sum(len(r["sources"]) + 1 for r in rings)
    print(f"generated {len(rings)} rings, {total_accounts} planted accounts total")

    driver = connect(args.neo4j_uri, args.neo4j_user, args.neo4j_password)
    try:
        load_rings(driver, rings)
    finally:
        driver.close()

    print(
        "\nground truth for graph/detect_rings.py: mule accounts are "
        f"{[planted_account_id(i, 'mule') for i in range(len(RING_SIZES))]}"
    )


if __name__ == "__main__":
    main()
