#!/usr/bin/env python3
"""One command per release, with gates that stop before anything reaches players.

    python scripts/release.py bump patch        # 0.0.1 -> 0.0.2, everywhere at once
    python scripts/release.py preflight         # every check, no side effects
    python scripts/release.py build             # client zip + server delta
    python scripts/release.py verify            # parity, manifests, checksums
    python scripts/release.py deploy            # backup, upload, verify sizes
    python scripts/release.py publish           # tag + GitHub release
    python scripts/release.py run               # all of the above, in order

THE SHAPE OF IT, AND WHY THE ORDER IS THE ORDER
-----------------------------------------------
Each phase is refusable and each one is cheap to redo. The expensive, irreversible things
happen last and only after the cheap checks have passed:

    bump -> preflight -> build -> verify -> deploy -> publish

`preflight` is first because it costs seconds and catches the two mistakes that are
unrecoverable once players have the zip: a mod-set desync, and two different builds of
`thee` wearing one version number. `verify` sits between building and deploying because
that is the last moment a bad package costs nothing. `deploy` takes a world backup before
it writes a single file. `publish` is last because a GitHub release is the one step that
is visible to other people the instant it happens.

WHAT IT WILL NOT DO
-------------------
It will not delete files on the server, start or stop the server, or create the GitHub
repository. The first is too destructive to drive from a path list; the second needs the
panel, which has no customer-accessible API; the third is a one-time outward-facing act
that should be a deliberate decision rather than a side effect of a release script.
Each is reported as a manual step at the point it is needed.
"""

import argparse
import hashlib
import json
import pathlib
import re
import shutil
import subprocess
import sys
from datetime import date

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
import packmeta  # noqa: E402

ROOT = packmeta.ROOT
SCRIPTS = packmeta.SCRIPTS
DIST = ROOT / "dist"
DEPLOYED = DIST / "deployed"
CHANGELOG = ROOT / "CHANGELOG.md"

FULL_PACKAGE = ROOT.parent / "gonkas-server-upload"
DELTA_PACKAGE = ROOT.parent / "gonkas-server-upload-delta"

UNRELEASED = "## Unreleased"


# ----------------------------------------------------------------------------------------
# plumbing
# ----------------------------------------------------------------------------------------

def run(cmd, cwd=ROOT, capture=False):
    """Run a subprocess, echoing it first so a failed release is reproducible by hand."""
    printable = " ".join(str(c) for c in cmd)
    print("    $ %s" % printable)
    proc = subprocess.run([str(c) for c in cmd], cwd=str(cwd),
                          capture_output=capture, text=True)
    if proc.returncode != 0:
        if capture:
            sys.stderr.write(proc.stdout or "")
            sys.stderr.write(proc.stderr or "")
        raise packmeta.PackError("`%s` exited %d" % (printable, proc.returncode))
    return proc.stdout if capture else ""


def phase(title):
    print()
    print("== %s %s" % (title, "=" * max(0, 76 - len(title))))


def python_script(name, *args):
    return run([sys.executable, str(SCRIPTS / name)] + list(args))


def sha1_file(path):
    digest = hashlib.sha1()
    with open(path, "rb") as fh:
        for chunk in iter(lambda: fh.read(1 << 20), b""):
            digest.update(chunk)
    return digest.hexdigest()


def client_zip_path(version=None):
    meta = packmeta.pack()
    return DIST / ("%s-%s.zip" % (meta["slug"], version or meta["version"]))


def confirm(question):
    """A yes/no the operator has to type. Deliberately not a default-yes."""
    reply = input("%s [type yes to continue] " % question).strip().lower()
    return reply == "yes"


# ----------------------------------------------------------------------------------------
# bump
# ----------------------------------------------------------------------------------------

