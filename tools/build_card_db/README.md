# Card DB builder

Builds `app/src/main/assets/cards.db` - a trimmed, FTS5-indexed SQLite of Magic
cards + rulings - from Scryfall bulk data, for offline card/ruling lookup in the app.
The generated DB is gitignored; re-run this script to rebuild it. To actually
**refresh** from the latest Scryfall data, pass `--force` (a plain re-run reuses
the cached download).

## Attribution

Card data from **Scryfall** (https://scryfall.com); follow Scryfall's usage terms.
Card names and Oracle text are (c) Wizards of the Coast and are shown for reference
only. Personal, non-commercial fan project under the WotC Fan Content Policy. Not
affiliated with or endorsed by Wizards of the Coast. No card images are bundled.

## Build

    cd tools/build_card_db
    python3 -m venv .venv
    .venv/bin/pip install -r requirements.txt
    .venv/bin/python build_card_db.py

Options: `--force` (re-download the cached bulk files), `--out PATH` (output DB path).
Gameplay text changes rarely, so a weekly / post-set-release rebuild is plenty.

## Tests

    python3 -m unittest         # pure-function tests, no network or deps
