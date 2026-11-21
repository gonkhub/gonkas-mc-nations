# Working in this repo

Gonka's MC Nations — a NeoForge 1.21.1 modpack. `docs/` holds the design and workflow
notes and is deliberately gitignored; `docs/RELEASE.md` is the release runbook and
`docs/NATIONS.md` is the design brief. Read those before changing anything real.

## Never rewrite a file with Python's `write_text` on Windows

`.gitattributes` sets `* -text`, which disables all line-ending conversion so that pack
files round-trip into git byte-for-byte. `core.autocrlf` is `true` but `-text` overrides
it, so **whatever bytes you write are the bytes that get committed.**

`pathlib.Path.write_text()` translates `\n` to `os.linesep` on Windows. Read a
LF file with `read_text()` and write it back with `write_text()` and every line in the
file silently becomes CRLF — which then commits as a whole-file rewrite. It happened to
`scripts/release.py` and `CHANGELOG.md` on 2026-11-21: a 15-line change arrived as
"700 insertions, 676 deletions", and a real change inside a diff that size is invisible.

So:

- Prefer the Edit tool for edits. It preserves the file's own endings.
- Scripting an edit anyway? Use `read_bytes()` / `write_bytes()`, or
  `open(p, "w", newline="")`.
- Check `git diff --stat` after any scripted edit. An insertion count near the file's
  line count means you flipped the endings, not that you rewrote the file.

## The version lives in one place

`pack.json`. `python scripts/release.py bump <major|minor|patch>` moves it, the
`ExportVersion` in `instance.cfg` and the `CHANGELOG.md` heading together. Never edit
any of the three by hand.

`minecraft/mods/version-ledger.tsv` is **append-only**. It records the sha1 each version
of `thee`, `omnifix` and `chaosgate` shipped with, and `verify-custom-jars.py` refuses a
jar whose version is already recorded with different bytes. `thee` has shipped four
distinct builds all stamped 1.0.0, two differing by whether the Mechanical Broker exists,
and a client that has a registry entry the server lacks cannot finish the handshake. The
fix for a mismatch is always a new version. Never edit a ledger row.

## Branches

Work lands on `dev`. `main` moves only at a release — `release.py publish` fast-forwards
it — so `main` is always a state some player could actually be running.

## Before proposing a deploy

`python scripts/release.py preflight`. It is seconds, and it catches the two failures
that have already cost real time on the gonk server: a client/server mod-set desync, and
two different builds wearing one version string.

Both build scripts read the **working tree**, not git. "The build works" is therefore not
evidence that the repo is complete — that gap once left the config tree at 207 files on
disk and 2 in git. `preflight` now refuses a release when any file under `minecraft/` is
neither tracked nor ignored.

## Credentials

Never. The FTP password is the Multicraft panel password; it lives only in Windows
Credential Manager under `GonkasMCNations:ftp` and `deploy-ftp.py` reads it at connect
time. `scripts/check-no-secrets.sh` blocks a literal IP, a password or a machine path in
any tracked file, and it is right to — that is why the server address lives in the
gitignored `scripts/release-local.json`.

## Public repo: keep it thin

**Never write a README, a release description, or any lengthy player-facing prose into
this repository.** It goes stale within a release or two and then actively misleads.

- **A release body is the CHANGELOG entry and nothing else.** `release.py publish` emits
  exactly that; do not add a footer, a summary, or a description of what the pack is.
  0.0.1 shipped a page of prose including a section headed *"carried over from The
  Factory Must Gonk"* - the name of a different instance that exists only on this
  machine. A stranger downloading a zip cannot act on any of it.
- **Changelog entries are terse and player-visible.** What changed, in their words, one
  line each. Not what the pack IS, not why, not internal history.
- **Internal documentation stays on this machine.** `docs/` is gitignored on purpose:
  the runbook, the design surveys, the testing notes, the audits and the handover doc are
  written for whoever is building this, and are noise in a public repo. Prefer
  `git rm --cached` plus a `.gitignore` line over deleting; the local copy is the point.
- **What the public repo is for:** the pack itself, the scripts that build and release it,
  and a minimal README. Nothing that needs rewriting every time the pack changes.

If you catch yourself writing a paragraph for GitHub, it belongs in `docs/` instead.

## Launching the instance rewrites instance.cfg

Prism rewrites `instance.cfg` from its own state when it launches an instance, which
**silently reverts `ExportVersion` to whatever Prism last knew**. Bump, launch-test, then
publish, and the zip name and the repo disagree about the version.

Hit on 2026-11-21: `release.py bump minor` set `ExportVersion=0.1.0`, the launch test put
it back to `0.0.1`, and only `git status` caught it. **After any launch of the instance,
check `git diff instance.cfg` before releasing.**

## The server and the GitHub release never diverge

**Any time the Host Havoc server is updated, publish a GitHub release for that same
version.** Not "soon", not "at the next milestone" - the two are one step. A server
running a build players cannot download is a build nobody can reproduce, debug against,
or roll back to.

Practically this means `release.py deploy` and `release.py publish` belong in the same
sitting, and a deploy that cannot be published yet (because the build is known bad) should
be rolled forward to one that can, rather than left unpublished.

Standing instruction from the user, 2026-11-21, after 0.1.0 went to the server and sat
unpublished.

## GitHub rejects any tracked file over 100 MB

**Check jar sizes before un-ignoring them.** A push carrying a file over 100 MB is
rejected outright by the server, and the failure comes at `release.py publish` - after the
server has already been deployed, which is the worst moment to discover it.

Hit on 2026-11-21: `places-0.5.5-neoforge-1.21.1.jar` is 122 MB. It had been tracked along
with eighteen other player-supplied jars, and the first push attempt failed. Because none
of the commits were pushed yet, the fix was to collapse and rewrite them; once a blob that
size is in pushed history it is far more painful.

    git ls-files -z minecraft/mods | xargs -0 ls -l | awk '$5 > 100000000'

Note the asymmetry: a RELEASE ASSET may be up to 2 GiB, which is why a 721 MB client zip
is fine. The 100 MB limit is on files inside the git repository.
