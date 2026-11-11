#!/bin/sh
# Fails if any tracked (or staged) file contains a private value: network addresses,
# the Termux user name, the SSH port, the bot's username, personal email, key paths.
# The patterns live OUTSIDE the repo (they are the private values themselves):
#   ~/.config/phone-cctv/private-patterns.txt   one extended regex per line, # = comment
# Usage: scripts/privacy-check.sh      (exit 0 = clean). See docs/WORKFLOW.md §9.
set -e
cd "$(dirname "$0")/.."
P="$HOME/.config/phone-cctv/private-patterns.txt"
[ -f "$P" ] || { echo "missing $P (see docs/WORKFLOW.md)"; exit 2; }
PATTERN=$(grep -vE '^\s*(#|$)' "$P" | paste -sd'|' -)
if git grep -nIE --cached "$PATTERN" -- . ':!scripts/privacy-check.sh' ; then
  echo "PRIVATE DETAILS FOUND (above). Remove them before committing."
  exit 1
fi
echo "privacy check: clean"
