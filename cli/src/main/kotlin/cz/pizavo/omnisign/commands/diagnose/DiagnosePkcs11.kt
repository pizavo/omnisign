package cz.pizavo.omnisign.commands.diagnose

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import cz.pizavo.omnisign.data.service.Pkcs11DiagnosticsReport
import cz.pizavo.omnisign.data.service.Pkcs11DiagnosticsService
import kotlinx.coroutines.runBlocking
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

/**
 * CLI subcommand that produces a verbose, ground-truth diagnostic report on PKCS#11
 * discovery for the host machine.
 *
 * Output covers the JVM environment, candidate library paths grouped by source layer,
 * out-of-process p11-kit truth (Linux), connected PC/SC readers, and per-candidate
 * subprocess probe outcomes with wall-clock timings.  The report is intentionally
 * verbose: it is meant to be copy-pasted into bug reports or compared across machines.
 *
 * The subcommand never modifies any state — it does not trigger application warmup,
 * does not touch the keystore, and does not call `C_Login`.
 */
class DiagnosePkcs11 : CliktCommand(name = "pkcs11"), KoinComponent {
	private val service: Pkcs11DiagnosticsService by inject()

	override fun help(context: Context): String =
		"Print a structured PKCS#11 discovery diagnostic for the current host"

	override fun run(): Unit = runBlocking {
		val report = service.runDiagnostics()
		echo(formatReport(report))
	}

	/**
	 * Pretty-print [report] to a single multi-line string suitable for console output.
	 */
	private fun formatReport(report: Pkcs11DiagnosticsReport): String = buildString {
		appendHeader()
		appendEnvironment(report.environment)
		appendCandidatesByLayer(report.candidatesByLayer)
		appendMergedCandidates(report.mergedCandidates)
		appendP11Kit(report.p11Kit)
		appendPcscReaders(report.pcscReaders)
		appendProbes(report.probes)
		appendTokens(report.tokens)
		appendNoLoginEnumeration(report.noLoginEnumeration)
		appendRawNoLoginEnumeration(report.rawNoLoginCertificates)
		appendFooter(report.totalElapsedMillis)
	}

	private fun StringBuilder.appendHeader() {
		appendLine(SEPARATOR)
		appendLine("  PKCS#11 DIAGNOSTIC REPORT")
		appendLine(SEPARATOR)
	}

	private fun StringBuilder.appendEnvironment(env: Pkcs11DiagnosticsReport.Environment) {
		appendLine()
		appendLine("Environment")
		appendLine(SECTION_SEPARATOR)
		appendLine("  OS:                       ${env.osName} ${env.osVersion} (${env.osArch})")
		appendLine("  JVM bitness:              ${if (env.jvmIs64Bit) "64-bit" else "32-bit"}")
		appendLine("  Java version:             ${env.javaVersion}")
		appendLine("  java.home:                ${env.javaHome}")
		appendLine("  classpath length:         ${env.classpathChars} chars")
		val nativeAccess = if (env.nativeAccessFlagPresent) "yes" else "not detected"
		appendLine("  --enable-native-access:   $nativeAccess")
	}

	private fun StringBuilder.appendCandidatesByLayer(layers: Pkcs11DiagnosticsReport.CandidatesByLayer) {
		appendLine()
		appendLine("Candidate sources")
		appendLine(SECTION_SEPARATOR)
		appendLayer("OS-native discovery", layers.osNative)
		appendLayer("Drop directory", layers.dropDir)
		appendLayer("User-supplied", layers.userSupplied)
	}

	private fun StringBuilder.appendLayer(label: String, items: List<Pkcs11DiagnosticsReport.Candidate>) {
		appendLine("  $label (${items.size}):")
		if (items.isEmpty()) {
			appendLine("    (none)")
			return
		}
		for (candidate in items) appendCandidate(candidate)
	}

	private fun StringBuilder.appendMergedCandidates(merged: List<Pkcs11DiagnosticsReport.Candidate>) {
		appendLine()
		appendLine("Merged candidate list (${merged.size} unique after dedup)")
		appendLine(SECTION_SEPARATOR)
		if (merged.isEmpty()) {
			appendLine("  (no candidates)")
			return
		}
		for (candidate in merged) appendCandidate(candidate)
	}

	private fun StringBuilder.appendCandidate(candidate: Pkcs11DiagnosticsReport.Candidate) {
		val existsTag = if (candidate.exists) "✅" else "⚠️  not found"
		val sizeTag = candidate.sizeBytes?.let { " (${formatSize(it)})" } ?: ""
		appendLine("    ● ${candidate.name} $existsTag$sizeTag")
		appendLine("      ${candidate.path}")
	}

