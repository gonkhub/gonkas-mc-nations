#!/usr/bin/env python3
"""Build the auto-updater jar and the small "launcher" zip players import once.

    python tools/updater/build.py            # jar + launcher zip
    python tools/updater/build.py --jar      # jar only

The jar (tools/updater/nations-updater.jar) is what build-client-package.py puts in
every release zip as updater/nations-updater.jar, with instance.cfg's PreLaunchCommand
pointing at it. Rebuild it only when Updater.java changes, and commit it - the release
build copies the committed jar, it does not compile.

The launcher zip (dist/GonkasMCNations-Launcher.zip) is a Prism instance with no
minecraft/ folder at all: instance.cfg, mmc-pack.json and the updater. Imported into
Prism, its first launch downloads the whole newest release and every later launch
fetches only what changed. It does not depend on a pack version, so one copy can be
handed out once and never replaced - unless the loader changes so far that Prism
cannot reach the new one (the updater handles ordinary NeoForge bumps itself).

Compiled for Java 17 so it runs on any JVM Prism might pick for this instance.
"""

import importlib.util
import pathlib
import re
import shutil
import subprocess
import sys
import tempfile
import zipfile

HERE = pathlib.Path(__file__).resolve().parent
PACK = HERE.parent.parent
SRC = HERE / "src"
JAR = HERE / "nations-updater.jar"

sys.path.insert(0, str(PACK / "scripts"))
import packmeta  # noqa: E402


def load_client_builder():
    spec = importlib.util.spec_from_file_location(
        "build_client_package", PACK / "scripts" / "build-client-package.py")
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return mod


def build_jar():
    sources = [str(p) for p in SRC.rglob("*.java")]
    with tempfile.TemporaryDirectory() as tmp:
        classes = pathlib.Path(tmp) / "classes"
        subprocess.run(["javac", "--release", "17", "-encoding", "UTF-8",
                        "-d", str(classes)] + sources, check=True)
        # Fixed timestamps so an unchanged source gives a byte-identical jar and git
        # does not see a new binary on every build.
        manifest = ("Manifest-Version: 1.0\r\n"
                    "Main-Class: gonka.updater.Updater\r\n\r\n")
        with zipfile.ZipFile(JAR, "w", zipfile.ZIP_DEFLATED) as z:
            info = zipfile.ZipInfo("META-INF/MANIFEST.MF", (2026, 1, 1, 0, 0, 0))
            info.compress_type = zipfile.ZIP_DEFLATED
            z.writestr(info, manifest)
            for cls in sorted(classes.rglob("*.class")):
                info = zipfile.ZipInfo(cls.relative_to(classes).as_posix(), (2026, 1, 1, 0, 0, 0))
                info.compress_type = zipfile.ZIP_DEFLATED
                z.writestr(info, cls.read_bytes())
    print("wrote %s (%d bytes)" % (JAR, JAR.stat().st_size))


def build_launcher():
    client = load_client_builder()
    meta = packmeta.pack()
    local = packmeta.local(required=True)
    server_name = local.get("server_name") or meta["name"]
    server_address = packmeta.server_address()

    cfg = (PACK / "instance.cfg").read_text(encoding="utf-8")
    cfg = client.clean_instance_cfg(cfg, server_name, server_address)
    # The first launch downloads ~750 MB before the game window exists. With the
    # console hidden that looks exactly like a launcher that hung, so show it.
    for key, val in (("OverrideConsole", "true"), ("ShowConsole", "true")):
        cfg = re.sub(r"(?m)^%s=.*$" % key, "%s=%s" % (key, val), cfg)

    out_dir = PACK / "dist"
    out_dir.mkdir(exist_ok=True)
    out = out_dir / ("%s-Launcher.zip" % meta["slug"])
    with zipfile.ZipFile(out, "w", zipfile.ZIP_DEFLATED) as z:
        z.writestr("instance.cfg", cfg)
        z.write(PACK / "mmc-pack.json", "mmc-pack.json")
        z.write(JAR, client.UPDATER_IN_ZIP)
        icon = PACK / "minecraft" / "icon.png"
        if icon.exists():
            z.write(icon, "minecraft/icon.png")
        z.writestr("HOW-TO-INSTALL.txt", HOW_TO.replace("{NAME}", server_name))
    print("wrote %s (%d bytes)" % (out, out.stat().st_size))


HOW_TO = """GONKA'S MC NATIONS - LAUNCHER
=============================

This small zip installs the pack and keeps it up to date by itself.

1. Install Prism Launcher (free): https://prismlauncher.org/download/
2. In Prism: Add Instance -> Import from zip -> Browse -> pick this file -> OK.
3. Select the new instance and press Launch.

The FIRST launch downloads the whole pack (about 750 MB) before the game opens. The
progress shows in the log window Prism opens - it is not frozen. After that, every
launch checks for a newer release and downloads only the files that changed.

If an update says the NeoForge version changed, just press Launch again.

Multiplayer -> {NAME} is already in the server list.
"""


if __name__ == "__main__":
    if shutil.which("javac") is None:
        sys.exit("javac not found - install a JDK 17 or newer")
    build_jar()
    if "--jar" not in sys.argv:
        build_launcher()
