#!/usr/bin/env bash
# Stop: once per batch of code changes, make Claude run the change-check skill before it ends.
input=$(cat)
list="$(printf %s "$CLAUDE_PROJECT_DIR" | tr '\\' /)/.claude/state/changed.txt"
[ -s "$list" ] || exit 0
files=$(sort -u "$list" | tr '\n' ' ')
rm -f "$list"
# The stop right after a block: the check ran, so edits it made count as checked.
case "$input" in *'"stop_hook_active":true'*|*'"stop_hook_active": true'*) exit 0 ;; esac
printf '{"decision":"block","reason":"Code changed: %s- run the change-check skill on these files before you finish."}\n' "$files"
