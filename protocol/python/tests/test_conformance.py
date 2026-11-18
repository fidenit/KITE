"""Runs the protocol's conformance cases (../conformance) and the examples against the Python implementation.

    python -m unittest discover -s python/tests        (from the repository root)
"""

import contextlib
import io
import re
import sys
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "python"))

from patet import VERSION, Board, LineStream, join_cells, parts_table, serialize, split_cells  # noqa: E402
from patet.__main__ import main  # noqa: E402


def read(path):
    return path.read_text(encoding="utf-8").replace("\r\n", "\n")


def expand(s):
    """`<N c>` stands for the character c repeated N times."""
    return re.sub(r"<([0-9]+) (.)>", lambda m: m.group(2) * int(m.group(1)), s)


def tidy(s):
    """Trailing spaces are not compared (an empty title leaves one, and editors strip them from case files)."""
    return "\n".join(line.rstrip() for line in s.split("\n")).rstrip()


class Conformance(unittest.TestCase):
    maxDiff = None

    def test_conformance_cases(self):
        files = sorted((ROOT / "conformance").glob("*.txt"))
        self.assertGreaterEqual(len(files), 8, "expected the conformance cases")
        lines = 0
        for path in files:
            with self.subTest(case=path.name):
                lines += self.run_case(path.name, read(path))
        print(f"ok: {len(files)} conformance cases ({lines} lines)", file=sys.stderr)

    def run_case(self, name, text):
        script, found, want = ("\n" + text).partition("\nboard:\n")
        self.assertTrue(found, f"{name}: no board: section")
        script = script.split("\n")[1:]
        board = Board()
        ran = 0
        i = 0
        while i < len(script):
            line = script[i]
            i += 1
            by = "agent" if line.startswith("agent:") else "user" if line.startswith("user:") else None
            if by is None:
                self.assertTrue(line.startswith("#") or line.strip() == "", f"{name}:{i}: not a case line: {line}")
                continue
            # The op is taken as written (one leading space separates it from the author), so cases can test trimming.
            op = line[line.index(":") + 1:]
            op = op[1:] if op.startswith(" ") else op
            self.assertLess(i, len(script), f"{name}:{i}: no verdict after: {line}")
            expected = script[i].strip()
            i += 1
            self.assertTrue(expected.startswith("-> "), f"{name}:{i}: no verdict after: {line}")
            expected = expected[3:]
            got = board.line(expand(op), by)
            # A rejection is matched on its code; a case that also writes the message is matched in full.
            if got.startswith("err ") and len(expected.split(" ")) <= 2:
                got = " ".join(got.split(" ")[:2])
            self.assertEqual(got, expected, f"{name}:{i - 1}: {line}")
            ran += 1
        got = serialize(board)
        self.assertEqual(tidy(got), tidy(expand(want)), f"{name}: the board at the end")
        # What was written back rebuilds the same board when the user replays it.
        again = Board()
        for l in got.split("\n"):
            if l:
                self.assertEqual(again.line(l, "user"), "ok", f"{name}: replay of '{l}'")
        self.assertEqual(serialize(again), got, f"{name}: replaying the board")
        return ran

    def test_examples_apply_without_a_rejection(self):
        files = sorted((ROOT / "examples").glob("*.ops"))
        self.assertGreaterEqual(len(files), 4)
        for path in files:
            board = Board()
            for n, l in enumerate(read(path).split("\n"), 1):
                who, tab, op = l.partition("\t")
                if not tab or who == "T":
                    continue
                self.assertIn(board.line(op, "user" if who == "U" else "agent"), ("ok", "skip"), f"{path.name}:{n}: {l}")
            # The board an example builds survives being written and read back.
            text = serialize(board)
            again = Board()
            for l in text.splitlines():
                self.assertEqual(again.line(l, "user"), "ok", f"{path.name}: replay of '{l}'")
            self.assertEqual(serialize(again), text, f"{path.name}: round trip")

    def test_guide_table_is_this_table(self):
        guide = read(ROOT / "GUIDE.md")
        self.assertIn("<!-- parts:begin -->\n", guide)
        table = guide.split("<!-- parts:begin -->\n")[1].split("<!-- parts:end -->")[0]
        self.assertEqual(table, parts_table(), "the card type table differs from GUIDE.md")

    def test_cells_and_streams(self):
        self.assertEqual(split_cells("a | b\\|c | d"), ["a", "b|c", "d"])
        self.assertEqual(split_cells("C:\\dir\\ | x"), ["C:\\dir\\", "x"])
        self.assertEqual(split_cells(" | "), ["", ""])
        self.assertEqual(join_cells(["a|b", "c"]), "a\\|b | c")
        s = LineStream()
        got = s.feed("topic t A\nset k te") + s.feed("xt hi\r\nsay en") + s.feed("d")
        self.assertEqual(got, ["topic t A", "set k text hi"])
        self.assertEqual(s.partial, "say end")
        self.assertEqual(s.finish(), "say end")
        self.assertIsNone(s.finish())

    def test_limits_count_characters(self):
        b = Board()
        for l in ("topic t a", "card k note t x"):
            self.assertEqual(b.line(l), "ok")
        self.assertEqual(b.line("set k text " + "가" * 240), "ok")
        self.assertEqual(b.line("set k text " + "\U0001F600" * 241), "ok clipped k.text 240")
        self.assertEqual(len(b.card("k").fields["text"]), 240)
        # A rejected op leaves no note for the next verdict.
        self.assertTrue(b.line("upd k i1 " + "x" * 61).startswith("err no-items "))
        self.assertEqual(b.line("say hi"), "ok")

    def cli(self, *argv, stdin=None):
        out, err = io.StringIO(), io.StringIO()
        old = sys.stdin
        if stdin is not None:
            sys.stdin = io.TextIOWrapper(io.BytesIO(stdin.encode("utf-8")), encoding="utf-8")
        try:
            with contextlib.redirect_stdout(out), contextlib.redirect_stderr(err):
                code = main(list(argv))
        finally:
            sys.stdin = old
        return code, out.getvalue(), err.getvalue()

    def test_command_line(self):
        todo = str(ROOT / "examples" / "todo.ops")
        code, out, _ = self.cli("check", todo)
        self.assertEqual((code, out.count("\n")), (0, 1), out)
        self.assertRegex(out, r"^\d+ applied \(0 clipped\), 0 rejected\n$")
        code, out, _ = self.cli("show", todo)
        self.assertEqual(code, 0)
        self.assertTrue(out.startswith("topic move Moving Day\ncard k1 checklist move 3x3 Before the truck comes\n"), out)
        plain = f"# patet {VERSION}\ntopic t A\ncard k gauge t X\ncard k note t {'x' * 41}\nU\tpin k\npin k\n"
        code, out, _ = self.cli("check", "-", stdin=plain)
        self.assertEqual(code, 1)
        self.assertEqual(
            [l for l in out.splitlines() if not l.startswith("    ")],
            ["3: err unknown-kind no card type 'gauge'", "4: ok clipped k.title 40", "6: err user-only only the user can pin or unpin", "3 applied (1 clipped), 2 rejected"],
        )
        code, out, _ = self.cli("check", "-", stdin="# patet 1.0\ntopic t A\n")
        self.assertEqual(code, 1)
        self.assertTrue(out.startswith("1: this is patet "), out)
        self.assertEqual(self.cli("frob")[0], 2)


if __name__ == "__main__":
    unittest.main()
