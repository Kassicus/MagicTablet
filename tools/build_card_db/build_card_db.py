#!/usr/bin/env python3
"""Build a trimmed, FTS5-indexed SQLite of MTG cards + rulings from Scryfall bulk data.

Card data from Scryfall (https://scryfall.com). Card names and Oracle text are
Wizards of the Coast IP, shown for reference only. Personal, non-commercial fan
project under the WotC Fan Content Policy. No card images are bundled.
"""
from __future__ import annotations

import argparse
import json
import os
import sqlite3
import urllib.request

BULK_DATA_URL = "https://api.scryfall.com/bulk-data"
USER_AGENT = "MagicTablet/0.1 (personal fan project)"
HERE = os.path.dirname(os.path.abspath(__file__))
CACHE_DIR = os.path.join(HERE, ".cache")
DEFAULT_OUT = os.path.normpath(
    os.path.join(HERE, "..", "..", "app", "src", "main", "assets", "cards.db")
)

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


def _request(url: str) -> urllib.request.Request:
    return urllib.request.Request(
        url, headers={"User-Agent": USER_AGENT, "Accept": "application/json"}
    )


def fetch_bulk_urls() -> dict:
    """Return {'oracle_cards': uri, 'rulings': uri} from the Scryfall bulk-data endpoint."""
    with urllib.request.urlopen(_request(BULK_DATA_URL)) as resp:
        payload = json.load(resp)
    uris = {}
    for item in payload.get("data", []):
        if item.get("type") in ("oracle_cards", "rulings"):
            uris[item["type"]] = item["download_uri"]
    missing = {"oracle_cards", "rulings"} - uris.keys()
    if missing:
        raise RuntimeError(f"Scryfall bulk-data missing types: {missing}")
    return uris


def download(url: str, dest: str, force: bool = False) -> str:
    os.makedirs(os.path.dirname(dest), exist_ok=True)
    if os.path.exists(dest) and not force:
        print(f"  using cached {os.path.basename(dest)}")
        return dest
    print(f"  downloading {os.path.basename(dest)} ...")
    with urllib.request.urlopen(_request(url)) as resp, open(dest, "wb") as out:
        while True:
            chunk = resp.read(1 << 16)
            if not chunk:
                break
            out.write(chunk)
    return dest


def _create_schema(conn: sqlite3.Connection) -> None:
    conn.executescript(
        """
        CREATE TABLE Card (
          oracleId   TEXT PRIMARY KEY,
          name       TEXT NOT NULL,
          manaCost   TEXT,
          typeLine   TEXT,
          oracleText TEXT,
          keywords   TEXT
        );
        CREATE TABLE Ruling (
          id          INTEGER PRIMARY KEY AUTOINCREMENT,
          oracleId    TEXT NOT NULL,
          publishedAt TEXT,
          text        TEXT
        );
        CREATE INDEX idx_ruling_oracleId ON Ruling(oracleId);
        -- FTS4 (not FTS5): the target tablet's framework SQLite (3.44.3) ships
        -- FTS3/FTS4 but NOT FTS5, and the app uses that framework SQLite. FTS4's
        -- `notindexed=` is the equivalent of FTS5's UNINDEXED.
        CREATE VIRTUAL TABLE CardFts USING fts4(
          name, oracleText, oracleId, notindexed=oracleId,
          tokenize=unicode61 "remove_diacritics=2"
        );
        """
    )


def build_db(oracle_path: str, rulings_path: str, out_path: str) -> tuple[int, int]:
    import ijson  # lazy: keeps the pure-function unit tests dependency-free

    tmp_path = out_path + ".tmp"
    if os.path.exists(tmp_path):
        os.remove(tmp_path)
    os.makedirs(os.path.dirname(out_path), exist_ok=True)

    conn = sqlite3.connect(tmp_path)
    try:
        conn.execute("PRAGMA journal_mode = OFF")
        conn.execute("PRAGMA synchronous = OFF")
        _create_schema(conn)

        kept_ids: set[str] = set()
        card_count = 0
        print("  parsing oracle cards ...")
        with open(oracle_path, "rb") as f:
            card_batch, fts_batch = [], []
            for card in ijson.items(f, "item"):
                if not should_include(card):
                    continue
                row = extract_card(card)
                kept_ids.add(row["oracleId"])
                card_batch.append(
                    (row["oracleId"], row["name"], row["manaCost"],
                     row["typeLine"], row["oracleText"], row["keywords"])
                )
                fts_batch.append((row["name"], row["oracleText"], row["oracleId"]))
                if len(card_batch) >= 2000:
                    conn.executemany("INSERT OR IGNORE INTO Card VALUES (?,?,?,?,?,?)", card_batch)
                    conn.executemany("INSERT INTO CardFts (name, oracleText, oracleId) VALUES (?,?,?)", fts_batch)
                    card_count += len(card_batch)
                    card_batch.clear()
                    fts_batch.clear()
            if card_batch:
                conn.executemany("INSERT OR IGNORE INTO Card VALUES (?,?,?,?,?,?)", card_batch)
                conn.executemany("INSERT INTO CardFts (name, oracleText, oracleId) VALUES (?,?,?)", fts_batch)
                card_count += len(card_batch)

        ruling_count = 0
        print("  parsing rulings ...")
        with open(rulings_path, "rb") as f:
            ruling_batch = []
            for ruling in ijson.items(f, "item"):
                row = extract_ruling(ruling)
                if row["oracleId"] not in kept_ids:
                    continue
                ruling_batch.append((row["oracleId"], row["publishedAt"], row["text"]))
                if len(ruling_batch) >= 2000:
                    conn.executemany("INSERT INTO Ruling (oracleId, publishedAt, text) VALUES (?,?,?)", ruling_batch)
                    ruling_count += len(ruling_batch)
                    ruling_batch.clear()
            if ruling_batch:
                conn.executemany("INSERT INTO Ruling (oracleId, publishedAt, text) VALUES (?,?,?)", ruling_batch)
                ruling_count += len(ruling_batch)

        conn.commit()
        conn.execute("VACUUM")
        conn.commit()
    finally:
        conn.close()

    os.replace(tmp_path, out_path)
    return card_count, ruling_count


def main() -> None:
    parser = argparse.ArgumentParser(description="Build cards.db from Scryfall bulk data.")
    parser.add_argument("--force", action="store_true", help="re-download cached bulk files")
    parser.add_argument("--out", default=DEFAULT_OUT, help="output DB path")
    args = parser.parse_args()

    print("Fetching Scryfall bulk-data URLs ...")
    uris = fetch_bulk_urls()
    oracle_path = download(uris["oracle_cards"], os.path.join(CACHE_DIR, "oracle_cards.json"), args.force)
    rulings_path = download(uris["rulings"], os.path.join(CACHE_DIR, "rulings.json"), args.force)

    print("Building DB ...")
    cards, rulings = build_db(oracle_path, rulings_path, args.out)
    size_mb = os.path.getsize(args.out) / (1024 * 1024)
    print(f"Done: {cards} cards, {rulings} rulings, {size_mb:.1f} MB -> {args.out}")


if __name__ == "__main__":
    main()
