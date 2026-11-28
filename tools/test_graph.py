"""Тесты tools/graph.py: python3 -X utf8 -I -m unittest discover -s tools -p 'test_*.py'"""
import io
import tempfile
import unittest
from pathlib import Path

import graph

SUM = "0" * 64


def passport(code, *inputs, version=None):
    lines = [f"code={code}", f"title=Набор {code}", f"version={version or code + '-data-v1'}"]
    lines.append("inputs=" + " ".join(i[0] for i in inputs))
    for name, release, kind in inputs:
        lines.append(f"input.{name}.file={name}.tsv.gz")
        if release.startswith("http"):
            lines.append(f"input.{name}.url={release}")
        else:
            lines.append(f"input.{name}.release={release}")
        lines.append(f"input.{name}.{kind}={SUM}")
    return "\n".join(lines) + "\n"


class GraphTest(unittest.TestCase):
    def repo(self, passports, readme=True):
        root = Path(self.tmp.name)
        for code, text in passports.items():
            (root / code).mkdir()
            (root / code / "dataset.properties").write_text(text, encoding="utf-8")
        if readme:
            (root / "README.md").write_text(f"# r\n\n{graph.BEGIN}\n{graph.END}\n", encoding="utf-8")
        return root

    def run_main(self, root, *argv):
        out = io.StringIO()
        rc = graph.main(list(argv), root=root, out=out)
        return rc, out.getvalue()

    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()

    def tearDown(self):
        self.tmp.cleanup()

    def chain(self):
        return {
            "wld": passport("wld", ("source", "wld-source-20260101", "sha256")),
            "air": passport("air", ("dump", "air-source-20250901", "sha256"), ("cities", "wld-data-v1", "sha256_tsv"),
                            ("countries", "wld-data-v1", "sha256_tsv")),
            "sto": passport("sto", ("airports", "air-data-v1", "sha256_tsv")),
            "mov": passport("mov", ("cities", "wld-data-v1", "sha256_tsv"), ("x", "https://example.org/x", "sha256")),
            "auc": passport("auc", ("cities", "sto-data-v1", "sha256_tsv")),
        }

    def test_chain_order_after_and_readme(self):
        root = self.repo(self.chain())
        rc, out = self.run_main(root)
        self.assertEqual(1, rc, out)
        self.assertIn("граф входов устарел", out)
        self.assertEqual(0, self.run_main(root, "--readme")[0])
        rc, out = self.run_main(root)
        self.assertEqual(0, rc, out)
        self.assertIn("порядок сборки: wld → air → mov → sto → auc", out)
        self.assertIn("4 связи", out)
        rc, out = self.run_main(root, "--after", "air")
        self.assertEqual(0, rc, out)
        self.assertIn("1. sto — вход airports (airports.tsv.gz из air-data-v1)", out)
        self.assertIn("2. auc — вход cities (cities.tsv.gz из sto-data-v1)", out)
        self.assertEqual(0, self.run_main(root, "--after", "mov")[0])
        self.assertIn("пересобирать нечего", self.run_main(root, "--after", "mov")[1])
        g = graph.Graph(graph.load(root)[0])
        self.assertEqual(["air", "mov", "sto", "auc"], g.after("wld"))
        self.assertIn('  wld -- "cities, countries" --> air', g.mermaid())

    def test_cycle(self):
        p = self.chain()
        p["wld"] = passport("wld", ("airports", "sto-data-v1", "sha256_tsv"))
        root = self.repo(p)
        g = graph.Graph(graph.load(root)[0])
        self.assertIsNone(g.order())
        self.assertEqual(["air", "sto", "wld", "air"], g.cycle())
        rc, out = self.run_main(root)
        self.assertEqual(1, rc)
        self.assertIn("круг входов: air → sto → wld → air", out)

    def test_own_release_is_a_cycle(self):
        p = self.chain()
        p["sto"] = passport("sto", ("airports", "air-data-v1", "sha256_tsv"), ("me", "sto-data-v1", "sha256_tsv"))
        rc, out = self.run_main(self.repo(p))
        self.assertEqual(1, rc)
        self.assertIn("круг входов: sto → sto", out)

    def test_inputs_rules(self):
        p = self.chain()
        p["sto"] = passport("sto", ("airports", "air-data-v1", "sha256"))
        p["mov"] = passport("mov", ("src", "wld-source-20260101", "sha256"), ("old", "air-data-v0", "sha256_tsv"))
        p["auc"] = passport("auc", ("src", "auc-source-20260101", "sha256_tsv"), version="auc-data-1")
        rc, out = self.run_main(self.repo(p))
        self.assertEqual(1, rc)
        self.assertIn("sto/dataset.properties: вход airports: выпуск набора air — сумма содержимого", out)
        self.assertIn("mov/dataset.properties: вход src: выпуск wld-source-20260101 — ни выпуск набора", out)
        self.assertIn("mov/dataset.properties: вход old: выпуск air-data-v0 — ни выпуск набора", out)
        self.assertIn("auc/dataset.properties: вход src: снимок источников — сумма файла", out)
        self.assertIn("auc/dataset.properties: version=auc-data-1", out)

    def test_passport_shape(self):
        p = self.chain()
        p["auc"] = "code=auk\nversion=auc-data-v1\nversion=auc-data-v2\ninputs=\n"
        rc, out = self.run_main(self.repo(p))
        self.assertEqual(1, rc)
        self.assertIn("auc/dataset.properties:3: ключ version — второй раз", out)
        self.assertIn("auc/dataset.properties: code=auk, а папка — auc", out)

    def test_this_repository(self):
        rc, out = self.run_main(graph.ROOT)
        self.assertEqual(0, rc, out)


if __name__ == "__main__":
    unittest.main()
