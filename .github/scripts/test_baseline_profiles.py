from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

from baseline_profiles import validate_profiles


class BaselineProfilesTest(unittest.TestCase):
    def test_accepts_generated_class_and_method_records(self):
        for record in (
            "Lcom/clhs/score/ui/ScoreAppKt;",
            "HSPLcom/clhs/score/MainActivity;->onCreate(Landroid/os/Bundle;)V",
            "SLcom/clhs/score/ui/ScoreAppKt;->ScoreApp()V",
        ):
            with self.subTest(record=record), tempfile.TemporaryDirectory() as directory:
                root = Path(directory)
                for name in ("baseline-prof.txt", "startup-prof.txt"):
                    (root / name).write_text(f"# Generated profile\n{record}\n", encoding="utf-8")
                validate_profiles(root)

    def test_rejects_each_missing_or_unusable_profile(self):
        for name in ("baseline-prof.txt", "startup-prof.txt"):
            for contents in (
                None,
                "",
                "  \n",
                "# HSPLcom/clhs/score/MainActivity;->onCreate()V\n",
                "Landroidx/compose/runtime/Composer;\n",
                "Lother/Owner;->method(Lcom/clhs/score/MainActivity;)V\n",
                "Profile mentions Lcom/clhs/score/MainActivity;\n",
            ):
                with self.subTest(name=name, contents=contents), tempfile.TemporaryDirectory() as directory:
                    root = Path(directory)
                    for filename in ("baseline-prof.txt", "startup-prof.txt"):
                        (root / filename).write_text("Lcom/clhs/score/ui/ScoreAppKt;\n", encoding="utf-8")
                    if contents is None:
                        (root / name).unlink()
                    else:
                        (root / name).write_text(contents, encoding="utf-8")
                    with self.assertRaises((OSError, ValueError)):
                        validate_profiles(root)

    def test_cli_requires_directory_and_rejects_missing_profiles(self):
        script = Path(__file__).with_name("baseline_profiles.py")
        with tempfile.TemporaryDirectory() as directory:
            missing_argument = subprocess.run([sys.executable, str(script)], capture_output=True, text=True)
            missing_profiles = subprocess.run([sys.executable, str(script), directory], capture_output=True, text=True)
            self.assertNotEqual(missing_argument.returncode, 0)
            self.assertNotEqual(missing_profiles.returncode, 0)
            self.assertIn("baseline-prof.txt", missing_profiles.stderr)
