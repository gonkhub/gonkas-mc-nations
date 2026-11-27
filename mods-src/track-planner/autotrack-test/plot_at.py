import csv, sys
import numpy as np
import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt
from matplotlib.colors import ListedColormap

out = sys.argv[1]
names = sys.argv[2:]
fig, axes = plt.subplots(len(names), 2, figsize=(14, 6.2 * len(names)))
axes = np.atleast_2d(axes)
for row, (path, title) in enumerate(n.split("=") for n in names):
    rows = list(csv.DictReader(open(path)))
    xs = sorted({int(r["x"]) for r in rows}); zs = sorted({int(r["z"]) for r in rows})
    X = {x: i for i, x in enumerate(xs)}; Z = {z: i for i, z in enumerate(zs)}
    before = np.full((len(zs), len(xs)), np.nan); after = np.full_like(before, np.nan); built = np.zeros_like(before)
    for r in rows:
        i, j = Z[int(r["z"])], X[int(r["x"])]
        before[i, j] = int(r["before"]); after[i, j] = int(r["after_top"])
        if r["after_cat_y1"] == "track": built[i, j] = 2
        elif r["after_cat_y"] == "roadbed": built[i, j] = 1
    ext = [xs[0], xs[-1], zs[0], zs[-1]]
    lim = max(8, np.nanpercentile(np.abs(before), 98))
    for col, (data, label) in enumerate([(before, "before: ground height vs. builder"), (after, "after: ground height vs. builder")]):
        ax = axes[row][col]
        im = ax.imshow(data, origin="lower", extent=ext, cmap="BrBG_r", vmin=-lim, vmax=lim)
        if col == 1:
            ax.imshow(np.ma.masked_where(built == 0, built), origin="lower", extent=ext,
                      cmap=ListedColormap(["#9a9a9a", "#d7263d"]), vmin=1, vmax=2, interpolation="nearest")
        ax.plot(0, 0, "ko", ms=6)
        ax.set_title(f"{title}: {label}" + ("  (red = track, grey = roadbed)" if col == 1 else ""), fontsize=10)
        ax.set_xlabel("x offset from builder"); ax.set_ylabel("z offset from builder")
        fig.colorbar(im, ax=ax, shrink=.75, label="blocks above(+)/below(-) builder")
plt.tight_layout(); plt.savefig(out, dpi=100)
