import { readFileSync } from "node:fs";

/**
 * PreToolUse hook for the pr-reviewer subagent's Bash and PowerShell commands.
 *
 * The reviewer reads, runs specs, and makes temporary mutations in its own worktree; everything else stays
 * with the parent session. This hook blocks the commands that would change state the reviewer shares with
 * others: commits and other history changes, the git stash stack (shared by every worktree of the
 * repository), branches and worktrees, and anything written to GitHub. It exits with status 2 and an
 * explanation, which Claude Code shows to the subagent; unreadable input lets the command through.
 */

const COMMAND_START = String.raw`(?:^|[;&|({\n]|\bthen\b|\bdo\b)\s*&?\s*(?:\w+=\S*\s+)*`;

/**
 * Builds a pattern for a program name that may be given bare, by path, or by a quoted path with spaces.
 *
 * @param {string} name the program name without extension
 * @returns {string} the pattern source
 */
function program(name) {
	return String.raw`(?:"[^"\n]*[\\/]${name}(?:\.exe)?"|'[^'\n]*[\\/]${name}(?:\.exe)?'|(?:[^\s;&|"']*[\\/])?${name}(?:\.exe)?)`;
}

const GIT = String.raw`${COMMAND_START}${program("git")}(?:\s+(?:-C\s+\S+|-c\s+\S+|--[\w-]+(?:=\S+)?))*\s+`;
const GH = String.raw`${COMMAND_START}${program("gh")}\s+`;

/**
 * Patterns for the forbidden commands, each with a short name for the message.
 */
const FORBIDDEN = [
	{ name: "git history changes", pattern: new RegExp(`${GIT}(?:commit|push|merge|rebase|cherry-pick|revert|tag|update-ref|am)\\b`, "i") },
	{ name: "the shared git stash", pattern: new RegExp(`${GIT}stash\\b`, "i") },
	{ name: "branch changes", pattern: new RegExp(`${GIT}(?:branch\\s+(?:\\S+\\s+)*-[dDmMfc]\\b|switch\\s+(?:\\S+\\s+)*-[cC]\\b|checkout\\s+(?:\\S+\\s+)*-[bB]\\b)`, "i") },
	{ name: "worktree changes", pattern: new RegExp(`${GIT}worktree\\s+(?!list\\b)`, "i") },
	{ name: "GitHub writes", pattern: new RegExp(`${GH}(?:pr\\s+(?:comment|review|merge|close|edit|ready|reopen|create)|issue\\s+(?:comment|create|close|edit|reopen)|release|workflow\\s+run|run\\s+(?:rerun|cancel))\\b`, "i") },
	{ name: "GitHub writes", pattern: new RegExp(`${GH}api\\b[^;&|\\n]*(?:-X\\s*|--method[\\s=]+)(?:POST|PATCH|PUT|DELETE)\\b`, "i") },
	{ name: "GitHub writes", pattern: new RegExp(`${GH}api\\b[^;&|\\n]*\\s(?:-f|-F|--field|--raw-field|--input)\\s`, "i") },
];

/**
 * Reads the hook input and blocks forbidden commands with an explanation.
 */
function main() {
	let command;
	try {
		command = String(JSON.parse(readFileSync(0, "utf8"))?.tool_input?.command ?? "");
	} catch {
		process.exit(0);
	}
	const hits = [...new Set(FORBIDDEN.filter(({ pattern }) => pattern.test(command)).map(({ name }) => name))];
	if (hits.length > 0) {
		process.stderr.write(
			`Blocked by .claude/hooks/guard-reviewer.mjs (${hits.join(", ")}): the reviewer never changes shared state. ` +
				"Revert a temporary mutation with `git checkout -- <file>`, and put everything you found in your report; " +
				"the parent session decides what to commit or post.\n",
		);
		process.exit(2);
	}
	process.exit(0);
}

main();
