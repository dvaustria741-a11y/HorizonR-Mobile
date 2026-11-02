#!/bin/sh
# Prints the app version for a CI run number: X.Y.Z with Z in 1..9.
# Every 9 builds bump the minor (9 -> next major), so it reads 1.9.7, never 1.8.107.
# Run 107 was the last build on the old scheme; VERSION holds the base "major.minor" (1.8).
RUN="${1:?run number}"
BASE="$(tr -d '[:space:]' < "$(dirname "$0")/../VERSION")"
MAJOR="${BASE%%.*}"; MINOR="${BASE#*.}"
N=$((RUN - 106))
[ "$N" -lt 1 ] && N=1
STEP=$(( (N - 1) / 9 + 1 ))
TOTAL=$((MAJOR * 10 + MINOR + STEP))
echo "$((TOTAL / 10)).$((TOTAL % 10)).$(( (N - 1) % 9 + 1 ))"
