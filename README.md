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

Worlds, logs, caches, per-player data and the server address are not in this repo.
