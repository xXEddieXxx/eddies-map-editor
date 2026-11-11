---
name: doctest
description: Checks whether a code change in Eddie's Map Editor made AGENTS.md, README.md, CLAUDE.md, the agent and skill files or the CI workflow wrong or incomplete, and fixes them. Use after a change, or with /doctest.
---

# Doctest

Input: the changed files (or `git status --short`). Read each change, then check the docs it can
touch. Fix what is wrong in the docs' own short style; never rewrite what is still true.

| The change                                   | Check                                                                                    |
|----------------------------------------------|------------------------------------------------------------------------------------------|
| Class added, removed, renamed, role changed  | `AGENTS.md` code map; `README.md` "Where things are"                                     |
| A tool or feature a map maker sees           | `README.md` "What it does"; `AGENTS.md` "Goal" (drop what is no longer "only in files") |
| How saving, undo, threads or the canvas work | `AGENTS.md` "How it works" and "Rules"                                                   |
| `Editor` interface methods                   | `AGENTS.md` code map line of `Editor`                                                    |
| Gradle tasks, JDK, TripleA dependency        | `AGENTS.md` "Build and run"; `README.md` "Requirements", "Run", "Test"; `.github/workflows/build.yml` |
| Classes, methods or steps the agents name    | `.claude/agents/*.md`, `.claude/skills/*/SKILL.md`, `CLAUDE.md`                          |
| A screen's look                              | `docs/screenshot-*.png` - only say they are outdated; make new ones only when asked      |

Fast way: grep the docs for every class and method name the change touched, added or removed
(`Grep` over `*.md` and `.github`), and read each hit against the new code.

Report one line per doc fixed (file, what), or "docs ok".
