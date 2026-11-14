"""Guards for the temporary Drive-package rollback; not a playback test."""
import hashlib
import json
import tempfile
import unittest
import zipfile
from pathlib import Path

from merge_plugins import index_cs3_files
from tools.stage_prebuilt import copy_if_newer


PACKAGES = {
    "FilmMakinesi": (73, "bd956c2a8800ac228dc58de031b4db9542cc5f895f2817f6ce4b6df4c57dbaf6"),
    "HDFilmCehennemi": (64, "aeb21731b2e61f6cfdb20046425f93cdd563088dca032fc1a9abc7ac3ecbe8c3"),
}


class DriveFallbackPackagesTest(unittest.TestCase):
    def test_drive_code_is_unchanged_and_update_versions_are_correct(self):
        for name, (version, dex_sha256) in PACKAGES.items():
            with self.subTest(name=name), zipfile.ZipFile(Path("prebuilt") / f"{name}.cs3") as package:
                manifest = json.loads(package.read("manifest.json"))
                self.assertEqual(name, manifest["name"])
                self.assertEqual(version, manifest["version"])
                self.assertEqual(dex_sha256, hashlib.sha256(package.read("classes.dex")).hexdigest())

    def test_staging_and_manifest_index_prefer_fallback_over_previous_release(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            for name, (version, _) in PACKAGES.items():
                dest = root / f"{name}.cs3"
                with zipfile.ZipFile(dest, "w") as package:
                    package.writestr("manifest.json", json.dumps({"version": version - 1}))
                self.assertTrue(copy_if_newer(str(Path("prebuilt") / dest.name), str(dest)))
                with zipfile.ZipFile(dest) as package:
                    self.assertEqual(version, json.loads(package.read("manifest.json"))["version"])
                self.assertEqual(str(root), index_cs3_files(str(root))[dest.name])


if __name__ == "__main__":
    unittest.main()
