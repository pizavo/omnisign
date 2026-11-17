import { readFileSync } from "node:fs";
import { basename, relative } from "node:path";

/**
 * PostToolUse hook for the Edit, Write and MultiEdit tools.
 *
 * After an edit to a Kotlin source file it checks the lines the edit introduced against the conventions in
 * AGENTS.md: every class, interface, object and non-override function carries a KDoc comment, no inline
 * comments, and one top-level type per file, named after it. Only new lines are judged, so older code that
 * predates a rule does not get in the way. Problems go to standard error with exit status 2, which Claude Code
 * shows to the model; the edit itself stays in place. Any other outcome is silent.
 */

const MODIFIERS =
	"public|private|internal|protected|override|open|abstract|final|sealed|data|enum|annotation|value|inline|" +
	"suspend|operator|infix|tailrec|external|actual|expect|inner|companion|fun";
const DECLARATION = new RegExp(String.raw`^(\s*)((?:(?:${MODIFIERS})\s+)*)(class|interface|object|fun)\b(.*)$`);
const FUNCTION_NAME = /^\s*(?:<[^>]*>\s*)?(?:[\w<>?,.\s*]+\.)?(`[^`]+`|\w+)\s*\(/;
const TYPE_NAME = /^\s+(\w+)/;

/**
 * Parses a source line as a declaration of a class, interface, object or function.
 *
 * @param {string} line one line of Kotlin source
 * @returns {{indent: number, kind: string, name: string, needsKDoc: boolean} | null} the declaration, or null
 *   when the line declares none (including anonymous objects, companion objects and anonymous functions)
 */
function declarationOf(line) {
	const trimmed = line.trim();
	if (trimmed.startsWith("*") || trimmed.startsWith("//") || trimmed.startsWith("/*")) return null;
	const match = DECLARATION.exec(line);
	if (!match) return null;
	const [, indent, modifiers, kind, rest] = match;
	const name = (kind === "fun" ? FUNCTION_NAME : TYPE_NAME).exec(rest)?.[1];
	if (!name) return null;
	const isOverride = /\boverride\b/.test(modifiers);
	return { indent: indent.length, kind, name, needsKDoc: !isOverride };
}

/**
 * Tells whether a KDoc comment documents the declaration on the given line, looking past annotations.
 *
 * @param {string[]} lines the file's lines
 * @param {number} index the zero-based index of the declaration line
 * @returns {boolean} whether a `/** ... *\/` block ends right above the declaration
 */
function hasKDoc(lines, index) {
	let above = index - 1;
	while (above >= 0) {
		const trimmed = lines[above].trim();
		if (trimmed === "" || trimmed.startsWith("@") || /^[)\]]+,?$/.test(trimmed)) above--;
		else break;
	}
	if (above < 0 || !lines[above].trim().endsWith("*/")) return false;
	for (let line = above; line >= 0; line--) {
		const trimmed = lines[line].trim();
		if (trimmed.startsWith("/**")) return true;
		if (trimmed.startsWith("/*")) return false;
	}
	return false;
}

/**
 * Finds the lines of a code fragment that contain a line comment or a non-KDoc block comment, skipping
 * string literals, raw strings, character literals and KDoc.
 *
 * @param {string} fragment the code the edit introduced
 * @returns {string[]} the lines that start a comment
 */
function commentedLines(fragment) {
	const found = [];
	let rawString = false;
	let block = false;
	for (const line of fragment.split(/\r?\n/)) {
		if (!rawString && !block && line.trim().startsWith("*")) continue;
		let quote = null;
		let commented = false;
		for (let i = 0; i < line.length; ) {
			const rest = line.slice(i);
			if (block) {
				const end = rest.indexOf("*/");
				if (end < 0) break;
				block = false;
				i += end + 2;
			} else if (rawString) {
				const end = rest.indexOf('"""');
				if (end < 0) break;
				rawString = false;
				i += end + 3;
			} else if (quote) {
				if (line[i] === "\\") i += 2;
				else {
					if (line[i] === quote) quote = null;
					i++;
				}
			} else if (rest.startsWith('"""')) {
				rawString = true;
				i += 3;
			} else if (rest.startsWith("//")) {
				commented = true;
				break;
			} else if (rest.startsWith("/**")) {
				block = true;
				i += 3;
			} else if (rest.startsWith("/*")) {
				commented = true;
				block = true;
				i += 2;
			} else {
				if (line[i] === '"' || line[i] === "'") quote = line[i];
				i++;
			}
		}
		if (commented) found.push(line);
	}
	return found;
}

