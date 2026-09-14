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
    return [_row(start_step + i, f"pad-orig-{i}", f"pad-dest-{i}") for i in range(count)]


def test_build_edges_aggregates_repeated_pairs_into_one_edge(tmp_path):
    rows = [_row(1, "A", "M1", amount=50.0), _row(2, "A", "M1", amount=70.0)]
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
    rows = [_row(1, "LONELY_SENDER", "LONELY_DEST", amount=999.0)]
    rows += _padding_rows(10, 20)
    csv_path = _write_csv(tmp_path, rows)

    edges = build_edges(str(csv_path))

    assert MIN_DEST_IN_DEGREE == 2
    assert "LONELY_DEST" not in edges["nameDest"].values


def test_build_edges_scopes_to_fraud_eligible_types_and_training_period(tmp_path):
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
        assert all(edge["nameDest"] == ring["mule"] for edge in ring["edges"])
        all_sources.update(ring["sources"])

    assert len(all_sources) == sum(RING_SIZES)


def test_generate_rings_is_deterministic():
    assert generate_rings() == generate_rings()


def test_planted_account_id_naming():
    assert planted_account_id(0, "mule") == "RING0-MULE"
    assert planted_account_id(2, "src", 5) == "RING2-SRC-5"
