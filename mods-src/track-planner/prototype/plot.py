import sys, numpy as np, matplotlib; matplotlib.use("Agg"); import matplotlib.pyplot as plt
fig, axes = plt.subplots(2, 2, figsize=(14, 11), gridspec_kw={"height_ratios":[3,1.3]})
for col, name in enumerate(sys.argv[2:]):
    t = np.loadtxt(f"{name}_terrain.csv", delimiter=",").astype(float); p = np.loadtxt(f"{name}_path.csv", delimiter=",")
    ax = axes[0][col]; land = np.ma.masked_where(t < 0, t)
    ax.imshow(np.where(t < 0, 1, np.nan).T, origin="lower", cmap="Blues", vmin=0, vmax=1.5)
    im = ax.imshow(land.T, origin="lower", cmap="terrain", vmin=40, vmax=200)
    ax.plot(p[:,0], p[:,1], color="crimson", lw=2); ax.plot(p[0,0], p[0,1], "ko"); ax.plot(p[-1,0], p[-1,1], "k*", ms=12)
    ax.set_title(f"Route {name}: plan view (blue = water)"); fig.colorbar(im, ax=ax, shrink=.7, label="ground height")
    d = np.concatenate([[0], np.cumsum(np.hypot(np.diff(p[:,0]), np.diff(p[:,1])))])
    ax = axes[1][col]; ax.fill_between(d, 40, p[:,3], color="tan", label="ground"); ax.plot(d, p[:,2], color="crimson", label="track")
    ax.axhline(62, color="steelblue", lw=.8, ls="--"); ax.set_xlabel("distance along route (blocks)"); ax.set_ylabel("y"); ax.legend(); ax.set_title("height profile")
plt.tight_layout(); plt.savefig(sys.argv[1], dpi=110)
