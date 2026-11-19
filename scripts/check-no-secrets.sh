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
# Scans STAGED content only, so it works as a pre-commit hook:
#     ln -s ../../scripts/check-no-secrets.sh .git/hooks/pre-commit
#
# Run by hand with: sh scripts/check-no-secrets.sh
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

staged=$(git diff --cached --name-only --diff-filter=ACM)
[ -z "$staged" ] && exit 0

for f in $staged; do
    case "$f" in
        *.jar|*.png|*.ogg|*.zip|*.nbt|*.dat|*.mca) continue ;;
        scripts/check-no-secrets.sh) continue ;;
    esac

    [ -f "$f" ] || continue

    # drop comment lines before matching
    body=$(git show ":$f" 2>/dev/null | grep -vE '^[[:space:]]*(#|//|--|\*)' || true)
    [ -z "$body" ] && continue

    # this machine's absolute paths leak the OS username
    if printf '%s' "$body" | grep -qEi '[A-Z]:[/\\]+Users[/\\]+[A-Za-z0-9._-]+'; then
        report "$f" "contains a Windows user path (leaks the packer's username)"
    fi

    # literal IPv4, excluding loopback / any / broadcast / RFC5737 doc ranges
    ips=$(printf '%s' "$body" \
        | grep -oE '\b([0-9]{1,3}\.){3}[0-9]{1,3}\b' \
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
