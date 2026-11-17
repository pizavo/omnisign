import { readFileSync } from "node:fs";

/**
 * PreToolUse hook for the Bash and PowerShell tools.
 *
 * Blocks the shell commands that earlier agent sessions in this repository got wrong and tells the model
 * what to do instead. Claude Code shows standard error to the model when a PreToolUse hook exits with
 * status 2, and the command does not run. Any other outcome, including unreadable input, lets the command
 * through, so a fault in this script never stops work.
 */

const WORKTREE = String.raw`\.claude[\\/]+worktrees`;
const CHANGE_DIRECTORY = String.raw`(?:cd|pushd|chdir|Set-Location|Push-Location|sl)`;
const COMMAND_START = String.raw`(?:^|[;&|({\n]|\bthen\b|\bdo\b)\s*`;
const FLAGS = String.raw`(?:-\S+\s+)*`;

/**
 * The rules, each a predicate over the full command text and the explanation shown when it matches.
 */
const RULES = [
	{
		matches: (command) =>
			new RegExp(`${COMMAND_START}${CHANGE_DIRECTORY}\\s+${FLAGS}["']?[^;&|\\n]*${WORKTREE}`, "i").test(command) ||
			changesDirectoryThroughVariable(command),
		reason:
			"Changing into a folder under .claude/worktrees makes that worktree the session's working directory " +
			"for the rest of the session. Stay where you are and address the worktree from there: absolute paths, " +
			"`git -C <worktree> ...`, and `<worktree>/gradlew.bat -p <worktree> ...`.",
	},
	{
		matches: (command) =>
			new RegExp(String.raw`${COMMAND_START}&?\s*["']?(?:[^\s;&|]*[\\/])?gradlew(?:\.bat)?["']?\b[^;&|\n]*\s--stop\b`, "i").test(command),
		reason:
			"`gradlew --stop` stops every Gradle daemon on the machine, including builds that other agents or the " +
			"IDE are running. If a daemon is holding a worktree folder, let its build finish or ask the user.",
	},
	{
		matches: (command) =>
			new RegExp(String.raw`${COMMAND_START}git\b[^;&|\n]*(?:--no-gpg-sign\b|commit\.gpgsign\s*=\s*(?:false|0|no|off)\b)`, "i").test(command) ||
			new RegExp(String.raw`${COMMAND_START}git\s+config\b[^;&|\n]*\bcommit\.gpgsign\s+(?:false|0|no|off)\b`, "i").test(command),
		reason:
			"Every branch of this repository requires signed commits, so an unsigned commit cannot be pushed. If " +
			"signing fails because the GPG passphrase is not cached, stop and ask the user to unlock the key.",
	},
];

/**
 * Detects a change of directory through a variable that the same command set to a worktree path,
 * such as `WT=.../.claude/worktrees/x; cd "$WT"` in Bash or `$wt = "...\.claude\worktrees\x"; Set-Location $wt`
 * in PowerShell.
 *
 * @param {string} command the full command text
 * @returns {boolean} whether such a change of directory is present
 */
function changesDirectoryThroughVariable(command) {
	const assignment = new RegExp(String.raw`(?:^|[\s;&|(])\$?(\w+)\s*=\s*["']?[^"'\s;&|]*${WORKTREE}`, "gi");
	return [...command.matchAll(assignment)].some(([, name]) =>
		new RegExp(String.raw`${COMMAND_START}${CHANGE_DIRECTORY}\s+${FLAGS}["']?\$\{?${name}\b`, "i").test(command),
	);
}

/**
 * Reads the hook input, applies the rules, and exits with status 2 and the explanations when any rule matches.
 */
function main() {
	let command;
	try {
		command = String(JSON.parse(readFileSync(0, "utf8"))?.tool_input?.command ?? "");
	} catch {
		process.exit(0);
	}
	const reasons = RULES.filter((rule) => rule.matches(command)).map((rule) => rule.reason);
	if (reasons.length > 0) {
		process.stderr.write(`Blocked by .claude/hooks/guard-shell.mjs:\n- ${reasons.join("\n- ")}\n`);
		process.exit(2);
	}
	process.exit(0);
}

main();