/**
 * Lists the zero-based indexes of the file lines that equal the given line exactly.
 *
 * @param {string[]} lines the file's lines
 * @param {string} line the line to look for
 * @returns {number[]} the matching indexes
 */
function indexesOf(lines, line) {
	return lines.flatMap((candidate, index) => (candidate === line ? [index] : []));
}

/**
 * Shortens a source line for a message.
 *
 * @param {string} line the source line
 * @returns {string} the trimmed line, cut at 90 characters
 */
function excerpt(line) {
	const trimmed = line.trim();
	return trimmed.length > 90 ? `${trimmed.slice(0, 87)}...` : trimmed;
}

/**
 * Collects the convention problems that the edit's new lines cause in the edited file.
 *
 * @param {string} path the edited file
 * @param {string[]} fragments the text the edit introduced
 * @returns {string[]} one message per problem
 */
function problemsIn(path, fragments) {
	const lines = readFileSync(path, "utf8").split(/\r?\n/);
	const newLines = [...new Set(fragments.flatMap((fragment) => fragment.split(/\r?\n/)))];
	const problems = [];

	for (const line of newLines) {
		const declaration = declarationOf(line);
		if (!declaration?.needsKDoc) continue;
		for (const index of indexesOf(lines, line)) {
			if (!hasKDoc(lines, index)) problems.push(`line ${index + 1}: \`${excerpt(line)}\` has no KDoc comment.`);
		}
	}

	for (const line of new Set(fragments.flatMap(commentedLines))) {
		const index = indexesOf(lines, line)[0];
		const where = index === undefined ? "new code" : `line ${index + 1}`;
		problems.push(`${where}: inline comment in \`${excerpt(line)}\`. Inline comments are not allowed unless the user asked for one.`);
	}

	const topLevelTypes = lines
		.map(declarationOf)
		.filter((declaration) => declaration && declaration.indent === 0 && declaration.kind !== "fun")
		.map((declaration) => declaration.name);
	const introducedTypes = newLines
		.map(declarationOf)
		.filter((declaration) => declaration && declaration.indent === 0 && declaration.kind !== "fun")
		.map((declaration) => declaration.name);
	const fileName = basename(path, ".kt");
	if (topLevelTypes.length > 1) {
		for (const name of introducedTypes.filter((name) => name !== fileName)) {
			problems.push(
				`\`${name}\` is one of ${topLevelTypes.length} top-level types in this file (${topLevelTypes.join(", ")}). ` +
					`Each file holds one, named after it: move \`${name}\` to ${name}.kt or nest it.`,
			);
		}
	} else if (topLevelTypes.length === 1 && introducedTypes.includes(topLevelTypes[0]) && topLevelTypes[0] !== fileName) {
		problems.push(`the file declares \`${topLevelTypes[0]}\` but is named ${fileName}.kt; name the file after its declaration.`);
	}

	return problems;
}

/**
 * Reads the hook input and reports convention problems for Kotlin source files.
 */
function main() {
	let input;
	try {
		input = JSON.parse(readFileSync(0, "utf8"));
	} catch {
		process.exit(0);
	}
	const toolInput = input?.tool_input ?? {};
	const path = String(toolInput.file_path ?? "");
	if (!/\.kt$/i.test(path) || /[\\/]build[\\/]/.test(path)) process.exit(0);
	const fragments =
		input.tool_name === "Write"
			? [String(toolInput.content ?? "")]
			: input.tool_name === "MultiEdit"
				? (toolInput.edits ?? []).map((edit) => String(edit.new_string ?? ""))
				: [String(toolInput.new_string ?? "")];

	let problems;
	try {
		problems = problemsIn(path, fragments);
	} catch {
		process.exit(0);
	}
	if (problems.length === 0) process.exit(0);
	const shown = relative(process.env.CLAUDE_PROJECT_DIR ?? process.cwd(), path) || path;
	process.stderr.write(
		`Kotlin conventions (AGENTS.md, "Coding Conventions") in ${shown}:\n- ${problems.join("\n- ")}\n` +
			"Fix these now, or tell the user why one should stay.\n",
	);
	process.exit(2);
}

main();
