import json
import io
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
import zipfile

from deduplicate import ant_pattern, generate


class DeduplicateTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.source = self.root / "input"
        self.source.mkdir()
        self.output = self.root / "output"

    def document(self, path, body):
        target = self.source / path
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_bytes(body)

    def index(self, output=None):
        return json.loads(((output or self.output) / "licenses/index.json").read_bytes())

    def test_only_exact_bytes_share_storage_and_every_alias_retains_its_notice(self):
        original = {
            "licenses/a/LICENSE": b"Copyright Alice\nMIT\n",
            "licenses/b/LICENSE": b"Copyright Alice\nMIT\n",
            "licenses/c/LICENSE": b"Copyright Bob\nMIT\n",
            "licenses/d/LICENSE": b"Copyright Alice\r\nMIT\r\n",
            "licenses/e/LICENSE": b"Copyright Alice\nMIT",
            "licenses/f/LICENSE": b"\xef\xbb\xbfCopyright Alice\nMIT\n",
            "licenses/a/NOTICE": b"Separate attribution\x00\xff\n",
        }
        for path, body in original.items():
            self.document(path, body)
        generate(self.source, self.output)
        entries = dict(self.index()["documents"])
        self.assertEqual(set(original), set(entries))
        self.assertEqual(len(original) - 1, len(set(entries.values())))
        for path, body in original.items():
            self.assertEqual(body, (self.output / "licenses/blobs" / (entries[path] + ".txt")).read_bytes())
            self.assertEqual(body, (self.source / path).read_bytes())

    def test_java_resource_paths_and_notices_are_retained_without_unrelated_resources(self):
        body = b"Copyright library\nApache license\n"
        self.document("licenses/component/LICENSE", body)
        jar = self.root / "resources.jar"
        resource = "META-INF/library/module/LICENSE.txt"
        with zipfile.ZipFile(jar, "w") as archive:
            archive.writestr(resource, body)
            archive.writestr("META-INF/library/module/NOTICE", b"Extra notice")
            archive.writestr("META-INF/services/provider", b"Not a license")
        directory = self.root / "java-resources"
        directory.joinpath("META-INF/other").mkdir(parents=True)
        directory.joinpath("META-INF/other/COPYING.md").write_bytes(body)
        generate(self.source, self.output, [jar], [directory])
        entries = dict(self.index()["documents"])
        self.assertEqual(4, len(entries))
        self.assertEqual(entries["licenses/component/LICENSE"], entries["licenses/java-resources/" + resource])
        self.assertEqual(2, len(set(entries.values())))

    def test_output_is_deterministic(self):
        self.document("licenses/z", b"z")
        self.document("licenses/a", b"a")
        generate(self.source, self.output)
        second = self.root / "second"
        generate(self.source, second)
        for path in self.output.rglob("*"):
            if path.is_file():
                self.assertEqual(path.read_bytes(), (second / path.relative_to(self.output)).read_bytes())

    def test_aar_notices_include_classes_and_embedded_library_jars(self):
        self.document("licenses/app", b"app license")
        contents = io.BytesIO()
        with zipfile.ZipFile(contents, "w") as jar:
            jar.writestr("META-INF/library/LICENSE.txt", b"library license")
        archive = self.root / "library.aar"
        with zipfile.ZipFile(archive, "w") as aar:
            aar.writestr("classes.jar", contents.getvalue())
            aar.writestr("libs/embedded.jar", contents.getvalue())
        generate(self.source, self.output, [archive])
        self.assertEqual(2, len(self.index()["documents"]))

    def test_existing_packaging_exclusions_still_apply_to_java_notices(self):
        self.document("licenses/app", b"app license")
        jars = [self.root / "one.jar", self.root / "two.jar"]
        for i, jar in enumerate(jars):
            with zipfile.ZipFile(jar, "w") as archive:
                archive.writestr("META-INF/LICENSE.txt", str(i))
                archive.writestr("META-INF/kept/LICENSE.txt", b"kept")
                archive.writestr("META-INF/nested/NOTICE", b"excluded")
        generate(self.source, self.output, jars, java_excludes=["/META-INF/LICENSE*", "**/NOTICE"])
        self.assertEqual(2, len(self.index()["documents"]))
        for pattern, path, matches in [
            ("META-INF/**/LICENSE*", "META-INF/LICENSE.txt", True),
            ("META-INF/**/LICENSE*", "META-INF/a/b/LICENSE-MIT", True),
            ("META-INF/LICENSE*", "META-INF/a/LICENSE.txt", False),
            ("**/NOTICE?txt", "META-INF/a/NOTICE.txt", True),
        ]:
            self.assertEqual(matches, ant_pattern(pattern).fullmatch(path) is not None)

    def test_hash_collision_fails_instead_of_collapsing_different_bytes(self):
        self.document("licenses/a", b"a")
        self.document("licenses/b", b"b")
        with patch("deduplicate.hashlib.sha256") as digest:
            digest.return_value.hexdigest.return_value = "0" * 64
            with self.assertRaisesRegex(ValueError, "collision"):
                generate(self.source, self.output)
        self.assertFalse(self.output.exists())

    def test_conflicting_java_resources_fail_instead_of_dropping_attribution(self):
        jars = [self.root / "one.jar", self.root / "two.jar"]
        for index, jar in enumerate(jars):
            with zipfile.ZipFile(jar, "w") as archive:
                archive.writestr("META-INF/component/LICENSE", str(index))
        with self.assertRaisesRegex(ValueError, "Conflicting"):
            generate(self.source, self.output, jars)

    def test_invalid_paths_empty_input_and_stale_output_fail(self):
        with self.assertRaisesRegex(ValueError, "No bundled"):
            generate(self.source, self.output)
        jar = self.root / "unsafe.jar"
        with zipfile.ZipFile(jar, "w") as archive:
            archive.writestr("META-INF/../LICENSE", b"notice")
        with self.assertRaisesRegex(ValueError, "Invalid"):
            generate(self.source, self.output, [jar])
        self.document("licenses/a", b"a")
        generate(self.source, self.output)
        with self.assertRaises(FileExistsError):
            generate(self.source, self.output)


if __name__ == "__main__":
    unittest.main()
