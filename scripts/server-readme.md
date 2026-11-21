# Gonka's MC Nations — server upload

Everything in this folder **mirrors the server root**. In FileZilla, drag the *contents* of this
folder (not the folder itself) into the remote pane.

`PACKAGE.md` next to this file has the generated facts: loader version, mod count, size, the
client-only mods left out, and anything to delete. **Read `DELETE-FIRST.txt` before your first
upload.**

---

## 1. The previous server's files are the main risk

Not a tidiness problem — a crash-on-load problem. The old server ran a **different pack**:

| | |
|---|---|
| Mods on the old server | 107 |
| Mods in this pack | 134 |
| Same filename in both | 61 |
| **On the old server and not in this pack at all** | **46** |
| Loader | **21.1.243 → 21.1.265** |

Two of those rows are the ones that bite:

- **The 46 orphans include structure mods** (Additional Structures, Moog's Bountiful Structures,
  Cataclysm, Apotheosis and its family). Structure mods change **world generation**. Left in
  `mods/`, they would generate into your brand new survival world — and once a chunk is
  generated, removing the mod leaves the structures behind as broken blocks.
- **Several mods appear in both at different versions** — Create Dragons Plus 1.11.2b vs 1.11.9,
  Farmer's Delight 1.3.2 vs 1.3.4, Quark 482 vs 488, Distant Horizons 3.2.0 vs 3.3.3. Uploading
  over the old folder leaves **both jars**, two of them claiming one mod id. That is a hard crash
  before the server finishes loading, and the log blames the mod rather than the upload.

**So: delete, do not merge.** `DELETE-FIRST.txt` lists exactly which directories to remove and
which to leave alone. The short version:

| Delete on the server | Keep |
|---|---|
| `mods/` `config/` `defaultconfigs/` `kubejs/` `blueprints/` `moonlight-global-datapacks/` `local/` | `server.properties`, `ops.json`, `whitelist.json`, `banned-*.json`, `eula.txt` |
| the caches: `.mixin.out/` `dynamic-*-cache/` `coremods/` | `libraries/`, and the start script your host provides |
| | **`lookscanbethisevening/` — the old world. No local backup. Do not touch it.** |

### The old world — do not touch it

**`lookscanbethisevening` stays exactly where it is.** It is the old server's world and there is
**no local backup of it**, which makes it the only irreplaceable thing in this migration.

You do not need to move it, rename it, or delete it. Change one line in `server.properties`:

```
level-name=nations
```

The server then generates a **new** world in a folder called `nations`, beside the old one. The
old directory is never read, never written and never moved — which is why this is the approach
rather than renaming: a rename is a write to the one thing that cannot be recovered if it fails
half-way over FTP.

Nothing in `DELETE-FIRST.txt` is a world folder, and it carries a warning banner naming this
world specifically.

**Back it up when convenient, not as a blocker.** Download `lookscanbethisevening/` over FTP to
`F:\\gonkas-backups\\server-worlds\\`, then run:

```powershell
python scripts/verify-world-backup.py "F:\gonkas-backups\server-worlds\lookscanbethisevening"
```

which checks the download is a complete, readable world (region files, `level.dat`, playerdata)
rather than a silently truncated transfer, and writes a checksum manifest beside it. A world you
have not verified is not a backup.

### The loader

The old server is on NeoForge **21.1.243**; this pack needs **21.1.265** (see `PACKAGE.md`).
Install it through your host's panel, or run the NeoForge installer for 21.1.265 in the server
root. **This is not a file this package can contain** — it is the one manual step.

---

## 2. Upload order

1. **Stop the server.** Mods cannot be swapped under a running JVM, and a half-uploaded `mods/`
   is the commonest cause of a crash loop.
2. Set `level-name=nations` in `server.properties`. **Leave `lookscanbethisevening` alone.**
3. Delete everything in `DELETE-FIRST.txt` — check it against the banner at the top of that file
   first.
4. Install NeoForge 21.1.265.
5. Upload the contents of this folder.
6. Set the `server.properties` lines in section 4.
7. Start, and watch the log for `[thee]`.

---

## 3. Keeping it up to date afterwards

This package is **generated**, so you never have to decide what goes in it again:

```powershell
python scripts/build-server-package.py
```

```powershell
python scripts/build-server-package.py --delta
```

The first rebuilds the whole thing. The second writes a **`-delta` folder containing only the
files whose contents changed** since the last package, plus a `DELETE-ON-SERVER.txt` for anything
that has gone away. That matters because the full package is ~520 MB and almost all of it is mod
jars that never change — a `thee` rebuild is 600 KB. Upload the delta on top and you are current.

```powershell
python scripts/build-server-package.py --check ../gonkas-server-upload
```

verifies a built package against its own `UPLOAD-SHA1.txt` — worth running after a transfer you
are not sure completed.

**The habit worth keeping:** the pack repo is the source of truth, the script derives the server
from it, and `UPLOAD-SHA1.txt` records what is actually up there. Nothing is ever edited on the
server directly — if you change a config through FTP, the next delta will not know about it and
will quietly put the repo's version back.

---

