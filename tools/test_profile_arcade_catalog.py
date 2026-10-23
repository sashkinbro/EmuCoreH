"""Verified legacy profile identities must remain available in their actual cabinet scope."""
import hashlib
import json
from pathlib import Path
import sqlite3
import unittest

ASSETS = Path(__file__).resolve().parents[1] / "app/src/main/assets/catalog"
CASES = (
    ("vf4", "naomi2", "Sega Naomi 2", 1552, "Virtua Fighter 4"),
    ("vf4evo", "naomi2", "Sega Naomi 2", 22239, "Virtua Fighter 4: Evolution"),
    ("vf4tuned", "naomi2", "Sega Naomi 2", 39668, "Virtua Fighter 4: Final Tuned"),
    ("dolphin", "awbios", "Sammy Atomiswave", 3114, "Dolphin Blue"),
)


class ProfileArcadeCatalogTest(unittest.TestCase):
    def test_verified_romsets_have_catalog_titles_in_their_cabinet_scope(self):
        registry = json.loads((ASSETS / "arcade_games.json").read_text(encoding="utf-8"))["games"]
        with sqlite3.connect(f"file:{(ASSETS / 'games.db').as_posix()}?mode=ro", uri=True) as db:
            for romset, bios, scope, game_id, title in CASES:
                with self.subTest(romset=romset):
                    self.assertEqual(bios, registry[romset]["bios"])
                    # Same exact-title + platform constraint used by profile catalog lookup.
                    matches = db.execute(
                        "SELECT g.igdb_id FROM games g JOIN game_platforms p USING(igdb_id) "
                        "WHERE g.name=? AND p.platform=?", (title, scope)
                    ).fetchall()
                    self.assertEqual([(game_id,)], matches)

    def test_previous_platform_associations_are_retained(self):
        with sqlite3.connect(f"file:{(ASSETS / 'games.db').as_posix()}?mode=ro", uri=True) as db:
            for game_id, previous_scope in ((1552, "Sega Naomi"), (22239, "Sega Naomi"),
                                           (39668, "Sega Naomi"), (3114, "Dreamcast")):
                self.assertIsNotNone(db.execute(
                    "SELECT 1 FROM game_platforms WHERE igdb_id=? AND platform=?",
                    (game_id, previous_scope)
                ).fetchone())

    def test_published_database_digest_matches_asset(self):
        self.assertEqual((ASSETS / "games.sha256").read_text().strip(),
                         hashlib.sha256((ASSETS / "games.db").read_bytes()).hexdigest())


if __name__ == "__main__":
    unittest.main()
