#!/usr/bin/env python3
"""Граф входов наборов — кто чей вход, по паспортам <код>/dataset.properties (только стандартная библиотека).

    python3 -X utf8 -I tools/graph.py              — проверка графа и README, порядок сборки
    python3 -X utf8 -I tools/graph.py --after КОД  — кого проверить и пересобрать после нового выпуска набора КОД
    python3 -X utf8 -I tools/graph.py --mermaid    — граф для README (Mermaid)
    python3 -X utf8 -I tools/graph.py --readme     — переписать граф в README.md (между отметками)

Вход набора — одно из трёх:
  * выпуск другого набора: input.<имя>.release = его version, сумма — input.<имя>.sha256_tsv (содержимое .tsv.gz:
    выпуск можно пережать, сумма останется);
  * свой снимок источников: <код>-source-<ГГГГММДД>, сумма — input.<имя>.sha256 (файл как есть);
  * ссылка: input.<имя>.url, сумма — любая из двух.
Кругов нет: набор не берёт свой выпуск ни прямо, ни через другие наборы. Код 0 — всё в порядке, 1 — есть замечания.
"""
import heapq
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
BEGIN = "<!-- граф входов: python3 -X utf8 -I tools/graph.py --readme -->"
END = "<!-- /граф входов -->"


class Input:
    def __init__(self, name, props):
        self.name = name
        self.file = props.get(f"input.{name}.file", "")
        self.release = props.get(f"input.{name}.release", "")
        self.url = props.get(f"input.{name}.url", "")
        self.sha256 = props.get(f"input.{name}.sha256", "")
        self.sha256_tsv = props.get(f"input.{name}.sha256_tsv", "")


class Dataset:
    def __init__(self, folder, props):
        self.folder = folder
        self.code = props.get("code", "")
        self.title = props.get("title", "")
        self.version = props.get("version", "")
        self.inputs = [Input(n, props) for n in props.get("inputs", "").split()]


def read_passport(path):
    """Паспорт — строки «ключ=значение», «#» — комментарий; ключ дважды — ошибка (как у tools/ci/passport.sh —
    первое значение, но молча это не пропускаем)."""
    props, problems = {}, []
    for n, line in enumerate(path.read_text(encoding="utf-8").splitlines(), 1):
        line = line.rstrip("\r")
        if not line.strip() or line.lstrip().startswith("#"):
            continue
        if "=" not in line:
            problems.append(f"{path.parent.name}/{path.name}:{n}: не «ключ=значение»: {line}")
            continue
        k, v = line.split("=", 1)
        if k in props:
            problems.append(f"{path.parent.name}/{path.name}:{n}: ключ {k} — второй раз")
            continue
        props[k] = v
    return props, problems


def load(root=ROOT):
    """Наборы — папки верхнего уровня с dataset.properties, по коду."""
    datasets, problems = [], []
    for p in sorted(root.glob("*/dataset.properties")):
        props, pr = read_passport(p)
        problems += pr
        datasets.append(Dataset(p.parent.name, props))
    return datasets, problems


