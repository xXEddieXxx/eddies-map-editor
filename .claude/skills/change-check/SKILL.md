---
name: change-check
description: Checks a finished code change in Eddie's Map Editor before it is reported - is every touched line needed for the feature, shorten it (ponytail), tests, review, docs (doctest). The Stop hook asks for it with the changed files; run it also by hand with /change-check.
---

# Change check

Input: the changed files (from the Stop hook, or `git status --short` when none are given). Skip
files that no longer exist (eg a deleted `ScreenshotProbeTest`). The repo may have no commit yet:
then `git diff` shows nothing and an untracked file is read whole.

Run the steps in order. Each step that changes code goes on with the changed code.

1. **Needed?** Use the `ponytail:ponytail-review` skill on the changed files, with the feature the
   user asked for. Every hunk must serve that feature, or be a test, fixture or doc it needs.
   Revert hunks that don't (refactors on the side, options, "for later" code), but only ones made
   in this session; ask before touching the user's own edits. Fix the logic findings.
2. **Shorter.** Apply the `ponytail:ponytail` rules to the kept code: reuse the helpers in the
   shortcuts below, delete before adding, no new abstraction. Behaviour stays the same.
3. **Tests.** Stop a running editor or game (`jps -l`), then `./gradlew test`; green before going on.
4. **Review.** Start the `map-editor-reviewer` agent with what the change is for (not how it was
   built). Fix what it finds and repeat until `VERDICT: pass`.
5. **Docs.** Run the `doctest` skill on the changed files.
6. **Report** as `CLAUDE.md` says: what changed, how it was checked, what is open, a commit
   message. Start the editor again (`./gradlew run`, in the background).

If the ponytail plugin is missing, do steps 1 and 2 by hand with the same questions.

## Shortcuts

| Need                                | Where                                                                             |
|-------------------------------------|-----------------------------------------------------------------------------------|
| Which class does what               | `AGENTS.md` code map; `README.md` "Where things are"                              |
| Rules (data safety, UI, code, tests) | `AGENTS.md` "How it works", "Rules"                                              |
| Change the game XML                 | `MapXml` (returns a new `Game`), applied via `MapProject.edit`                    |
| Files beside the XML / disk         | `MapFiles` (value), `MapFolder` (disk, `.bak`, `replace`, zip, Play)              |
| UI pieces                           | `Form`, `Panels`, `Help` ("?" hints), `MakerIcon`, `SearchList`, `MakerResources` |
| Problems of a map                   | `MapChecks`, `FileLint`                                                           |
| Tests and fixtures                  | `src/test/java/eddies/mapeditor`; `src/test/resources/minimap.xml`; `MapPictureTest.givenPicture()` |
| TripleA engine source               | source checkouts in `CLAUDE.local.md` (to read only)              |
| Gradle with the right JDK           | `CLAUDE.local.md` (JAVA_HOME line)                                                |
| Offscreen screenshots               | `ui-prober` agent                                                                 |
| Larger separable work               | `map-editor-developer` agent                                                      |
| CI                                  | `.github/workflows/build.yml`                                                     |
