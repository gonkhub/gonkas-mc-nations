# Gonka's MC Nations

NeoForge 1.21.1 Minecraft modpack. PrismLauncher (`OneSix`) instance.

Assemble a playable instance:

1. Clone into your PrismLauncher `instances/` folder.
2. Fetch the indexed mods with [packwiz](https://packwiz.infra.link/):
   ```
   cd minecraft/mods/.index
   packwiz install --all
   ```
3. Configure the `instance.cfg` clean filter once per clone:
   ```
   git config filter.prism-instance-cfg.clean "sh ./scripts/clean-instance-cfg.sh"
   git config filter.prism-instance-cfg.smudge cat
   ```
4. Verify the hand-installed jars: `sh scripts/verify-hand-installed.sh`

If you are committing to this repo, install the secret guard once per clone — git
hooks are not cloned:
```
printf '#!/bin/sh\nexec sh "$(git rev-parse --show-toplevel)/scripts/check-no-secrets.sh"\n' > .git/hooks/pre-commit
chmod +x .git/hooks/pre-commit
```

Release the pack — one command, gated at each step:

```
python scripts/release.py preflight   # every check, no side effects
python scripts/release.py run         # preflight, build, verify, deploy, publish
```

The version lives in `pack.json` and nowhere else; move it with
`python scripts/release.py bump <major|minor|patch>`, which keeps `instance.cfg` and
`CHANGELOG.md` in step. `preflight` is the one worth knowing: it refuses a release where
a pack-built mod reuses a version string for different bytes, which has already cost this
project a launch day once.

Build the pieces on their own:

```
python scripts/build-client-package.py          # the player zip
python scripts/build-server-package.py          # the full upload, ~520 MB
python scripts/build-server-package.py --delta  # only what changed since the last one
```

The package mirrors the server root: drag its contents in over FTP. It is the pack minus the
client-only mods, minus per-world state, minus anything holding a secret, plus a sha1 manifest
that makes the next upload a delta rather than half a gigabyte. `README-SERVER.md` and
`DELETE-FIRST.txt` ship inside it — read the second one before the first upload, because a
leftover jar from a different pack is a hard crash rather than a tidiness problem.

Worlds, logs, caches, per-player data and the server address are not in this repo.
