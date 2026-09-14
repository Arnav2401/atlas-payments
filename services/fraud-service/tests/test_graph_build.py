"""Pure-logic tests for graph/build_graph.py and graph/plant_rings.py - the
parts that don't need a live Neo4j, so they run in CI like every other test
here. The actual Louvain/degree/PageRank pipeline is exercised for real
against docker-compose's Neo4j (see the M6 section of the README for the
exact commands and the numbers they produced) rather than mocked here -
mirroring test_feature_latency.py's own honesty about what it does and does
not cover.
"""

from __future__ import annotations

import pandas as pd

from graph.build_graph import MIN_DEST_IN_DEGREE, build_edges
from graph.plant_rings import RING_SIZES, generate_rings, planted_account_id


def _write_csv(tmp_path, rows: list[dict]):
    path = tmp_path / "synthetic_paysim.csv"
    pd.DataFrame(rows).to_csv(path, index=False)
    return path


def _row(step, name_orig, name_dest, amount=100.0, txn_type="TRANSFER"):
    return {
        "step": step,
        "type": txn_type,
        "amount": amount,
        "nameOrig": name_orig,
        "oldbalanceOrg": 1000.0,
        "newbalanceOrig": 900.0,
        "nameDest": name_dest,
        "oldbalanceDest": 0.0,
        "newbalanceDest": amount,
        "isFraud": 0,
        "isFlaggedFraud": 0,
    }


def _padding_rows(start_step: int, count: int) -> list[dict]:
    """Filler rows so a small fixture's 80/20 temporal_split cutoff still
    lands after the rows a test actually cares about - build_edges() always
    runs the real training-period split (see build_graph.py's module
    docstring on why that is not optional), so a fixture too small for an
    80/20 split to leave a meaningful train portion would test the wrong
    thing rather than nothing. Each padding row is its own one-shot,
    one-sender destination, which MIN_DEST_IN_DEGREE drops on its own - inert
    by construction, not just by accident.
    """
    return [_row(start_step + i, f"pad-orig-{i}", f"pad-dest-{i}") for i in range(count)]


def test_build_edges_aggregates_repeated_pairs_into_one_edge(tmp_path):
    rows = [_row(1, "A", "M1", amount=50.0), _row(2, "A", "M1", amount=70.0)]
    # M1 needs a second, distinct sender too, or the MIN_DEST_IN_DEGREE scope
    # (a real production concern, not a test artifact) drops it entirely.
    rows.append(_row(3, "B", "M1", amount=10.0))
    rows += _padding_rows(10, 20)
    csv_path = _write_csv(tmp_path, rows)

    edges = build_edges(str(csv_path))

    a_to_m1 = edges[(edges["nameOrig"] == "A") & (edges["nameDest"] == "M1")].iloc[0]
    assert a_to_m1["weight"] == 2
    assert a_to_m1["totalAmount"] == 120.0
    assert a_to_m1["firstStep"] == 1
    assert a_to_m1["lastStep"] == 2


def test_build_edges_drops_one_shot_destinations(tmp_path):
    # A single sender to a single destination - below MIN_DEST_IN_DEGREE, and
    # this dataset's own dominant pattern per training/features.py's own
    # measurement (nearly every nameOrig is one-shot).
    rows = [_row(1, "LONELY_SENDER", "LONELY_DEST", amount=999.0)]
    rows += _padding_rows(10, 20)
    csv_path = _write_csv(tmp_path, rows)

    edges = build_edges(str(csv_path))

    assert MIN_DEST_IN_DEGREE == 2
    assert "LONELY_DEST" not in edges["nameDest"].values


def test_build_edges_scopes_to_fraud_eligible_types_and_training_period(tmp_path):
    # PAYMENT is never fraud-eligible (see training/data.py) - two rows here so
    # the destination clears MIN_DEST_IN_DEGREE and the assertion is actually
    # testing the type filter, not incidentally re-testing the degree filter.
    rows = [_row(1, "X", "Y", txn_type="PAYMENT"), _row(2, "Z", "Y", txn_type="PAYMENT")]
    rows += [_row(i, f"orig{i}", "REAL_DEST", txn_type="CASH_OUT") for i in range(3, 8)]
    rows += _padding_rows(10, 20)
    csv_path = _write_csv(tmp_path, rows)

    edges = build_edges(str(csv_path))

    assert "Y" not in edges["nameDest"].values
    assert "REAL_DEST" in edges["nameDest"].values


def test_generate_rings_produces_one_disjoint_component_per_ring():
    rings = generate_rings()

    assert len(rings) == len(RING_SIZES)
    all_sources: set[str] = set()
    for ring, expected_size in zip(rings, RING_SIZES):
        assert len(ring["sources"]) == expected_size
        assert len(ring["edges"]) == expected_size
        # Every planted edge in this ring points at this ring's own mule -
        # the fan-in shape the whole exercise exists to test.
        assert all(edge["nameDest"] == ring["mule"] for edge in ring["edges"])
        all_sources.update(ring["sources"])

    # No source account is reused across rings - each ring is a genuinely
    # separate, disconnected component, which is exactly what makes
    # graph/detect_rings.py's "same Louvain community" check trivially true
    # by construction (see that module's own docstring on why that is NOT
    # the interesting part of the experiment).
    assert len(all_sources) == sum(RING_SIZES)


def test_generate_rings_is_deterministic():
    assert generate_rings() == generate_rings()


def test_planted_account_id_naming():
    assert planted_account_id(0, "mule") == "RING0-MULE"
    assert planted_account_id(2, "src", 5) == "RING2-SRC-5"