	private fun StringBuilder.appendP11Kit(truth: Pkcs11DiagnosticsReport.P11KitTruth?) {
		if (truth == null) return
		appendLine()
		appendLine("p11-kit out-of-process truth (Linux)")
		appendLine(SECTION_SEPARATOR)
		appendLine("  p11-kit --version:")
		appendIndentedBlock(truth.version, "    ", emptyMessage = "(unavailable)")
		appendLine("  pkg-config --variable=proxy_module p11-kit-1:")
		appendIndentedBlock(truth.pkgConfigProxyPath, "    ", emptyMessage = "(unavailable)")
		appendLine("  p11-kit list-modules:")
		appendIndentedBlock(truth.listModulesOutput, "    ", emptyMessage = "(unavailable)")
		appendLine("  p11-kit list-tokens:")
		appendIndentedBlock(truth.listTokensOutput, "    ", emptyMessage = "(unavailable)")
	}

	private fun StringBuilder.appendIndentedBlock(text: String?, indent: String, emptyMessage: String) {
		if (text.isNullOrBlank()) {
			appendLine("$indent$emptyMessage")
			return
		}
		text.lineSequence().forEach { line -> appendLine("$indent$line") }
	}

	private fun StringBuilder.appendPcscReaders(readers: List<String>) {
		appendLine()
		appendLine("PC/SC readers (${readers.size})")
		appendLine(SECTION_SEPARATOR)
		if (readers.isEmpty()) {
			appendLine("  (no readers detected — pcscd/winscard unreachable, or no readers connected)")
			return
		}
		for (name in readers) appendLine("  - $name")
	}

	private fun StringBuilder.appendProbes(probes: List<Pkcs11DiagnosticsReport.ProbeOutcome>) {
		appendLine()
		appendLine("Per-candidate subprocess probes (${probes.size})")
		appendLine(SECTION_SEPARATOR)
		if (probes.isEmpty()) {
			appendLine("  (no candidates were probed)")
			return
		}
		for (probe in probes) appendProbe(probe)
	}

	private fun StringBuilder.appendProbe(probe: Pkcs11DiagnosticsReport.ProbeOutcome) {
		val outcomeTag = when (probe.outcome) {
			Pkcs11DiagnosticsReport.ProbeOutcome.Outcome.SUCCESS -> "✅ SUCCESS"
			Pkcs11DiagnosticsReport.ProbeOutcome.Outcome.CRASHED -> "💥 CRASHED (exit ${probe.exitCode})"
			Pkcs11DiagnosticsReport.ProbeOutcome.Outcome.TIMED_OUT -> "⏱️  TIMED OUT"
			Pkcs11DiagnosticsReport.ProbeOutcome.Outcome.NO_COMMAND -> "❌ NO COMMAND"
		}
		val pidTag = probe.pid?.let { " (pid $it)" } ?: ""
		appendLine("  ● ${probe.name} — $outcomeTag in ${probe.totalMillis} ms$pidTag")
		appendLine("    ${probe.path}")
		probe.stderrSnippet?.let { stderr ->
			appendLine("    stderr:")
			appendIndentedBlock(stderr, "      ", emptyMessage = "")
		}
		if (probe.identities.isEmpty()) return
		appendLine("    identities (${probe.identities.size}):")
		for (identity in probe.identities) {
			val slotHex = "0x%X".format(identity.slotId)
			appendLine(
				"      - ${identity.label.ifBlank { "(blank label)" }} / serial ${identity.serialNumber} / slot ${identity.slotId} ($slotHex)"
			)
		}
	}

	private fun StringBuilder.appendTokens(tokens: List<Pkcs11DiagnosticsReport.TokenSummary>) {
		appendLine()
		appendLine("Final tokens as discovery would emit them (${tokens.size})")
		appendLine(SECTION_SEPARATOR)
		if (tokens.isEmpty()) {
			appendLine("  (none)")
			return
		}
		for (token in tokens) {
			val pinTag = if (token.requiresPin) "requires PIN" else "no PIN"
			appendLine("  ● ${token.id}  \"${token.name}\"  [${token.type}, $pinTag]")
			token.path?.let { appendLine("    Path: $it") }
		}
	}

	/**
	 * Render the "Route A" no-login enumeration section: whether each PKCS#11 token's
	 * certificates are visible through a SunPKCS#11 `KeyStore` opened without a PIN.
	 */
	private fun StringBuilder.appendNoLoginEnumeration(
		results: List<Pkcs11DiagnosticsReport.NoLoginEnumeration>,
	) {
		appendLine()
		appendLine("No-login certificate enumeration (Route A experiment)")
		appendLine(SECTION_SEPARATOR)
		appendLine("  Attempts KeyStore(\"PKCS11\").load(null, null) — a public session, no C_Login.")
		appendLine("  A signing certificate appearing here is readable WITHOUT a PIN, so the PIN")
		appendLine("  could be deferred to signing (the Adobe model).")
		if (results.isEmpty()) {
			appendLine("  (no PKCS#11 tokens discovered — nothing to probe)")
			return
		}
		for (result in results) appendNoLoginResult(result)
	}

