from __future__ import annotations

ACCOUNT_LABEL = "Account"
TRANSACTED_TO = "TRANSACTED_TO"

PROP_ACCOUNT_ID = "accountId"
PROP_PLANTED = "planted"  # True only for graph/plant_rings.py's synthetic accounts

PROP_WEIGHT = "weight"  # transaction count, real edges aggregated from PaySim rows
PROP_TOTAL_AMOUNT = "totalAmount"
PROP_FIRST_STEP = "firstStep"
PROP_LAST_STEP = "lastStep"

GDS_GRAPH_NAME = "atlas-counterparty-graph"

DEFAULT_NEO4J_URI = "bolt://localhost:7687"
DEFAULT_NEO4J_USER = "neo4j"
DEFAULT_NEO4J_PASSWORD = "atlas-demo-password"
