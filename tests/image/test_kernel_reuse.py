import copy
import importlib.util
from pathlib import Path
import unittest

REPO = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location("kernel_reuse", REPO / "image/scripts/reuse-avf-kernel.py")
reuse = importlib.util.module_from_spec(spec)
spec.loader.exec_module(reuse)


class KernelReuseTest(unittest.TestCase):
    def test_only_successful_same_repository_avf_dev_producers_are_accepted(self):
        run = {"conclusion": "success", "head_branch": "dev", "head_sha": "a" * 40,
               "path": ".github/workflows/avf_image.yml", "repository": {"full_name": "nolbo/TERM"},
               "head_repository": {"full_name": "nolbo/TERM"}}
        self.assertEqual(reuse.source_commit(run, "nolbo/TERM"), "a" * 40)
        for field, value in (("conclusion", "failure"), ("head_branch", "main"),
                             ("head_sha", "not-a-commit"), ("path", ".github/workflows/android.yml"),
                             ("repository", {"full_name": "other/repo"}),
                             ("head_repository", {"full_name": "fork/repo"})):
            with self.subTest(field=field), self.assertRaises(ValueError):
                invalid = copy.deepcopy(run)
                invalid[field] = value
                reuse.source_commit(invalid, "nolbo/TERM")


if __name__ == "__main__":
    unittest.main()
