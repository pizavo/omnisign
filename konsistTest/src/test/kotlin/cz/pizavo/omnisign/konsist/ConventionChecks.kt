package cz.pizavo.omnisign.konsist

import com.lemonappdev.konsist.api.KoModifier
import com.lemonappdev.konsist.api.container.KoScope
import com.lemonappdev.konsist.api.declaration.KoBaseDeclaration
import com.lemonappdev.konsist.api.declaration.KoFileDeclaration
import com.lemonappdev.konsist.api.declaration.KoObjectDeclaration
import com.lemonappdev.konsist.api.provider.KoContainingDeclarationProvider
import com.lemonappdev.konsist.api.provider.KoNameProvider

/**
 * Finds the places that break each [ConventionRule] in the given Kotlin files.
 *
 * Each violation is one line in the format of `known-violations.txt`: the rule's [ConventionRule.id], the path of the
 * file relative to the repository root, and what in the file breaks the rule, such as `Outer.function` or an import.
 * The lines carry no line numbers, so they stay valid while unrelated code around them changes.
 *
 * The Compose components that the Lumo plugin generates into `composeApp` follow the plugin's own style and are
 * not checked.
 *
 * @param scope The Kotlin files to check, usually every module's sources.
 */
class ConventionChecks(scope: KoScope) {
	private val files: List<KoFileDeclaration> = scope.files.filterNot { LUMO_SOURCES in it.relativePath() }

	/**
	 * Lists the violations of [rule], one line each, sorted.
	 *
	 * @param rule The convention to check.
	 * @return The violations in the format of `known-violations.txt`.
	 */
	fun violationsOf(rule: ConventionRule): List<String> =
		files.flatMap { file ->
			when (rule) {
				ConventionRule.TYPE_WITHOUT_KDOC -> typesWithoutKDoc(file)
				ConventionRule.FUNCTION_WITHOUT_KDOC -> functionsWithoutKDoc(file)
				ConventionRule.SEVERAL_TOP_LEVEL_TYPES -> severalTopLevelTypes(file)
				ConventionRule.FILE_NOT_NAMED_AFTER_TYPE -> fileNotNamedAfterType(file)
				ConventionRule.OBJECT_DECLARATION -> disallowedObjects(file)
				ConventionRule.JAVA_API_IMPORT -> javaApiImports(file)
			}.map { subject -> "${rule.id} ${file.relativePath()} $subject" }
		}.sorted()

	/**
	 * Names the classes, interfaces and objects of [file] that have no KDoc, leaving out companion objects and
	 * declarations local to a function.
	 */
	private fun typesWithoutKDoc(file: KoFileDeclaration): List<String> =
		file.classesAndInterfacesAndObjects(includeNested = true, includeLocal = false)
			.filterNot { it is KoObjectDeclaration && it.hasCompanionModifier }
			.filterNot { it.hasKDoc }
			.map { it.declarationPath() }

	/**
	 * Names the functions of [file] that have no KDoc, leaving out overrides and local functions.
	 */
	private fun functionsWithoutKDoc(file: KoFileDeclaration): List<String> =
		file.functions(includeNested = true, includeLocal = false)
			.filterNot { it.hasOverrideModifier }
			.filterNot { it.hasKDoc }
			.map { it.declarationPath() }

	/**
	 * Lists the top-level types of [file] as one subject when there is more than one, so that adding or removing a
	 * type changes the line.
	 */
	private fun severalTopLevelTypes(file: KoFileDeclaration): List<String> =
		file.topLevelTypeNames()
			.takeIf { it.size > 1 }
			?.let { listOf(it.joinToString(",")) }
			.orEmpty()

	/**
	 * Names the only top-level type of [file] when the file is not named after it. The file name is compared up to
	 * its first dot, so `SaveDialog.jvm.kt` may hold `SaveDialog`.
	 */
	private fun fileNotNamedAfterType(file: KoFileDeclaration): List<String> =
		listOfNotNull(file.topLevelTypeNames().singleOrNull()?.takeIf { it != file.name.substringBefore('.') })

	/**
	 * Names the objects of [file] outside the allowed cases: companion objects, `data object` values, `expect` and
	 * `actual` objects, `external` objects that declare JavaScript modules, and Compose `*Defaults` holders.
	 */
	private fun disallowedObjects(file: KoFileDeclaration): List<String> =
		file.objects(includeNested = true)
			.filterNot {
				it.hasCompanionModifier || it.hasDataModifier || it.name.endsWith("Defaults") ||
					it.hasModifier(KoModifier.EXPECT, KoModifier.ACTUAL, KoModifier.EXTERNAL)
			}.map { it.declarationPath() }

	/**
	 * Lists the imports in [file] of Java APIs that have a Kotlin counterpart: `java.time`, `java.util.UUID`, and
	 * the static `java.nio.file.Files` and `Paths` calls that the `kotlin.io.path` functions replace.
	 */
	private fun javaApiImports(file: KoFileDeclaration): List<String> =
		file.imports.map { it.name }.filter { it.startsWith(JAVA_TIME) || it in JAVA_TYPES_WITH_KOTLIN_COUNTERPARTS }

	/**
	 * Returns the sorted names of the classes, interfaces, objects and type aliases declared at the top level of
	 * this file. A sealed hierarchy counts as its sealed parent alone: its branches may sit beside it at the top
	 * level.
	 */
	private fun KoFileDeclaration.topLevelTypeNames(): List<String> {
		val types = classesAndInterfacesAndObjects(includeNested = false, includeLocal = false)
		val sealedParents = types.filter { it.hasModifier(KoModifier.SEALED) }.map { it.name }.toSet()
		val hierarchyRoots = types.filterNot { type ->
			type.parents().any { it.name.substringBefore('<').substringAfterLast('.') in sealedParents }
		}
		return (hierarchyRoots.map { it.name } + typeAliases.map { it.name }).sorted()
	}

	/**
	 * Returns this file's path relative to the repository root, with forward slashes.
	 */
	private fun KoFileDeclaration.relativePath(): String = projectPath.replace('\\', '/').trimStart('/')

	/**
	 * Returns the names of this declaration and of the declarations that contain it, outermost first, joined with
	 * dots, such as `SigningViewModel.Companion.create`.
	 */
	private fun KoBaseDeclaration.declarationPath(): String =
		generateSequence(this) { (it as? KoContainingDeclarationProvider)?.containingDeclaration }
			.takeWhile { it !is KoFileDeclaration }
			.map { (it as? KoNameProvider)?.name ?: "?" }
			.toList()
			.asReversed()
			.joinToString(".")

	/**
	 * Holds the path fragment of the generated sources and the Java APIs the rules look for.
	 */
	private companion object {
		/**
		 * The package directory into which the Lumo plugin generates Compose components.
		 */
		const val LUMO_SOURCES = "/kotlin/cz/pizavo/omnisign/lumo/"

		/**
		 * The package prefix of the Java date and time API, which `kotlin.time` and `kotlinx.datetime` replace.
		 */
		const val JAVA_TIME = "java.time."

		/**
		 * Java types with a direct Kotlin counterpart: `kotlin.uuid.Uuid`, and the `kotlin.io.path` functions and
		 * extensions.
		 */
		val JAVA_TYPES_WITH_KOTLIN_COUNTERPARTS = setOf(
			"java.util.UUID",
			"java.nio.file.Files",
			"java.nio.file.Paths",
		)
	}
}