## 4. `server.properties` — the lines worth setting

Edit the server's existing file; do not replace it.

```
level-type=minecraft:normal
max-tick-time=180000
view-distance=8
simulation-distance=6
allow-flight=true
spawn-protection=0
```

- **`max-tick-time=180000`** (3 minutes) matters most. Minecolonies pathfinding and Create
  contraption ticks can exceed the 60-second default on a busy colony, and the watchdog will kill
  the server mid-session. Raising it is the standard fix for any Minecolonies pack.
- **`allow-flight=true`** — Create jetpacks and Create Aeronautics airships both trip the vanilla
  flight check without it.
- **`spawn-protection=0`** — otherwise nobody can build near spawn, which is where the first
  colonies will go.
- **view/simulation distance**: 8 and 6 are deliberate. `thee` and Minecolonies both keep work off
  unloaded chunks on purpose, so a smaller simulation distance costs this pack much less than it
  would a vanilla server.

## 5. RAM and JVM

**Allocate 6–8 GB** if the host allows it. `SCULK.md`'s whole design is built around *"a 3–4 GB
server across a world with no border"*, so the pack's own systems are written to survive 4 GB —
but 124 mods with Create, Minecolonies and Biomes O' Plenty will thrash at 4 GB during worldgen.
6 GB is comfortable for 2–3 active players.

```
-Xms4G -Xmx8G -XX:+UseG1GC -XX:MaxGCPauseMillis=130 -XX:+UnlockExperimentalVMOptions -XX:G1NewSizePercent=28 -XX:G1HeapRegionSize=16M -XX:G1ReservePercent=20
```

## 6. Minecolonies: one decision made, one still open

`MINECOLONIES-INTEGRATION.md` lists four conflicts between `thee` and Minecolonies. Two are code
and are built. The other two are server config, in `config/minecolonies-server.toml`:

| Setting | Value | |
|---|---|---|
| `maxdistancefromworldspawn` | **`1000000`** — raised | The default 30000 is exactly where this pack's frontier begins, so a colony *at* 30k sat on the boundary and the Far Reaches were decorative. Now 33× the sculk layer's frontier radius. There is no disable: the config's range is `> 1000`. |
| `minColonyDistance` | `8` — **still your call** | How close colonies may be, in chunks. At 8, two level-5 claim rings (16 chunks each) overlap heavily. `thee` handles it — whoever claimed first keeps the chunk — but borders get messy. 16–24 gives each nation room. |

`initialColonySize = 4` and `maxColonySize = 20` should stay: `thee`'s claim ladder is keyed to
them (town hall 1 → 4 chunks, 5 → 16, deliberately under 20).

## 7. First boot, in order

1. Let the server finish generating spawn **before anyone joins**.
2. `/op <you>`.
3. `/sculk list` — expect *"No sculk nodes recorded"* on a fresh world; nodes appear as Ancient
   City chunks load. If the command does not exist, `thee` did not load — grep the log for
   `[thee]`.
4. `/theetest all` **before players join**: it creates and deletes colonies. Expect
   `13 passed, 0 FAILED, 1 skipped` or better. `claim-radius` may skip; that is known and
   harmless (see `TESTING.md`).
5. Check BlueMap on port **8100**, and that your host actually exposes it.

## 8. Launch-day heads-up: a new world has no money in it

This is not a bug and it will look like one.

`thee 1.24.0` removed **both** spur faucets — passive income and the 60,000 charter grant — on
the principle that spurs are an imperial currency. The only thing that mints spurs now is
**beating back the sculk**, paid to the empire that ran the campaign. So on a fresh world:

- a new settlement starts at **0 spurs** and cannot buy anything;
- nothing accrues over time;
- the first real money arrives when somebody destroys a sculk Heart (`/sculk here` says what a
  given node would pay).

If you want a float to get trade moving before the first campaign, that is what the operator
faucet is for:

```
/settlement fund <name> 50000
```

Worth deciding deliberately rather than discovering on night three.

## 9. BlueMap

`config/bluemap/` is included, so the server starts with the pack's map configuration. BlueMap
renders into its own `bluemap/` folder on the server — **deliberately not in this package**,
because it is rendered output for a world that does not exist yet.

`thee` draws two layers of its own once there is something to draw: **Nations** (empire territory,
filled in the seat's colour) and **The Sculk** (node zones, deeper stages more opaque). Both are
toggleable in the web UI. Create's track layer is already on (`create_bluemap-server.toml`).

## 10. If it will not start

- **Crash on load, a duplicate mod id, or an unexplained mixin error** → almost always a leftover
  jar in `mods/`. Confirm you *deleted* the folder rather than merging into it.
- **Watchdog kills the server after 60s** → `max-tick-time`, section 4.
- **`thee` missing** → check the jar's sha1 against its line in `mods/hand-installed.sha1`. This
  pack has shipped four different builds stamped the same version before, which is exactly why
  that file exists.
- **Worldgen looks wrong, or there are structures you do not recognise** → an orphan structure
  mod from the old pack generated into the world. There is no clean fix after the fact: delete
  the world and start again with a clean `mods/`.
