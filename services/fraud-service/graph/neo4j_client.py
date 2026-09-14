from __future__ import annotations

import argparse
import os

from neo4j import Driver, GraphDatabase

from graph.schema import DEFAULT_NEO4J_PASSWORD, DEFAULT_NEO4J_URI, DEFAULT_NEO4J_USER


def connect(uri: str | None = None, user: str | None = None, password: str | None = None) -> Driver:
    uri = uri or os.environ.get("ATLAS_NEO4J_URI", DEFAULT_NEO4J_URI)
    user = user or os.environ.get("ATLAS_NEO4J_USER", DEFAULT_NEO4J_USER)
    password = password or os.environ.get("ATLAS_NEO4J_PASSWORD", DEFAULT_NEO4J_PASSWORD)
    driver = GraphDatabase.driver(uri, auth=(user, password))
    driver.verify_connectivity()
    return driver


def add_connection_args(parser: argparse.ArgumentParser) -> None:
    parser.add_argument("--neo4j-uri", default=None, help=f"Default: ${{ATLAS_NEO4J_URI:-{DEFAULT_NEO4J_URI}}}")
    parser.add_argument("--neo4j-user", default=None, help=f"Default: ${{ATLAS_NEO4J_USER:-{DEFAULT_NEO4J_USER}}}")
    parser.add_argument("--neo4j-password", default=None, help="Default: $ATLAS_NEO4J_PASSWORD or the compose default")
