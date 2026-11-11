#!/usr/bin/env bash
# PostToolUse (Edit|Write): remember changed code files for the Stop check (check-changes.sh).
path=$(grep -o '"file_path" *: *"[^"]*"' | head -1 | sed 's/.*: *"//; s/"$//' | tr -s '\\' /)
case "$path" in
  */src/*|*.gradle.kts|*/gradle.properties|*/.github/*) ;;
  *) exit 0 ;;
esac
root=$(printf %s "$CLAUDE_PROJECT_DIR" | tr '\\' /)
mkdir -p "$root/.claude/state"
echo "${path#"$root"/}" >> "$root/.claude/state/changed.txt"