def cmd_bump(args):
    """Move the version in every place it is written, in one step.

    Three files carry it and they must never disagree: pack.json is the source of truth,
    instance.cfg's ExportVersion is what the client builder stamps into the zip name, and
    CHANGELOG.md is what players read. Bumping by hand means bumping two of the three.
    """
    meta = packmeta.pack()
    old = meta["version"]
    major, minor, patch = (int(p) for p in old.split("."))

    if args.to:
        if not re.fullmatch(r"\d+\.\d+\.\d+", args.to):
            raise packmeta.PackError("--to %r is not MAJOR.MINOR.PATCH" % args.to)
        new = args.to
    elif args.part == "major":
        new = "%d.0.0" % (major + 1)
    elif args.part == "minor":
        new = "%d.%d.0" % (major, minor + 1)
    else:
        new = "%d.%d.%d" % (major, minor, patch + 1)

    if new == old:
        raise packmeta.PackError("already at %s" % new)

    # pack.json, preserving the comment block and key order
    pack_path = ROOT / "pack.json"
    data = json.loads(pack_path.read_text(encoding="utf-8"))
    data["version"] = new
    pack_path.write_text(json.dumps(data, indent=2, ensure_ascii=False) + "\n",
                         encoding="utf-8")

    # instance.cfg ExportVersion
    cfg_path = ROOT / "instance.cfg"
    cfg = cfg_path.read_text(encoding="utf-8")
    if re.search(r"^ExportVersion=.*$", cfg, re.M):
        cfg = re.sub(r"^ExportVersion=.*$", "ExportVersion=" + new, cfg, count=1, flags=re.M)
    else:
        cfg = cfg.rstrip("\n") + "\nExportVersion=" + new + "\n"
    cfg_path.write_text(cfg, encoding="utf-8")

    # CHANGELOG: close the Unreleased section into a dated heading, open a fresh one
    ensure_changelog()
    text = CHANGELOG.read_text(encoding="utf-8")
    if UNRELEASED in text:
        text = text.replace(
            UNRELEASED,
            "## %s - %s" % (new, date.today().isoformat()), 1)
        marker = "<!-- new entries go under Unreleased -->"
        text = text.replace(marker, marker + "\n\n" + UNRELEASED + "\n\n- _nothing yet_\n", 1)
    CHANGELOG.write_text(text, encoding="utf-8")

    print("%s -> %s" % (old, new))
    print("  pack.json       version")
    print("  instance.cfg    ExportVersion")
    print("  CHANGELOG.md    Unreleased closed as %s, new Unreleased opened" % new)
    print()
    print("Write the changelog entry for %s before releasing - `publish` uses it as the"
          % new)
    print("GitHub release notes, and an empty entry ships as the release description.")
    return 0


def ensure_changelog():
    if CHANGELOG.is_file():
        return
    CHANGELOG.write_text(
        "# Changelog\n\n"
        "Player-facing changes to Gonka's MC Nations, newest first.\n\n"
        "Each released heading is a git tag and a GitHub release; the body of the "
        "heading is\nwhat that release's notes say. `scripts/release.py bump` opens and "
        "closes these\nsections, so write under **Unreleased** as you work and the bump "
        "dates it for you.\n\n"
        "<!-- new entries go under Unreleased -->\n\n"
        "## Unreleased\n\n- _nothing yet_\n",
        encoding="utf-8")
    print("created CHANGELOG.md")


def changelog_section(version):
    """The body of one version's changelog heading, for release notes."""
    if not CHANGELOG.is_file():
        return ""
    text = CHANGELOG.read_text(encoding="utf-8")
    match = re.search(r"^## %s\b[^\n]*\n(.*?)(?=^## |\Z)" % re.escape(version),
                      text, re.M | re.S)
    return match.group(1).strip() if match else ""


# ----------------------------------------------------------------------------------------
# preflight
# ----------------------------------------------------------------------------------------

