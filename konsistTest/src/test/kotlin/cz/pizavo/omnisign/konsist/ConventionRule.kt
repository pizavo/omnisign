package cz.pizavo.omnisign.konsist

/**
 * A coding convention from `AGENTS.md` that [ConventionChecks] verifies across the Kotlin sources of every module.
 *
 * @property id The identifier that starts each line of this rule in `known-violations.txt`.
 * @property convention The convention in one sentence, used as the test name and in failure messages.
 */
enum class ConventionRule(val id: String, val convention: String) {
	/**
	 * Classes, interfaces and objects carry KDoc; companion objects do not need it.
	 */
	TYPE_WITHOUT_KDOC(
		"type-without-kdoc",
		"every class, interface and object has a KDoc comment",
	),

	/**
	 * Functions carry KDoc, except overrides, which the overridden declaration documents, and local functions.
	 */
	FUNCTION_WITHOUT_KDOC(
		"function-without-kdoc",
		"every function has a KDoc comment, except overrides and local functions",
	),

	/**
	 * A file declares at most one top-level class, interface, object, type alias or sealed hierarchy.
	 */
	SEVERAL_TOP_LEVEL_TYPES(
		"several-top-level-types",
		"a file declares one top-level class, interface, object, type alias or sealed hierarchy",
	),

	/**
	 * A file with one top-level type is named after it; a platform suffix such as `.jvm` is allowed.
	 */
	FILE_NOT_NAMED_AFTER_TYPE(
		"file-not-named-after-type",
		"a file is named after its top-level type",
	),

	/**
	 * `object` declarations are limited to the cases `AGENTS.md` allows, plus `data object` values of a sealed
	 * hierarchy and `external` objects that declare JavaScript modules; services, repositories and helpers are Koin
	 * definitions instead. The further cases that `docs/docs-development/conventions.mdx` accepts, such as the
	 * entry points of child JVMs, are listed one by one in `known-violations.txt`.
	 */
	OBJECT_DECLARATION(
		"object-declaration",
		"objects are only companion objects, data objects, expect/actual and external declarations, and Compose *Defaults",
	),

	/**
	 * Kotlin APIs replace the Java ones that have a direct Kotlin counterpart; a bridge to a Java library that
	 * requires a Java type stays in `jvmMain` and is listed in `known-violations.txt`.
	 */
	JAVA_API_IMPORT(
		"java-api-import",
		"Kotlin APIs replace java.time, java.util.UUID, and the java.nio.file.Files and Paths calls",
	),
}
