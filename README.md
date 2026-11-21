# Gonka's MC Nations

A NeoForge 1.21.1 Minecraft modpack.

**Players:** download the latest zip from
[Releases](https://github.com/gonkhub/gonkas-mc-nations/releases) and import it with
Prism Launcher (**Add Instance -> Import from zip**). `HOW-TO-JOIN.txt` inside the zip
has the rest.

## Working on the pack

Two things a fresh clone cannot run without:

```
cd minecraft/mods/.index && packwiz install --all
git config filter.prism-instance-cfg.clean "sh ./scripts/clean-instance-cfg.sh"
git config filter.prism-instance-cfg.smudge cat
```

Then `python scripts/release.py preflight`. Everything else - the runbook, the design
notes, the testing notes - is deliberately not in this repo; see CLAUDE.md.