def cmd_preflight(args):
    meta = packmeta.pack()
    phase("preflight  %s %s" % (meta["name"], meta["version"]))

    problems = []

    # 1. the loader the launcher will use must be the one the server is told to install
    loader = packmeta.loader_version()
    if loader != meta["neoforge"]:
        problems.append(
            "pack.json says NeoForge %s, mmc-pack.json says %s. The server's loader "
            "must match the client's exactly." % (meta["neoforge"], loader))
    else:
        print("ok       NeoForge %s in both pack.json and mmc-pack.json" % loader)

    # 2. a dirty tree means the zip and the tag describe different code
    dirty = packmeta.working_tree_dirty()
    if dirty:
        message = ("%d tracked file(s) have uncommitted changes. The git tag would not "
                   "describe what is in the zip." % len(dirty))
        if args.allow_dirty:
            print("WARN     %s  (--allow-dirty)" % message)
            for line in dirty[:10]:
                print("             %s" % line)
        else:
            problems.append(message + "\n         "
                            + "\n         ".join(dirty[:10])
                            + ("\n         ... and %d more" % (len(dirty) - 10)
                               if len(dirty) > 10 else "")
                            + "\n         Commit them, or pass --allow-dirty.")
    else:
        print("ok       working tree clean")

    # 3. secrets.
    #
    # Scope it to what this release adds, not the whole tree. The pre-commit hook has
    # already answered for every commit it saw, and `--all` is a few hundred `git show`
    # calls, which on Windows takes minutes - a gate that slow gets skipped, and a
    # skipped gate is worse than a narrow one. With no previous tag there is nothing to
    # diff against, so the first release does pay for the full sweep once.
    since = previous_tag()
    try:
        if since:
            run(["sh", str(SCRIPTS / "check-no-secrets.sh"), "--range", since],
                capture=True)
            print("ok       no secrets in anything changed since %s" % since)
        else:
            print("         no previous tag - scanning every tracked file (slow, once)")
            run(["sh", str(SCRIPTS / "check-no-secrets.sh"), "--all"], capture=True)
            print("ok       no secrets, IPs or machine paths in any tracked file")
    except packmeta.PackError as exc:
        problems.append("secret scan failed:\n         %s" % exc)

    # 4. the version-stamping guard - the one that cost a launch day
    try:
        python_script("verify-custom-jars.py")
        print("ok       pack-built jars: filename, checksum and ledger all agree")
    except packmeta.PackError as exc:
        problems.append("custom jar verification failed: %s" % exc)

    # 5. third-party hand-installed jars against their recorded sha1
    try:
        run(["sh", str(SCRIPTS / "verify-hand-installed.sh")], capture=True)
        print("ok       hand-installed jars match hand-installed.sha1")
    except packmeta.PackError as exc:
        problems.append("hand-installed jar checksums failed: %s" % exc)

    # 6. pack content that the build ships and git does not have.
    #
    # Added after this bit me: the index held a stale `thee-1.0.0.jar` while the real
    # 1.28.0 jar was untracked, and the entire config tree - 207 files - was untracked
    # too. Every build was still correct, because both build scripts read the WORKING
    # TREE. That is what made it invisible, and it quietly broke the rollback path,
    # which is "check out the previous tag and rebuild".
    #
    # The definition that catches it in general: a file under minecraft/ that is neither
    # tracked nor ignored is a hole. `--others --exclude-standard` is exactly that set,
    # so this needs no list of its own to drift out of date.
    untracked = [line for line in
                 packmeta.git("ls-files", "--others", "--exclude-standard",
                              "--", "minecraft").splitlines() if line.strip()]
    if untracked:
        message = ("%d file(s) under minecraft/ are neither tracked nor ignored. The "
                   "build ships them and a clone would not have them:" % len(untracked))
        detail = "\n         ".join(untracked[:15])
        if len(untracked) > 15:
            detail += "\n         ... and %d more" % (len(untracked) - 15)
        if args.allow_untracked:
            print("WARN     %s  (--allow-untracked)" % message)
            print("             %s" % detail)
        else:
            problems.append(
                message + "\n         " + detail
                + "\n         Either `git add` them, or add them to .gitignore with a "
                  "reason.\n         Pass --allow-untracked to proceed anyway.")
    else:
        print("ok       every file under minecraft/ is tracked or deliberately ignored")

    # 7. the local config exists, so deploy will not fail after the slow parts
    try:
        packmeta.server_address()
        print("ok       scripts/release-local.json present and filled in")
    except packmeta.PackError as exc:
        if args.skip_local:
            print("WARN     %s  (--skip-local)" % exc)
        else:
            problems.append(str(exc))

    if problems:
        print()
        for problem in problems:
            print("BLOCKED  %s" % problem, file=sys.stderr)
        print("\npreflight FAILED: %d problem(s)" % len(problems), file=sys.stderr)
        return 1
    print("\npreflight passed")
    return 0


def previous_tag():
    """The most recent v* tag, or "" if this repo has never released.

    Used to scope the secret scan. `git describe` is the right tool: it answers
    "the last tag reachable from HEAD", which is exactly the span a release adds to.
    """
    out = packmeta.git("describe", "--tags", "--abbrev=0", "--match", "v*", check=False)
    return out if out and not out.startswith("fatal") else ""


# ----------------------------------------------------------------------------------------
# build
# ----------------------------------------------------------------------------------------

