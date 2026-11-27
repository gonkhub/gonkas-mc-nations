"""Draws run/harness-out/<case>_map.csv + <case>_line.csv: plan view and height profile.

usage: python3 harness_plot.py out.png run/harness-out/c1_autotrack_case1 "title" [more prefix/title pairs]
"""
import csv, os, sys
import numpy as np
import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt

out, pairs = sys.argv[1], list(zip(sys.argv[2::2], sys.argv[3::2]))
fig, axes = plt.subplots(len(pairs), 2, figsize=(15, 5.6 * len(pairs)), gridspec_kw={"width_ratios": [1.15, 1]})
axes = np.atleast_2d(axes)
for row, (prefix, title) in enumerate(pairs):
    line = list(csv.DictReader(open(prefix + "_line.csv")))
    lx = np.array([int(r["x"]) for r in line]); lz = np.array([int(r["z"]) for r in line])
    ty = np.array([float(r["track_y"]) for r in line]); gr = np.array([int(r["ground_real"]) for r in line])
    gp = np.array([int(r["ground_planned"]) for r in line]); wr = np.array([int(r["water_real"]) for r in line])
    ax = axes[row][0]
    if os.path.exists(prefix + "_map.csv"):
        m = list(csv.DictReader(open(prefix + "_map.csv")))
        xs = sorted({int(r["x"]) for r in m}); zs = sorted({int(r["z"]) for r in m})
        X = {v: i for i, v in enumerate(xs)}; Z = {v: i for i, v in enumerate(zs)}
        g = np.full((len(zs), len(xs)), np.nan); wet = np.zeros_like(g)
        for r in m:
            g[Z[int(r["z"])], X[int(r["x"])]] = int(r["ground"])
            wet[Z[int(r["z"])], X[int(r["x"])]] = int(r["water"]) != -999
        im = ax.imshow(np.ma.masked_where(wet > 0, g), origin="lower", extent=[xs[0], xs[-1], zs[0], zs[-1]], cmap="terrain", vmin=40, vmax=140)
        ax.imshow(np.ma.masked_where(wet == 0, wet), origin="lower", extent=[xs[0], xs[-1], zs[0], zs[-1]], cmap="Blues", vmin=0, vmax=1.6)
        fig.colorbar(im, ax=ax, shrink=.8, label="ground y")
    bed = ty - 1
    kind = np.where(wr != -999, 3, np.where(bed > gr + 6, 3, np.where(bed > gr, 1, np.where(bed + 5 < gr, 4, np.where(bed < gr, 2, 0)))))
    colors = {0: "#222222", 1: "#c98b2a", 2: "#7a4fd0", 3: "#d7263d", 4: "#000000"}
    for k, lab in [(0, "on ground"), (1, "embankment"), (2, "cutting"), (3, "bridge"), (4, "tunnel")]:
        sel = kind == k
        if sel.any():
            ax.scatter(lx[sel], lz[sel], s=4, c=colors[k], label=lab, zorder=3)
    ax.plot(0, 0, "ko", ms=7); ax.plot(lx[-1], lz[-1], "k*", ms=12)
    ax.set_title(title + ": plan view (blue = water)", fontsize=10); ax.set_xlabel("x from start"); ax.set_ylabel("z from start")
    ax.legend(loc="best", fontsize=8, markerscale=3); ax.set_aspect("equal")
    ax = axes[row][1]
    d = np.concatenate([[0], np.cumsum(np.hypot(np.diff(lx), np.diff(lz)))])
    ax.fill_between(d, np.minimum(gr, ty.min() - 10) - 5, gr, color="#c9b48a", label="ground (real)")
    if (wr != -999).any():
        ax.fill_between(d, gr, np.where(wr != -999, wr, gr), color="#6fa8dc", label="water")
    ax.plot(d, gp, color="#555555", lw=.8, ls="--", label="ground the planner assumed")
    ax.plot(d, ty, color="#d7263d", lw=1.6, label="track")
    ax.set_title("height profile", fontsize=10); ax.set_xlabel("blocks along the line"); ax.set_ylabel("y"); ax.legend(fontsize=8)
plt.tight_layout(); plt.savefig(out, dpi=95)
