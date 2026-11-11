@AGENTS.md

# Working on this repository with Claude Code

- **The user commits.** Never commit, stage or push, and add no `Co-Authored-By` lines. End a
  change with a short summary the user can turn into a commit message.
- **Short, simple replies** in the user's language (German or English): what changed, how it
  was checked, what is open. No long reports.
- **Decide the details yourself** when the user asks to build something; ask only about choices
  that are theirs (deleting work, scope, names).
- **Don't use the user's mouse or keyboard.** Check the UI with offscreen renders
  (AGENTS.md, "Swing checks without a screen").
- **A running editor uses the jar a build writes.** Before a build, stop it
  (`jps -l` shows `eddies.mapeditor.EddiesMapEditor`), and start the editor
  again afterwards with `./gradlew run` in the background, so the user can try the change.
- **Workflow for a change** (steps 2–5 are the `change-check` skill; a Stop hook in
  `.claude/settings.json` asks for it once code changed, `doctest` checks the docs):
  1. Implement it yourself, or for bigger, separable parts with the `map-editor-developer` agent.
  2. `./gradlew test` green.
  3. Update `AGENTS.md` and `README.md` where the change made them wrong.
  4. Let the `map-editor-reviewer` agent review it (`VERDICT: pass` needed); fix what it finds.
  5. Short report with a commit message.
- The `ui-prober` agent renders screens offscreen and describes them, for layout checks.

Machine-specific notes (JDK path, where TripleA is installed) belong in `CLAUDE.local.md`,
which is not committed.