def cmd_build(args):
    meta = packmeta.pack()
    phase("build  %s" % meta["version"])

    DIST.mkdir(exist_ok=True)
    DEPLOYED.mkdir(parents=True, exist_ok=True)

    print("-- client zip")
    python_script("build-client-package.py")
    zip_path = client_zip_path()
    if not zip_path.is_file():
        raise packmeta.PackError(
            "the client build did not produce %s.\n"
            "    It names the zip from instance.cfg's ExportVersion; if that disagrees\n"
            "    with pack.json, run `release.py bump --to %s` to resync them."
            % (zip_path, meta["version"]))
    print("   %s  %.1f MB" % (zip_path.name, zip_path.stat().st_size / 1048576))

    print()
    print("-- server package")
    # Delta against what is ACTUALLY DEPLOYED, not against the last package built.
    #
    # This is the one behavioural fix to the existing flow. `--delta auto` compares
    # against the manifest sitting in the full package directory, which is never updated
    # after a delta is uploaded - so a second delta re-sends everything the first delta
    # already sent. The deployed manifest under dist/deployed/ is written only by a
    # verified upload, which makes it the only honest answer to "what is on the server".
    deployed = DEPLOYED / "UPLOAD-SHA1.txt"
    if args.full or not deployed.is_file():
        if not deployed.is_file() and not args.full:
            print("   no deployed manifest yet - building a FULL package")
        python_script("build-server-package.py")
        built = FULL_PACKAGE
    else:
        python_script("build-server-package.py", "--delta", str(deployed))
        built = DELTA_PACKAGE
        if not built.is_dir():
            built = FULL_PACKAGE

    print()
    print("built    client %s" % zip_path.name)
    print("         server %s" % built)
    (DIST / "last-build.json").write_text(json.dumps({
        "version": meta["version"],
        "client_zip": zip_path.name,
        "server_package": str(built),
        "built": date.today().isoformat(),
    }, indent=2) + "\n", encoding="utf-8")
    return 0


def last_build():
    path = DIST / "last-build.json"
    if not path.is_file():
        raise packmeta.PackError(
            "no dist/last-build.json - run `release.py build` first.")
    return json.loads(path.read_text(encoding="utf-8"))


# ----------------------------------------------------------------------------------------
# verify
# ----------------------------------------------------------------------------------------

def cmd_verify(args):
    meta = packmeta.pack()
    phase("verify  %s" % meta["version"])
    build = last_build()
    package = pathlib.Path(build["server_package"])
    zip_path = DIST / build["client_zip"]

    problems = []

    if build["version"] != meta["version"]:
        problems.append("dist/last-build.json is for %s but pack.json says %s - rebuild."
                        % (build["version"], meta["version"]))

    print("-- package against its own manifest")
    try:
        python_script("build-server-package.py", "--check", str(package))
    except packmeta.PackError as exc:
        problems.append("package self-check failed: %s" % exc)

    print()
    print("-- client/server mod parity")
    try:
        python_script("check-parity.py", "--client", str(zip_path),
                      "--server", str(package))
    except packmeta.PackError as exc:
        problems.append("parity check failed: %s" % exc)

    print()
    print("-- client zip checksum")
    digest = sha1_file(zip_path)
    recorded = DIST / (zip_path.name + ".sha1")
    if recorded.is_file():
        want = recorded.read_text(encoding="utf-8").split()[0]
        if want != digest:
            problems.append("%s sha1 is %s but %s records %s - the zip changed after "
                            "it was hashed." % (zip_path.name, digest, recorded.name, want))
        else:
            print("ok       %s  %s" % (digest, zip_path.name))
    else:
        recorded.write_text("%s  %s\n" % (digest, zip_path.name), encoding="utf-8")
        print("wrote    %s" % recorded.name)

    if problems:
        print()
        for problem in problems:
            print("BLOCKED  %s" % problem, file=sys.stderr)
        print("\nverify FAILED: %d problem(s)" % len(problems), file=sys.stderr)
        return 1
    print("\nverify passed - safe to deploy")
    return 0


# ----------------------------------------------------------------------------------------
# deploy
# ----------------------------------------------------------------------------------------