class Graph:
    """Рёбра «набор-вход → набор» с именами входов; свои снимки и ссылки — внешние входы, не рёбра."""

    def __init__(self, datasets):
        self.datasets = {d.code: d for d in datasets}
        self.problems = []
        self.edges = {}  # (откуда, куда) → [вход, …]
        by_version = {}
        for d in datasets:
            where = f"{d.folder}/dataset.properties"
            if d.code != d.folder:
                self.problems.append(f"{where}: code={d.code}, а папка — {d.folder}")
            if not re.fullmatch(rf"{re.escape(d.folder)}-data-v[1-9][0-9]*", d.version):
                self.problems.append(f"{where}: version={d.version} — нужно {d.folder}-data-v<N>")
            if d.version in by_version:
                self.problems.append(f"{where}: version={d.version} — та же, что у {by_version[d.version]}")
            by_version.setdefault(d.version, d.code)
        for d in datasets:
            where = f"{d.folder}/dataset.properties"
            for i in d.inputs:
                what = f"{where}: вход {i.name}"
                if not re.fullmatch(r"[A-Za-z0-9._-]+", i.file):
                    self.problems.append(f"{what}: input.{i.name}.file=«{i.file}» — нужно имя файла")
                if bool(i.release) == bool(i.url):
                    self.problems.append(f"{what}: нужен ровно один из input.{i.name}.release и input.{i.name}.url")
                    continue
                if i.url:
                    if not (i.sha256 or i.sha256_tsv):
                        self.problems.append(f"{what}: нет суммы (input.{i.name}.sha256_tsv или .sha256)")
                    continue
                if i.release in by_version:
                    src = by_version[i.release]
                    if not i.sha256_tsv or i.sha256:
                        self.problems.append(f"{what}: выпуск набора {src} — сумма содержимого input.{i.name}.sha256_tsv "
                                             f"(и без .sha256)")
                    self.edges.setdefault((src, d.code), []).append(i.name)
                elif re.fullmatch(rf"{re.escape(d.code)}-source-[0-9]{{8}}", i.release):
                    if not i.sha256 or i.sha256_tsv:
                        self.problems.append(f"{what}: снимок источников — сумма файла input.{i.name}.sha256 "
                                             f"(и без .sha256_tsv)")
                else:
                    self.problems.append(f"{what}: выпуск {i.release} — ни выпуск набора из этого репозитория, "
                                         f"ни свой снимок {d.code}-source-<ГГГГММДД>")
        cycle = self.cycle()
        if cycle:
            self.problems.append("круг входов: " + " → ".join(cycle) + " (набор не может зависеть от себя)")

    def consumers(self, code):
        return sorted({b for (a, b) in self.edges if a == code})

    def producers(self, code):
        return sorted({a for (a, b) in self.edges if b == code})

    def cycle(self):
        """Первый найденный круг — список кодов от начала до него же; нет — None."""
        state = {}
        for start in sorted(self.datasets):
            if start in state:
                continue
            stack, path = [(start, iter(self.consumers(start)))], [start]
            state[start] = 1
            while stack:
                node, it = stack[-1]
                nxt = next(it, None)
                if nxt is None:
                    state[node] = 2
                    stack.pop()
                    path.pop()
                elif state.get(nxt) == 1:
                    return path[path.index(nxt):] + [nxt]
                elif nxt not in state:
                    state[nxt] = 1
                    stack.append((nxt, iter(self.consumers(nxt))))
                    path.append(nxt)
        return None

    def order(self):
        """Порядок сборки: сначала входы; среди готовых — по коду. С кругом — None."""
        need = {c: len(self.producers(c)) for c in self.datasets}
        ready = [c for c, n in need.items() if n == 0]
        heapq.heapify(ready)
        out = []
        while ready:
            c = heapq.heappop(ready)
            out.append(c)
            for b in self.consumers(c):
                need[b] -= 1
                if need[b] == 0:
                    heapq.heappush(ready, b)
        return out if len(out) == len(self.datasets) else None

    def after(self, code):
        """Наборы, которые берут выпуск code прямо или через другие, — в порядке сборки."""
        seen, todo = set(), [code]
        while todo:
            for b in self.consumers(todo.pop()):
                if b not in seen:
                    seen.add(b)
                    todo.append(b)
        return [c for c in (self.order() or []) if c in seen]

    def mermaid(self):
        order = self.order() or sorted(self.datasets)
        lines = ["```mermaid", "flowchart LR"]
        for c in order:
            title = self.datasets[c].title.replace('"', "'")
            lines.append(f'  {c}["{c} · {title}"]')
        for (a, b) in sorted(self.edges, key=lambda e: (order.index(e[1]), order.index(e[0]))):
            names = ", ".join(sorted(self.edges[(a, b)]))
            lines.append(f'  {a} -- "{names}" --> {b}')
        lines.append("```")
        return "\n".join(lines) + "\n"


