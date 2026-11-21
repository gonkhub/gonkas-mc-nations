#!/usr/bin/env python3
"""Compare the client zip's mod set against the server package's.

    python scripts/check-parity.py
    python scripts/check-parity.py --client dist/GonkasMCNations-0.0.1.zip \
                                  --server ../gonkas-server-upload

WHAT THIS CATCHES THAT THE TWO BUILD SCRIPTS CANNOT
---------------------------------------------------
The client zip and the server package are built by different scripts from the same tree.
Each is individually correct. Nothing until now compared them to each other, and the
failure they can produce together is the worst one this pack has: a registry desync.

A client whose registry has an entry the server lacks CANNOT CONNECT. The handshake fails
on the missing path, every player at once, and the error names the mod rather than the
cause. The reverse - a server mod the client lacks - fails the same way.

So two rules, and they are not symmetrical:

  1. EVERY SERVER JAR MUST BE IN THE CLIENT, at the same version and the same bytes.
     There is no such thing as a legitimately server-only mod in this pack. If one
     appears, it is a packaging mistake.

  2. A CLIENT JAR MISSING FROM THE SERVER MUST BE DELIBERATE - it has to appear in
     build-server-package.py's CLIENT_ONLY list, which is imported here rather than
     copied, so the two can never drift apart.

Read against the package's UPLOAD-SHA1.txt, not its directory listing, so this works
identically on a full package and on a `--delta` one. A delta's manifest still describes
the whole server, which is exactly what has to be compared.
"""

import argparse
import hashlib
import importlib.util
import pathlib
import sys
import zipfile

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
import packmeta  # noqa: E402