def cmd_deploy(args):
    meta = packmeta.pack()
    phase("deploy  %s" % meta["version"])
    build = last_build()
    package = pathlib.Path(build["server_package"])

    print("The server must be STOPPED before this writes anything.")
    print("Mods cannot be swapped under a running JVM, and a half-written mods/ is the")
    print("commonest cause of a crash loop.")
    print()

    if not args.yes and not confirm("Is the server stopped?"):
        print("Stopped. Nothing was uploaded.")
        return 1

    if not args.skip_backup:
        print()
        print("-- world backup")
        python_script("deploy-ftp.py", "backup-world")
    else:
        print("WARN     --skip-backup: no world backup taken for this deploy")

    print()
    print("-- upload")
    python_script("deploy-ftp.py", "upload", str(package))

    print()
    print("-- verify what landed")
    python_script("deploy-ftp.py", "verify", str(package))

    # Only now is the deployed manifest true. Writing it earlier would make the next
    # delta skip files that never arrived.
    DEPLOYED.mkdir(parents=True, exist_ok=True)
    manifest = package / "UPLOAD-SHA1.txt"
    shutil.copyfile(manifest, DEPLOYED / "UPLOAD-SHA1.txt")
    shutil.copyfile(manifest, DEPLOYED / ("UPLOAD-SHA1.%s.txt" % meta["version"]))
    print()
    print("recorded dist/deployed/UPLOAD-SHA1.txt  (the next delta compares against this)")
    print("         dist/deployed/UPLOAD-SHA1.%s.txt  (kept for rollback)"
          % meta["version"])

    print()
    print("MANUAL, in this order:")
    print("  1. If DELETE-ON-SERVER.txt was printed above, delete those files by hand.")
    print("  2. Start the server from the panel.")
    print("  3. Watch the console for:  Done (    [thee]    and no duplicate mod id")
    print("  4. Smoke test, before players join:")
    print("         /sculk list        expect a node list, or none on a fresh world")
    print("         /theetest all      expect 13 passed, 0 FAILED, 1 skipped or better")
    return 0


# ----------------------------------------------------------------------------------------
# publish
# ----------------------------------------------------------------------------------------

def cmd_publish(args):
    meta = packmeta.pack()
    version = meta["version"]
    tag = "v" + version
    phase("publish  %s" % tag)

    zip_path = client_zip_path()
    if not zip_path.is_file():
        raise packmeta.PackError("no %s - run `release.py build`" % zip_path)

    repo = meta.get("github")
    if not repo:
        raise packmeta.PackError("pack.json has no `github` field (owner/name)")

    notes = changelog_section(version)
    if not notes or "_nothing yet_" in notes:
        raise packmeta.PackError(
            "CHANGELOG.md has no written entry for %s.\n"
            "    The entry is the release notes; publishing without one ships a release\n"
            "    whose description is empty or a placeholder. Write it, then re-run."
            % version)

    remotes = packmeta.git("remote", check=False)
    if "origin" not in remotes.split():
        print("This repo has no `origin` remote, so there is nothing to push to.")
        print()
        print("MANUAL, once - creating a public repository is an outward-facing act and")
        print("this script will not do it on its own:")
        print()
        print("    gh repo create %s --public --source . --remote origin --push" % repo)
        print()
        print("Then re-run `release.py publish`.")
        return 1

    existing = packmeta.git("tag", "--list", tag)
    if existing:
        print("tag %s already exists locally" % tag)
    else:
        run(["git", "tag", "-a", tag, "-m", "%s %s" % (meta["name"], version)])
    run(["git", "push", "origin", "HEAD"])
    run(["git", "push", "origin", tag])

    notes_file = DIST / ("release-notes-%s.md" % version)
    checksum = sha1_file(zip_path)
    notes_file.write_text(
        "%s\n\n---\n\n"
        "**Download:** `%s` (%.0f MB)\n\n"
        "Import it with Prism Launcher: **Add Instance -> Import from zip**. "
        "`HOW-TO-JOIN.txt` inside the zip has the rest.\n\n"
        "Minecraft %s, NeoForge %s.\n\n"
        "sha1 `%s`\n"
        % (notes, zip_path.name, zip_path.stat().st_size / 1048576,
           meta["minecraft"], meta["neoforge"], checksum),
        encoding="utf-8")

    sha_path = DIST / (zip_path.name + ".sha1")
    assets = [str(zip_path)]
    if sha_path.is_file():
        assets.append(str(sha_path))

    print()
    print("Uploading %.0f MB to a PUBLIC GitHub release. GitHub's per-asset limit is"
          % (zip_path.stat().st_size / 1048576))
    print("2 GiB, so this fits, and there is no cap on total release size or bandwidth.")
    if not args.yes and not confirm("Publish %s to %s?" % (tag, repo)):
        print("Stopped. The tag is pushed; no release was created.")
        return 1

    run(["gh", "release", "create", tag,
         "--repo", repo,
         "--title", "%s %s" % (meta["name"], version),
         "--notes-file", str(notes_file)] + assets)

    # main tracks what players actually have. Work lands on dev; main moves only here,
    # once a release exists, so "what is on main" and "what someone could have downloaded"
    # are the same question. Fast-forward ONLY: if main has drifted off the released
    # history, that is a real situation needing a human, not something to force past.
    # Ancestry by comparing hashes, not by `merge-base --is-ancestor`: that reports
    # through its exit code, and packmeta.git hands back stdout only.
    main_sha = packmeta.git("rev-parse", "--verify", "--quiet", "refs/heads/main",
                            check=False)
    on_main = packmeta.git("rev-parse", "--abbrev-ref", "HEAD") == "main"
    if main_sha and on_main:
        # Released straight off main. `git branch -f` refuses to touch the checked-out
        # branch, and raising here would fail publish AFTER the release went public.
        print()
        print("main is the current branch, so it is already at %s." % tag)
    elif main_sha:
        if packmeta.git("merge-base", "main", tag) != main_sha:
            print()
            print("NOT moving main: it is not an ancestor of %s, so this would not be a" % tag)
            print("fast-forward. Someone has committed to main directly. Sort that out by")
            print("hand - main is what players have and it should never be rewritten.")
        else:
            run(["git", "branch", "-f", "main", tag])
            run(["git", "push", "origin", "main"])

    print()
    print("published  https://github.com/%s/releases/tag/%s" % (repo, tag))
    print("Players download the zip from that page. Post the link.")
    return 0


