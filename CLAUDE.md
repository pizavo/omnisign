# CLAUDE.md

Guidance for Claude Code in this repository. The project reference is `AGENTS.md`, imported on the next line so that every session loads it at launch. Keep project knowledge in `AGENTS.md` rather than here, so the two files cannot drift apart; this file holds only what is specific to Claude Code.

@AGENTS.md

## Claude Code setup

`.claude/settings.json` registers two hooks, both small Node scripts in `.claude/hooks/`:

- `guard-shell.mjs` runs before every Bash and PowerShell command and blocks three mistakes earlier sessions made: changing into a folder under `.claude/worktrees` (that worktree then becomes the session's working directory), `gradlew --stop` (it stops the builds of other agents and the IDE), and turning commit signing off.
- `check-kotlin-conventions.mjs` runs after every edit to a `.kt` file. Judging only the lines the edit added, it reports a class, interface, object, or non-override function without KDoc, an inline comment, and a second top-level type in the file or a file not named after its type. The edit stays; the report asks for a fix.
