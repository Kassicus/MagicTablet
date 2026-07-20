import json
import unittest

from build_card_db import extract_card, extract_ruling, should_include


class ShouldIncludeTest(unittest.TestCase):
    def test_includes_normal_card(self):
        self.assertTrue(should_include({"oracle_id": "x", "name": "Llanowar Elves", "layout": "normal"}))

    def test_excludes_token(self):
        self.assertFalse(should_include({"oracle_id": "x", "name": "Soldier", "layout": "token"}))

    def test_excludes_double_faced_token(self):
        self.assertFalse(should_include({"oracle_id": "x", "name": "T", "layout": "double_faced_token"}))

    def test_excludes_emblem(self):
        self.assertFalse(should_include({"oracle_id": "x", "name": "Emblem", "layout": "emblem"}))

    def test_excludes_art_series(self):
        self.assertFalse(should_include({"oracle_id": "x", "name": "Art", "layout": "art_series"}))

    def test_excludes_alchemy_prefix(self):
        self.assertFalse(should_include({"oracle_id": "x", "name": "A-Faceless Haven", "layout": "normal"}))

    def test_excludes_missing_oracle_id(self):
        self.assertFalse(should_include({"name": "No Id", "layout": "normal"}))


class ExtractCardTest(unittest.TestCase):
    def test_simple_card(self):
        row = extract_card({
            "oracle_id": "abc", "name": "Lightning Bolt", "mana_cost": "{R}",
            "type_line": "Instant", "oracle_text": "Lightning Bolt deals 3 damage to any target.",
            "keywords": [],
        })
        self.assertEqual(row["oracleId"], "abc")
        self.assertEqual(row["name"], "Lightning Bolt")
        self.assertEqual(row["manaCost"], "{R}")
        self.assertEqual(row["typeLine"], "Instant")
        self.assertIn("3 damage", row["oracleText"])
        self.assertEqual(json.loads(row["keywords"]), [])

    def test_dfc_joins_faces(self):
        row = extract_card({
            "oracle_id": "dfc", "name": "Front // Back", "mana_cost": "",
            "type_line": "", "oracle_text": None,
            "card_faces": [
                {"mana_cost": "{G}", "type_line": "Creature - Elf", "oracle_text": "Front text."},
                {"mana_cost": "", "type_line": "Land", "oracle_text": "Back text."},
            ],
            "keywords": [],
        })
        self.assertEqual(row["name"], "Front // Back")
        self.assertEqual(row["manaCost"], "{G}")
        self.assertEqual(row["typeLine"], "Creature - Elf // Land")
        self.assertEqual(row["oracleText"], "Front text.\n//\nBack text.")

    def test_missing_oracle_text_becomes_empty(self):
        row = extract_card({"oracle_id": "x", "name": "Weird", "keywords": []})
        self.assertEqual(row["oracleText"], "")


class ExtractRulingTest(unittest.TestCase):
    def test_maps_comment_to_text(self):
        row = extract_ruling({"oracle_id": "abc", "published_at": "2020-01-01", "comment": "It works."})
        self.assertEqual(row, {"oracleId": "abc", "publishedAt": "2020-01-01", "text": "It works."})


if __name__ == "__main__":
    unittest.main()