# ----------------------------------------------------------------------------------------
# run
# ----------------------------------------------------------------------------------------

def cmd_run(args):
    meta = packmeta.pack()
    print("%s %s - full release" % (meta["name"], meta["version"]))
    for name, func in (("preflight", cmd_preflight),
                       ("build", cmd_build),
                       ("verify", cmd_verify),
                       ("deploy", cmd_deploy),
                       ("publish", cmd_publish)):
        if name in (args.skip or []):
            print("\n== skip %s (--skip)" % name)
            continue
        code = func(args)
        if code:
            print("\nstopped at `%s`. Nothing after it ran." % name, file=sys.stderr)
            return code
    print("\nreleased %s" % meta["version"])
    return 0


def main():
    parser = argparse.ArgumentParser(
        description=__doc__,
        formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = parser.add_subparsers(dest="cmd", required=True)

    p = sub.add_parser("bump", help="move the version everywhere it is written")
    p.add_argument("part", nargs="?", default="patch",
                   choices=["major", "minor", "patch"])
    p.add_argument("--to", default=None, help="set an exact version instead")
    p.set_defaults(func=cmd_bump)

    def add_gate_flags(parser_):
        parser_.add_argument("--allow-dirty", action="store_true",
                             help="proceed with uncommitted tracked changes")
        parser_.add_argument("--skip-local", action="store_true",
                             help="do not require scripts/release-local.json")
        parser_.add_argument("--allow-untracked", action="store_true",
                             help="proceed with pack files that are neither tracked "
                                  "nor ignored")

    p = sub.add_parser("preflight", help="every check, no side effects")
    add_gate_flags(p)
    p.set_defaults(func=cmd_preflight)

    p = sub.add_parser("build", help="client zip + server package")
    p.add_argument("--full", action="store_true",
                   help="build a full server package instead of a delta")
    p.set_defaults(func=cmd_build)

    p = sub.add_parser("verify", help="parity, manifests and checksums")
    p.set_defaults(func=cmd_verify)

    p = sub.add_parser("deploy", help="backup, upload, verify")
    p.add_argument("--yes", action="store_true", help="do not ask for confirmation")
    p.add_argument("--skip-backup", action="store_true",
                   help="do not back up the world first")
    p.set_defaults(func=cmd_deploy)

    p = sub.add_parser("publish", help="tag and create the GitHub release")
    p.add_argument("--yes", action="store_true", help="do not ask for confirmation")
    p.set_defaults(func=cmd_publish)

    p = sub.add_parser("run", help="preflight, build, verify, deploy, publish")
    add_gate_flags(p)
    p.add_argument("--full", action="store_true")
    p.add_argument("--yes", action="store_true")
    p.add_argument("--skip-backup", action="store_true")
    p.add_argument("--skip", action="append", default=[],
                   choices=["preflight", "build", "verify", "deploy", "publish"],
                   help="skip a phase (repeatable)")
    p.set_defaults(func=cmd_run)

    args = parser.parse_args()
    return args.func(args)


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except packmeta.PackError as exc:
        packmeta.die(exc)
    except KeyboardInterrupt:
        print("\ninterrupted", file=sys.stderr)
        raise SystemExit(130)
