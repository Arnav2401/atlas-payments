"""Shared constants for the M6 counterparty graph — the Neo4j analogue of
``fraud_service.feature_spec``: one place naming the node label, relationship
type, and property keys every script in this package writes or reads, so a
typo in one script fails the next script's Cypher rather than silently
matching zero rows.
"""

from __future__ import annotations

ACCOUNT_LABEL = "Account"
TRANSACTED_TO = "TRANSACTED_TO"

# Node properties
PROP_ACCOUNT_ID = "accountId"
PROP_PLANTED = "planted"  # True only for graph/plant_rings.py's synthetic accounts

# Relationship properties
PROP_WEIGHT = "weight"  # transaction count, real edges aggregated from PaySim rows
PROP_TOTAL_AMOUNT = "totalAmount"
PROP_FIRST_STEP = "firstStep"
PROP_LAST_STEP = "lastStep"

# GDS in-memory graph projection name, shared by detect_rings.py and
# graph_features.py so both run algorithms against the same projection
# without re-projecting it twice.
GDS_GRAPH_NAME = "atlas-counterparty-graph"

DEFAULT_NEO4J_URI = "bolt://localhost:7687"
DEFAULT_NEO4J_USER = "neo4j"
DEFAULT_NEO4J_PASSWORD = "atlas-demo-password"
