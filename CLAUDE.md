# CLAUDE.md

Guidance for Claude Code in this repository. The project reference is `AGENTS.md`, imported on the next line so that every session loads it at launch. Keep project knowledge in `AGENTS.md` rather than here, so the two files cannot drift apart; this file holds only what is specific to Claude Code.

@AGENTS.md

## Claude Code setup

`.claude/settings.json` registers two hooks, both small Node scripts in `.claude/hooks/`:

- `guard-shell.mjs` runs before every Bash and PowerShell command and blocks three mistakes earlier sessions made: changing into a folder under `.claude/worktrees` (that worktree then becomes the session's working directory), `gradlew --stop` (it stops the builds of other agents and the IDE), and turning commit signing off.
- `check-kotlin-conventions.mjs` runs after every edit to a `.kt` file. Judging only the lines the edit added, it reports a class, interface, object, or non-override function without KDoc, an inline comment, and a second top-level type in the file or a file not named after its type. The edit stays; the report asks for a fix.

`.claude/agents/pr-reviewer.md` defines the independent reviewer for security and data-loss fixes. It runs on Sonnet in a worktree of its own, receives what the PR is meant to fix and the PR's head commit rather than the parent's reasoning, runs specs one by one and PIT (`:<module>:pitest`, with at most two threads where the specs start the server) on the classes the PR changes, and reports in at most 300 words. Its own hook, `guard-reviewer.mjs`, blocks commits, pushes, the git stash (shared by every worktree), branch and worktree changes, and writes to GitHub.