def client_only_map():
    """CLIENT_ONLY imported from build-server-package.py, never duplicated.

    The filename has hyphens so it is not importable by name; load it by path. The
    module runs its top-level definitions only - main() is guarded by __main__ - so
    importing it is free of side effects.
    """
    path = packmeta.SCRIPTS / "build-server-package.py"
    if not path.is_file():
        raise packmeta.PackError("cannot find %s to read CLIENT_ONLY from" % path)
    spec = importlib.util.spec_from_file_location("_build_server_package", path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    mapping = getattr(module, "CLIENT_ONLY", None)
    if not mapping:
        raise packmeta.PackError("build-server-package.py has no CLIENT_ONLY mapping")
    return mapping


def server_jars(package_dir):
    """{jar filename: sha1} from the package manifest."""
    manifest = pathlib.Path(package_dir) / "UPLOAD-SHA1.txt"
    if not manifest.is_file():
        raise packmeta.PackError(
            "no UPLOAD-SHA1.txt in %s.\n"
            "    Build the server package first:\n"
            "        python scripts/build-server-package.py" % package_dir)
    out = {}
    for line in manifest.read_text(encoding="utf-8").splitlines():
        if "  " not in line:
            continue
        digest, rel = line.split("  ", 1)
        rel = rel.strip()
        if rel.startswith("mods/") and rel.endswith(".jar"):
            out[rel[len("mods/"):]] = digest.strip()
    if not out:
        raise packmeta.PackError("%s lists no mods/*.jar" % manifest)
    return out


def client_jars(zip_path):
    """{jar filename: sha1} read out of the built client zip."""
    zip_path = pathlib.Path(zip_path)
    if not zip_path.is_file():
        raise packmeta.PackError(
            "no client zip at %s.\n"
            "    Build it first:\n"
            "        python scripts/build-client-package.py" % zip_path)
    out = {}
    with zipfile.ZipFile(zip_path) as z:
        for info in z.infolist():
            name = info.filename
            if not name.startswith("minecraft/mods/") or not name.endswith(".jar"):
                continue
            rel = name[len("minecraft/mods/"):]
            if "/" in rel:          # nothing nested under mods/ is a loaded mod
                continue
            digest = hashlib.sha1()
            with z.open(info) as fh:
                for chunk in iter(lambda: fh.read(1 << 20), b""):
                    digest.update(chunk)
            out[rel] = digest.hexdigest()
    if not out:
        raise packmeta.PackError("%s contains no minecraft/mods/*.jar" % zip_path)
    return out


def latest_client_zip():
    dist = packmeta.ROOT / "dist"
    version = packmeta.version()
    exact = dist / ("%s-%s.zip" % (packmeta.pack()["slug"], version))
    if exact.is_file():
        return exact
    candidates = sorted(dist.glob("*.zip"), key=lambda p: p.stat().st_mtime)
    if not candidates:
        raise packmeta.PackError(
            "no zip in dist/. Build one:\n"
            "        python scripts/build-client-package.py")
    newest = candidates[-1]
    print("note: pack.json says %s but dist/ has no %s - checking %s instead"
          % (version, exact.name, newest.name))
    return newest


def main():
    parser = argparse.ArgumentParser(
        description=__doc__,
        formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--client", default=None, help="the built client zip")
    parser.add_argument("--server", default=None, help="the built server package dir")
    args = parser.parse_args()

    client_path = pathlib.Path(args.client) if args.client else latest_client_zip()
    server_path = (pathlib.Path(args.server) if args.server
                   else packmeta.ROOT.parent / "gonkas-server-upload")

    client = client_jars(client_path)
    server = server_jars(server_path)
    client_only = client_only_map()

    def is_client_only(jar):
        return next((reason for key, reason in client_only.items() if key in jar), None)

    problems = []

    # Rule 1: the server must not carry anything the client lacks, at all, ever.
    for jar, digest in sorted(server.items()):
        if jar not in client:
            problems.append(
                "SERVER-ONLY  %s\n"
                "        On the server and not in the client zip. A client missing a\n"
                "        server mod cannot complete the handshake. Either the client\n"
                "        build excluded it wrongly, or this jar should not ship." % jar)
        elif client[jar] != digest:
            problems.append(
                "DIFFERENT    %s\n"
                "        client sha1 %s\n"
                "        server sha1 %s\n"
                "        Same filename, different bytes. For a pack-built jar this is\n"
                "        the version-stamping bug - run verify-custom-jars.py."
                % (jar, client[jar], digest))

    # Rule 2: client extras have to be on the list, and the list has to be accurate.
    unexplained = []
    for jar in sorted(client):
        if jar in server:
            continue
        reason = is_client_only(jar)
        if reason is None:
            unexplained.append(jar)
    for jar in unexplained:
        problems.append(
            "UNEXPLAINED  %s\n"
            "        In the client and not on the server, and not matched by any\n"
            "        CLIENT_ONLY entry in build-server-package.py. If it is genuinely\n"
            "        client-side, add it there with its reason. If it is not, the\n"
            "        server is missing a mod it needs." % jar)

    # A CLIENT_ONLY entry that matches nothing is stale rather than dangerous, but it is
    # how the list rots: the next jar whose name happens to contain the substring gets
    # silently dropped from the server.
    stale = [key for key in client_only
             if not any(key in jar for jar in client)]

    excluded = [jar for jar in client if jar not in server]
    print("client   %d jars  (%s)" % (len(client), client_path.name))
    print("server   %d jars  (%s)" % (len(server), server_path.name))
    print("excluded %d client-only" % len(excluded))

    if stale:
        print()
        for key in sorted(stale):
            print("stale    CLIENT_ONLY[%r] matches no jar in the client - a version "
                  "rename?" % key)

    if problems:
        print(file=sys.stderr)
        for problem in problems:
            print("BLOCKED  %s" % problem, file=sys.stderr)
        print("\nparity FAILED: %d problem(s)" % len(problems), file=sys.stderr)
        return 1

    print("\nparity ok: every server jar is in the client at the same bytes, and every "
          "client extra is declared client-only")
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except packmeta.PackError as exc:
        packmeta.die(exc)