def plural(n, one, few, many):
    """«1 связь», «4 связи», «5 связей»."""
    k = n % 100
    word = many if 11 <= k <= 14 else one if n % 10 == 1 else few if 2 <= n % 10 <= 4 else many
    return f"{n} {word}"


def readme_block(text):
    """Граф в README между отметками BEGIN и END; отметок нет (или не по разу) — None."""
    if text.count(BEGIN) != 1 or text.count(END) != 1:
        return None
    a, b = text.index(BEGIN) + len(BEGIN), text.index(END)
    return text[a:b].strip("\n") + "\n" if a <= b else None


def with_readme_block(text, block):
    a, b = text.index(BEGIN) + len(BEGIN), text.index(END)
    return text[:a] + "\n" + block + text[b:]


def main(argv, root=ROOT, out=sys.stdout):
    datasets, problems = load(root)
    g = Graph(datasets)
    problems += g.problems
    readme = root / "README.md"
    if argv[:1] == ["--mermaid"] and len(argv) == 1:
        out.write(g.mermaid())
        return 0
    if argv[:1] == ["--readme"] and len(argv) == 1:
        if problems:
            for p in problems:
                print("⛔ " + p, file=out)
            return 1
        text = readme.read_text(encoding="utf-8")
        if readme_block(text) is None:
            print(f"⛔ README.md: нет отметок {BEGIN} … {END} (по одной)", file=out)
            return 1
        readme.write_text(with_readme_block(text, g.mermaid()), encoding="utf-8", newline="\n")
        print("✅ граф в README.md переписан", file=out)
        return 0
    if argv[:1] == ["--after"] and len(argv) == 2:
        code = argv[1]
        if code not in g.datasets:
            print(f"⛔ нет набора {code}", file=out)
            return 1
        if problems:
            for p in problems:
                print("⛔ " + p, file=out)
            return 1
        todo = g.after(code)
        if not todo:
            print(f"выпуск {g.datasets[code].version} — ничей не вход: пересобирать нечего", file=out)
            return 0
        print(f"после нового выпуска {g.datasets[code].version} — по порядку; набор пересобрать, если сумма "
              f"его входа изменилась (иначе он и всё после него через него — как были):", file=out)
        for n, c in enumerate(todo, 1):
            via = []
            for p in g.producers(c):
                d = g.datasets[p]
                for name in sorted(g.edges[(p, c)]):
                    i = next(x for x in g.datasets[c].inputs if x.name == name)
                    via.append(f"{name} ({i.file} из {d.version})")
            print(f"  {n}. {c} — вход {', '.join(via)}", file=out)
        return 0
    if argv:
        print(__doc__.strip(), file=out)
        return 2
    if readme.is_file():
        block = readme_block(readme.read_text(encoding="utf-8"))
        if block is None:
            problems.append(f"README.md: нет отметок графа входов ({BEGIN} … {END})")
        elif not g.problems and block != g.mermaid():
            problems.append("README.md: граф входов устарел — python3 -X utf8 -I tools/graph.py --readme")
    if problems:
        for p in problems:
            print("⛔ " + p, file=out)
        return 1
    order = g.order()
    print(f"✅ граф входов: {plural(len(datasets), 'набор', 'набора', 'наборов')}, "
          f"{plural(len(g.edges), 'связь', 'связи', 'связей')}, кругов нет; README — как граф", file=out)
    print("порядок сборки: " + " → ".join(order), file=out)
    for c in order:
        ins = []
        for i in g.datasets[c].inputs:
            ins.append(f"{i.name} ← {i.release or 'ссылка'}")
        print(f"  {c}: " + ("; ".join(ins) if ins else "без входов"), file=out)
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
