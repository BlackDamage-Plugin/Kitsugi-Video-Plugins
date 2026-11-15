#!/usr/bin/env python3
import unittest
from pathlib import Path
import importlib.util

ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location(
    "fm_close_rapid_probe", ROOT / "tools" / "fm_close_rapid_probe.py"
)
mod = importlib.util.module_from_spec(spec)
assert spec.loader is not None
spec.loader.exec_module(mod)


class AnalyzeFixtures(unittest.TestCase):
    def test_close_fixture_is_player(self) -> None:
        html = (ROOT / "FilmMakinesi/src/test/resources/live/fm_close_1.html").read_text(encoding="utf-8")
        info = mod.analyze(html, "fm-close")
        self.assertEqual("player", info["kind"])
        self.assertTrue(info["jwplayer"] > 0)
        self.assertTrue(info["trap_contentUrl"])

    def test_rapid_fixture_is_player(self) -> None:
        html = (ROOT / "FilmMakinesi/src/test/resources/live/fm_rapid_1.html").read_text(encoding="utf-8")
        info = mod.analyze(html, "fm-rapid")
        self.assertEqual("player", info["kind"])
        self.assertTrue(info["commented_file_link"])

    def test_geo_page(self) -> None:
        html = "<html><body>Content unavailable in your region</body></html>"
        self.assertEqual("geo", mod.analyze(html, "fm-close")["kind"])
        self.assertFalse(mod.valid_body("fm-close", "200", html))

    def test_url_shape_hides_ids(self) -> None:
        self.assertEqual(
            "closeload.filmmakinesi.to/video/embed/<id>/",
            mod.shape_url("https://closeload.filmmakinesi.to/video/embed/RFeVmBTmaCG/?imdb_id=tt0460681"),
        )


if __name__ == "__main__":
    unittest.main()