	private fun StringBuilder.appendNoLoginResult(result: Pkcs11DiagnosticsReport.NoLoginEnumeration) {
		val slotTag = result.slotId?.let { " slot $it (0x%X)".format(it) } ?: " (default slot)"
		appendLine()
		appendLine("  ● ${result.tokenName}$slotTag")
		appendLine("    ${result.libraryPath}")
		if (!result.loaded) {
			appendLine("    ⚠️  no-login enumeration NOT viable — ${result.error}")
			return
		}
		if (result.entries.isEmpty()) {
			appendLine("    ✅ public session opened without a PIN — but 0 entries visible")
			appendLine("       (certs are private objects on this token, or SunPKCS11 hides certs")
			appendLine("        whose private key is invisible pre-login → Route A not usable here)")
			return
		}
		val plural = if (result.entries.size == 1) "entry" else "entries"
		appendLine("    ✅ public session opened without a PIN — ${result.entries.size} $plural visible:")
		for (entry in result.entries) {
			val kind = when {
				entry.isKeyEntry -> "key+cert"
				entry.isCertificateEntry -> "cert-only"
				else -> "other"
			}
			appendLine("      - ${entry.alias}  [$kind]")
			entry.subjectDN?.let { appendLine("        subject: $it") }
			entry.issuerDN?.let { appendLine("        issuer:  $it") }
			entry.serialNumber?.let { appendLine("        serial:  $it") }
		}
	}

	/**
	 * Render the raw, out-of-process no-`C_Login` certificate enumeration — the
	 * authoritative Route A/B premise check through OmniSign's own JNA stack.
	 */
	private fun StringBuilder.appendRawNoLoginEnumeration(
		scans: List<Pkcs11DiagnosticsReport.RawNoLoginCertScan>,
	) {
		appendLine()
		appendLine("No-login certificate enumeration — raw PKCS#11 (out-of-process)")
		appendLine(SECTION_SEPARATOR)
		appendLine("  C_FindObjects(CKO_CERTIFICATE) in a read-only session, no C_Login — the")
		appendLine("  same check as 'pkcs11-tool --list-objects --type cert' (no --login), via")
		appendLine("  OmniSign's JNA stack.  A signing cert here is a public object: it could")
		appendLine("  be listed with no PIN, deferring authentication to signing.")
		if (scans.isEmpty()) {
			appendLine("  (no PKCS#11 libraries to probe)")
			return
		}
		for (scan in scans) appendRawNoLoginScan(scan)
	}

	private fun StringBuilder.appendRawNoLoginScan(scan: Pkcs11DiagnosticsReport.RawNoLoginCertScan) {
		appendLine()
		appendLine("  ● ${scan.libraryPath}")
		if (!scan.subprocessSucceeded) {
			appendLine("    ⚠️  --certs subprocess did not succeed — see the Probes section above")
			return
		}
		if (scan.certificates.isEmpty()) {
			appendLine("    ✅ session opened without a PIN — 0 public certificate objects")
			appendLine("       (certificates are private on this token → Route A would not help here)")
			return
		}
		val plural = if (scan.certificates.size == 1) "certificate" else "certificates"
		appendLine("    ✅ ${scan.certificates.size} $plural readable WITHOUT a PIN:")
		for (cert in scan.certificates) {
			appendLine("      - subject: ${cert.subjectDN}")
			appendLine("        issuer:  ${cert.issuerDN}")
			appendLine("        serial:  ${cert.serialNumber}   slot ${cert.slotId}")
			appendLine("        CKA_ID:  ${cert.ckaId.ifBlank { "(none)" }}")
			appendLine("        label:   ${cert.label.ifBlank { "(none)" }}")
		}
	}

	private fun StringBuilder.appendFooter(totalElapsedMillis: Long) {
		appendLine()
		appendLine(SEPARATOR)
		appendLine("  Total diagnostic time: ${formatDuration(totalElapsedMillis)}")
		appendLine(SEPARATOR)
	}

	/**
	 * Format a byte count into a short human-readable string (e.g. `1.2 MB`).
	 */
	private fun formatSize(bytes: Long): String {
		if (bytes < KIB) return "$bytes B"
		if (bytes < MIB) return "%.1f KB".format(bytes.toDouble() / KIB)
		return "%.1f MB".format(bytes.toDouble() / MIB)
	}

	/**
	 * Format a wall-clock millisecond count into a short human-readable string.
	 */
	private fun formatDuration(millis: Long): String =
		if (millis < SECONDS_THRESHOLD) "$millis ms" else "%.2f s".format(millis.toDouble() / MILLIS_PER_SECOND)

	private companion object {
		const val SEPARATOR = "═════════════════════════════════════════════════════════════════════"
		const val SECTION_SEPARATOR = "─────────────────────────────────────────────────────────────────────"
		const val KIB = 1024L
		const val MIB = 1024L * 1024L
		const val MILLIS_PER_SECOND = 1000.0
		const val SECONDS_THRESHOLD = 1000L
	}
}
