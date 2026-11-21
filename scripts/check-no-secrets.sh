#!/bin/sh
# Refuse to commit anything that looks like a secret, a server address, or this
# machine's identity.
#
# This repo is public-facing: it exists so someone can rebuild and run this exact
# pack. That is all it is for. Minecraft config trees are a bad neighbourhood for
# this -- mod configs routinely accumulate auth tokens, webserver bindings, server
# addresses and absolute paths containing the packer's Windows username, and none
# of that is obvious from a filename.
#
# Scans STAGED content by default, so it works as a pre-commit hook:
#     ln -s ../../scripts/check-no-secrets.sh .git/hooks/pre-commit
#
# Run by hand with: sh scripts/check-no-secrets.sh
#
# With --all it scans every TRACKED file in the working tree instead. That is the
# release gate rather than the commit gate: a hook only ever sees the commit in front of
# it, so a secret committed before the hook was installed -- or committed on another
# machine, or with --no-verify -- is invisible to it forever. A release is the right
# moment to ask about the whole tree, because a release is when the tree becomes a zip
# somebody else unpacks.
#
# Comment lines are skipped, because .gitignore and .packignore legitimately talk
# about tokens and server addresses while containing neither.

set -e
cd "$(dirname "$0")/.."

status=0

report() {
    printf '%s\n' "BLOCKED  $1" >&2
    printf '%s\n' "         $2" >&2
    status=1
}

# Which files to scan. Content always comes from the INDEX, never from the working copy,
# in every mode -- see read_body below.
case "$1" in
    --all)
        # Every tracked file. Slow: a few hundred files times a handful of greps, which
        # on Windows is a few hundred process spawns. This is the one-time baseline
        # sweep, not a per-release gate.
        staged=$(git ls-files)
        ;;
    --range)
        # Everything that changed between $2 and HEAD, plus whatever is staged now.
        # This is the release gate: a release only needs to answer for what it adds,
        # because every earlier commit was answered for by the baseline sweep and by
        # the hook. Proportionate, and fast enough to run every time.
        [ -n "$2" ] || { echo "check-no-secrets: --range needs a revision" >&2; exit 2; }
        staged=$( { git diff --name-only --diff-filter=ACM "$2" HEAD;
                    git diff --cached --name-only --diff-filter=ACM; } | sort -u )
        ;;
    *)
        staged=$(git diff --cached --name-only --diff-filter=ACM)
        ;;
esac

# ALWAYS the index, never `cat`. The working copy of a filtered file is not what gets
# committed: instance.cfg is cleaned by scripts/clean-instance-cfg.sh on the way in, so
# the copy on disk holds this machine's Java paths and the committed copy does not.
# Scanning the working copy reports that as a leak, which is both wrong and the kind of
# wrong that teaches people to ignore the guard.
read_body() { git show ":$1" 2>/dev/null; }

[ -z "$staged" ] && exit 0

for f in $staged; do
    case "$f" in
        *.jar|*.png|*.ogg|*.zip|*.nbt|*.dat|*.mca) continue ;;
        scripts/check-no-secrets.sh) continue ;;
    esac

    [ -f "$f" ] || continue

    # drop comment lines before matching
    body=$(read_body "$f" | grep -vE '^[[:space:]]*(#|//|--|\*)' || true)
    [ -z "$body" ] && continue

    # this machine's absolute paths leak the OS username
    if printf '%s' "$body" | grep -qEi '[A-Z]:[/\\]+Users[/\\]+[A-Za-z0-9._-]+'; then
        report "$f" "contains a Windows user path (leaks the packer's username)"
    fi

    # Literal IPv4. The hard part is NOT finding dotted quads -- it is not drowning in
    # four-component version numbers, which look identical. "19.70.0.469" is a JEI version;
    # "21.1.4.12" is an AsyncParticles version. A packwiz index is full of them.
    #
    # Three rules, which together keep this useful without crying wolf:
    #   1. every octet must be 0-255, so "19.70.0.469" is not an address
    #   2. skip version/filename/url/hash metadata fields, where quads are always versions
    #   3. but ALWAYS flag a quad right after "://", which is a real network endpoint
    #      wherever it appears -- rule 2 must not create a hole here
    scannable=$(printf '%s\n' "$body" \
        | grep -vE "^[[:space:]]*(filename|url|hash|hash-format|mod-id|version|x-[a-z-]*version[a-z-]*)[[:space:]]*=" || true)

    quads=$(printf '%s\n' "$scannable" | grep -oE '\b([0-9]{1,3}\.){3}[0-9]{1,3}\b' || true)
    endpoints=$(printf '%s\n' "$body" | grep -oE '://([0-9]{1,3}\.){3}[0-9]{1,3}' | sed 's|://||' || true)

    ips=$(printf '%s\n%s\n' "$quads" "$endpoints" | grep -vE '^[[:space:]]*$' | sort -u \
        | awk -F. '$1<=255 && $2<=255 && $3<=255 && $4<=255' \
        | grep -vE '^(127\.0\.0\.1|0\.0\.0\.0|255\.255\.255\.255|192\.0\.2\.[0-9]+)$' || true)

    if [ -n "$ips" ]; then
        report "$f" "contains a literal IP address: $(printf '%s' "$ips" | tr '\n' ' ')"
    fi

    # assigned secrets: key = "value" / "key": "value", value non-empty and not a placeholder
    if printf '%s' "$body" | grep -qEi '"?(token|secret|password|passwd|api[_-]?key|access[_-]?key|private[_-]?key|webhook)"?[[:space:]]*[:=][[:space:]]*"?[A-Za-z0-9/+_-]{8,}'; then
        report "$f" "contains what looks like an assigned credential"
    fi

    if printf '%s' "$body" | grep -q 'BEGIN .*PRIVATE KEY'; then
        report "$f" "contains a private key block"
    fi
done

# files that must never be tracked regardless of content
for forbidden in \
    minecraft/servers.dat \
    minecraft/servers.dat_old \
    minecraft/kubejs/config/web_server.json \
    minecraft/usercache.json \
    minecraft/usernamecache.json
do
    if printf '%s\n' "$staged" | grep -qx "$forbidden"; then
        report "$forbidden" "must never be committed (server address, auth token or player data)"
    fi
done

if [ "$status" -ne 0 ]; then
    echo "" >&2
    echo "Commit refused. Unstage the file, or fix the content." >&2
    echo "If a match is genuinely a false positive, widen the exclusions in this script" >&2
    echo "rather than bypassing it with --no-verify." >&2
fi

exit "$status"
