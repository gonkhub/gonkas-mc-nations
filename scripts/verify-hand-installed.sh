#!/bin/sh
# Verify the hand-installed mod jars against their recorded sha1.
#
# Run this before a release and before a server deploy. Exit non-zero means the jar
# on disk is not the jar this pack was built against -- stop and find out why.
#
# Why this script exists:
#
# `thee` has shipped at least four distinct builds all stamped version 1.0.0, with
# the same filename. Two of them differ by whether the Mechanical Broker is present
# (434300 bytes: no broker; 509665 bytes: 35 broker entries). Nothing in the filename,
# the mod version, or the packwiz index distinguishes them, because hand-installed
# jars have no index entry at all.
#
# The failure mode is not subtle. A client with `thee:mechanical_broker` registered
# cannot connect to a server without it -- the handshake fails on the missing registry
# path. That is a hard connect failure on launch day, found by every player at once.
# It has already happened once on the gonk server.
#
# The permanent fix is a real version bump per build in gradle.properties, so that two
# different jars can never share a version string. Until that is true upstream, this
# script is the guard.

set -e
cd "$(dirname "$0")/.."

MANIFEST=minecraft/mods/hand-installed.sha1

if [ ! -f "$MANIFEST" ]; then
    echo "verify-hand-installed: no manifest at $MANIFEST" >&2
    exit 2
fi

# strip comments; sha1sum -c cannot read them
if grep -v '^[[:space:]]*#' "$MANIFEST" | grep -q '[^[:space:]]'; then
    grep -v '^[[:space:]]*#' "$MANIFEST" | sha1sum -c -
else
    echo "verify-hand-installed: manifest has no entries" >&2
    exit 2
fi
