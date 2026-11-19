#!/usr/bin/env bash
# Publish the current HEAD to the public inqueryhq/inquery repo as one new commit
# on top of its existing history. Only the file tree is copied; the private dev
# history never leaves this machine.
#
# Usage: scripts/push-public-inquery.sh "feat: what changed in this release"
# Set GIT_SSH_COMMAND if the default SSH key/port does not reach the public repo.

set -euo pipefail

MSG="${1:?usage: $0 \"commit message\"}"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
WORK="$ROOT/.public-export-tmp"
REMOTE="${PUBLIC_REMOTE:-git@ssh.github.com:inqueryhq/inquery.git}"
NAME="$(git -C "$ROOT" config user.name)"
EMAIL="$(git -C "$ROOT" config user.email)"

rm -rf "$WORK"
trap 'rm -rf "$WORK"' EXIT
git clone -q --depth 1 "$REMOTE" "$WORK"

# Replace the tracked tree with the current HEAD (ignored files stay out).
git -C "$WORK" rm -rq --ignore-unmatch .
git -C "$ROOT" archive HEAD | tar -x -C "$WORK"
rm -f "$WORK/CLAUDE.local.md"
git -C "$WORK" add -A

if git -C "$WORK" diff --cached --quiet; then
  echo "Public repo already matches HEAD; nothing to publish."
  exit 0
fi

git -C "$WORK" diff --cached --stat | tail -1
git -C "$WORK" -c user.name="$NAME" -c user.email="$EMAIL" commit -q -m "$MSG"
git -C "$WORK" push -q origin main
echo "Done: https://github.com/inqueryhq/inquery"
