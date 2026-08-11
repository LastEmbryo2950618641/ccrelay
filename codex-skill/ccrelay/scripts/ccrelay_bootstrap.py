#!/usr/bin/env python3
"""Minimal local bootstrap stage persistence."""

from __future__ import annotations

import os
import sqlite3
import time
from contextlib import closing
from pathlib import Path
from typing import Any, Dict


UNINITIALIZED = "UNINITIALIZED"
SSH_READY = "SSH_READY"
DEPLOYED = "DEPLOYED"
VALID_STAGES = {SSH_READY, DEPLOYED}


def skill_root() -> Path:
    return Path(__file__).resolve().parents[1]


def database_path() -> Path:
    configured = str(os.environ.get("CCRELAY_BOOTSTRAP_DB") or "").strip()
    return Path(configured).expanduser().resolve() if configured else skill_root() / ".local" / "bootstrap-state.db"


def initialize(connection: sqlite3.Connection) -> None:
    connection.execute(
        """
        CREATE TABLE IF NOT EXISTS bootstrap_stage (
            cluster_id TEXT PRIMARY KEY,
            stage TEXT NOT NULL,
            updated_at INTEGER NOT NULL
        )
        """
    )
    connection.commit()


def get_stage(cluster_id: str = "default") -> Dict[str, Any]:
    path = database_path()
    if not path.is_file():
        return stage_result(cluster_id, UNINITIALIZED, None)
    with closing(sqlite3.connect(path)) as connection:
        initialize(connection)
        row = connection.execute(
            "SELECT stage, updated_at FROM bootstrap_stage WHERE cluster_id = ?",
            (cluster_id,),
        ).fetchone()
    return stage_result(cluster_id, row[0], row[1]) if row else stage_result(cluster_id, UNINITIALIZED, None)


def set_stage(cluster_id: str, stage: str) -> Dict[str, Any]:
    normalized = str(stage or "").strip().upper()
    if normalized not in VALID_STAGES:
        raise ValueError(f"unsupported bootstrap stage: {stage}")
    path = database_path()
    path.parent.mkdir(parents=True, exist_ok=True)
    updated_at = int(time.time() * 1000)
    with closing(sqlite3.connect(path)) as connection:
        initialize(connection)
        connection.execute(
            """
            INSERT INTO bootstrap_stage(cluster_id, stage, updated_at)
            VALUES (?, ?, ?)
            ON CONFLICT(cluster_id) DO UPDATE SET
                stage = excluded.stage,
                updated_at = excluded.updated_at
            """,
            (cluster_id, normalized, updated_at),
        )
        connection.commit()
    return stage_result(cluster_id, normalized, updated_at)


def reset(cluster_id: str = "default") -> Dict[str, Any]:
    path = database_path()
    if path.is_file():
        with closing(sqlite3.connect(path)) as connection:
            initialize(connection)
            connection.execute("DELETE FROM bootstrap_stage WHERE cluster_id = ?", (cluster_id,))
            connection.commit()
    return stage_result(cluster_id, UNINITIALIZED, None)


def stage_result(cluster_id: str, stage: str, updated_at: int | None) -> Dict[str, Any]:
    return {
        "clusterId": cluster_id,
        "stage": stage,
        "initialized": stage != UNINITIALIZED,
        "updatedAt": updated_at,
    }
