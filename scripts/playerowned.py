"""player-owned.txt, read the same way the in-instance updater reads it (#26).

The pack must never overwrite what belongs to the player: their settings and keybinds
("seed": written only when missing) and their map, waypoint, shader and Distant Horizons
data ("keep": never written or deleted). One list, at the repo root, obeyed by every
script that writes into an instance. Updater.java's Owned class is the twin of this one;
change the pattern rules in both or neither.
"""

import pathlib
import re

ROOT = pathlib.Path(__file__).resolve().parent.parent
FILE = "player-owned.txt"


def _glob(g):
    s = g.replace("\\", "/").rstrip("/")
    out = []
    i = 0
    while i < len(s):
        if s.startswith("**", i):
            out.append(".*")
            i += 2
        elif s[i] == "*":
            out.append("[^/]*")
            i += 1
        else:
            out.append(re.escape(s[i]))
            i += 1
    return re.compile("".join(out) + "(/.*)?", re.IGNORECASE)


class Owned:
    def __init__(self, *texts):
        self.seed_patterns = []
        self.keep_patterns = []
        for t in texts:
            self.add(t)

    def add(self, text):
        for raw in text.splitlines():
            line = raw.strip()
            if not line or line.startswith("#"):
                continue
            parts = line.split(None, 1)
            if len(parts) != 2:
                continue
            if parts[0] == "seed":
                self.seed_patterns.append(_glob(parts[1]))
            elif parts[0] == "keep":
                self.keep_patterns.append(_glob(parts[1]))

    def seed(self, rel):
        return any(p.fullmatch(rel) for p in self.seed_patterns)

    def keep(self, rel):
        return any(p.fullmatch(rel) for p in self.keep_patterns)


def load(extra_text=None):
    """The repo's list, plus (optionally) a release zip's copy, which can only add."""
    texts = [(ROOT / FILE).read_text(encoding="utf-8")]
    if extra_text:
        texts.append(extra_text)
    return Owned(*texts)
