#!/usr/bin/env python3
"""Build a trimmed, FTS5-indexed SQLite of MTG cards + rulings from Scryfall bulk data.

Card data from Scryfall (https://scryfall.com). Card names and Oracle text are
Wizards of the Coast IP, shown for reference only. Personal, non-commercial fan
project under the WotC Fan Content Policy. No card images are bundled.
"""
from __future__ import annotations

import json

# Layouts that are not real playable cards we look up rulings for.
EXCLUDED_LAYOUTS = frozenset({"token", "double_faced_token", "emblem", "art_series"})


def should_include(card: dict) -> bool:
    """True if this Scryfall oracle-card entry should be bundled."""
    if not card.get("oracle_id"):
        return False
    if card.get("layout") in EXCLUDED_LAYOUTS:
        return False
    if str(card.get("name", "")).startswith("A-"):  # Alchemy rebalance
        return False
    return True


def _join_faces(card: dict, key: str, sep: str) -> str:
    faces = card.get("card_faces") or []
    parts = [str(face.get(key, "")).strip() for face in faces]
    return sep.join(part for part in parts if part)


def extract_card(card: dict) -> dict:
    """Map a Scryfall oracle-card entry to a Card row."""
    mana_cost = card.get("mana_cost") or ""
    if not mana_cost and card.get("card_faces"):
        mana_cost = _join_faces(card, "mana_cost", " // ")

    type_line = card.get("type_line") or ""
    if not type_line and card.get("card_faces"):
        type_line = _join_faces(card, "type_line", " // ")

    oracle_text = card.get("oracle_text")
    if oracle_text is None and card.get("card_faces"):
        oracle_text = _join_faces(card, "oracle_text", "\n//\n")

    return {
        "oracleId": card["oracle_id"],
        "name": card.get("name", ""),
        "manaCost": mana_cost,
        "typeLine": type_line,
        "oracleText": oracle_text or "",
        "keywords": json.dumps(card.get("keywords") or []),
    }


def extract_ruling(ruling: dict) -> dict:
    """Map a Scryfall ruling entry to a Ruling row (without the autoincrement id)."""
    return {
        "oracleId": ruling.get("oracle_id", ""),
        "publishedAt": ruling.get("published_at", ""),
        "text": ruling.get("comment", ""),
    }
