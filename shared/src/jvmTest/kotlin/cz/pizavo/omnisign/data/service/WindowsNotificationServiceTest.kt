package cz.pizavo.omnisign.data.service

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.PrintStream
import java.nio.file.Path
import kotlin.io.encoding.Base64
import kotlin.io.path.absolutePathString
import kotlin.io.path.readBytes
import kotlin.io.path.writeBytes

/**
 * Verifies that [WindowsNotificationService] keeps the text of a toast out of the PowerShell script that
 * shows it: every toast runs the same script, and the toast's XML and the icon's path travel in environment
 * variables, so that an apostrophe or a typographic quote in a job name, an error message or a path can
 * neither break the script nor run as code. On Windows, PowerShell parses the script without errors, and
 * loads a toast whose body is written to run as commands as plain text, typographic quotes and all.
 *
 * [WindowsNotificationService.notify] builds the process for its title, body and urgency, with the icon it
 * extracts once, and reports a process that fails to start rather than throwing.
 *
 * No test shows a toast or writes to the registry: each builds the process that
 * [WindowsNotificationService.notify] would start without starting it, or calls `notify` on a service that only
 * keeps that process and extracts its icon into a temporary directory, and on Windows runs either PowerShell's
 * parser on the script or [WindowsNotificationService.LOAD_TOAST_XML] alone.
 */
class WindowsNotificationServiceTest : FunSpec({

	val service = WindowsNotificationService()
	val onWindows = System.getProperty("os.name", "").lowercase().contains("win")
	val title = "OmniSign — Obnova dokončena (Jan’s ‚smlouvy‘, Ann's ‛notes‛ & <drafts>)"
	val payload = "x’+(New-Item -ItemType File -Path ’ran’)+’"
	val iconPath = "C:\\Users\\O’Brien\\AppData\\Roaming\\omnisign\\omnisign-toast-icon.png"

	/** [script] as PowerShell's `-EncodedCommand` takes it: Base64 of its UTF-16LE bytes. */
	fun encoded(script: String): String = Base64.Default.encode(script.toByteArray(Charsets.UTF_16LE))

	/** The script that [process] hands PowerShell, decoded from the argument after `-EncodedCommand`. */
	fun scriptOf(process: ProcessBuilder): String =
		Base64.Default.decode(process.command().last()).toString(Charsets.UTF_16LE)

	/**
	 * Run [process] with [script] in place of the one it carries, keeping its program, its arguments and its
	 * environment, and return what PowerShell exited with and wrote, its errors included. Progress records,
	 * which PowerShell would write to standard error as CLIXML, are turned off.
	 */
	fun runInstead(process: ProcessBuilder, script: String): Pair<Int, String> {
		val quiet = "\$ProgressPreference = 'SilentlyContinue'\n" + script
		process.command(process.command().dropLast(1) + encoded(quiet)).redirectErrorStream(true)
		val running = process.start()
		val output = running.inputStream.readBytes().toString(Charsets.UTF_8)
		return running.waitFor() to output
	}

	/** The text of each `text` element in the toast XML [xml], its predefined entities decoded. */
	fun textsOf(xml: String): List<String> =
		Regex("<text>(.*?)</text>").findAll(xml).map { match ->
			match.groupValues[1].replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"")
				.replace("&apos;", "'").replace("&amp;", "&")
		}.toList()

	test("every toast runs the same script, which holds none of the toast's text") {
		val first = service.toastProcess(title, payload, NotificationUrgency.CRITICAL, iconPath)
		val second = service.toastProcess("Other", "Text", NotificationUrgency.LOW, null)

		first.command() shouldBe second.command()
		first.command().dropLast(1) shouldBe listOf("powershell", "-NoProfile", "-NonInteractive", "-EncodedCommand")
		val script = scriptOf(first)
		script shouldBe WindowsNotificationService.SCRIPT
		script shouldNotContain "Jan"
		script shouldNotContain "New-Item -ItemType File"
		script shouldNotContain "O’Brien"
	}

	test("the script reads the toast's XML and the icon's path from the environment") {
		val script = WindowsNotificationService.SCRIPT

		script shouldContain "\$xml.LoadXml(\$env:${WindowsNotificationService.TOAST_XML_VARIABLE})"
		script shouldContain "if (\$env:${WindowsNotificationService.ICON_PATH_VARIABLE}) {"
		script shouldContain "-Name IconUri -Value \$env:${WindowsNotificationService.ICON_PATH_VARIABLE} "
	}

	test("the toast's XML travels in the environment, with only its text's &, < and > escaped") {
		val process = service.toastProcess(title, payload, NotificationUrgency.NORMAL, null)

		process.environment()[WindowsNotificationService.TOAST_XML_VARIABLE] shouldBe
			"<toast scenario='default'><visual><binding template='ToastGeneric'>" +
			"<text>OmniSign — Obnova dokončena (Jan’s ‚smlouvy‘, " +
			"Ann's ‛notes‛ &amp; &lt;drafts&gt;)</text>" +
			"<text>x’+(New-Item -ItemType File -Path ’ran’)+’</text>" +
			"</binding></visual></toast>"
	}

	test("a critical toast takes the alarm scenario, and a low or normal one the default scenario") {
		val scenarioOf = { urgency: NotificationUrgency ->
			service.toastProcess(title, payload, urgency, null)
				.environment()[WindowsNotificationService.TOAST_XML_VARIABLE].orEmpty()
				.substringAfter("<toast scenario='").substringBefore("'")
		}

		scenarioOf(NotificationUrgency.CRITICAL) shouldBe "alarm"
		scenarioOf(NotificationUrgency.NORMAL) shouldBe "default"
		scenarioOf(NotificationUrgency.LOW) shouldBe "default"
	}

	test("the icon's path travels in the environment, which holds an empty one when there is no icon") {
		service.toastProcess(title, payload, NotificationUrgency.NORMAL, iconPath)
			.environment()[WindowsNotificationService.ICON_PATH_VARIABLE] shouldBe iconPath
		service.toastProcess(title, payload, NotificationUrgency.NORMAL, null)
			.environment()[WindowsNotificationService.ICON_PATH_VARIABLE] shouldBe ""
	}

	/**
	 * A [WindowsNotificationService] that extracts its icon into [directory] and adds each process
	 * [WindowsNotificationService.notify] would start to [started] instead of starting it.
	 */
	fun keeping(directory: Path, started: MutableList<ProcessBuilder>) =
		WindowsNotificationService(iconDirectory = { directory }, run = { process -> started += process })

	test("notify starts the process for its title, body and urgency, with the icon it extracts") {
		val directory = tempdir().toPath().resolve("config")
		val started = mutableListOf<ProcessBuilder>()

		keeping(directory, started).notify(title, payload, NotificationUrgency.CRITICAL)

		val process = started.single()
		val icon = directory.resolve("omnisign-toast-icon.png")
		val xml = process.environment()[WindowsNotificationService.TOAST_XML_VARIABLE].orEmpty()
		textsOf(xml) shouldContainExactly listOf(title, payload)
		xml shouldContain "<toast scenario='alarm'>"
		scriptOf(process) shouldBe WindowsNotificationService.SCRIPT
		process.environment()[WindowsNotificationService.ICON_PATH_VARIABLE] shouldBe icon.absolutePathString()
		val bundled = WindowsNotificationService::class.java.getResourceAsStream("/omnisign-toast-icon.png")
			.shouldNotBeNull().use { it.readBytes() }
		icon.readBytes() shouldBe bundled
	}

	test("notify keeps the icon it extracted before") {
		val directory = tempdir().toPath()
		val icon = directory.resolve("omnisign-toast-icon.png").apply { writeBytes(byteArrayOf(1, 2, 3)) }
		val started = mutableListOf<ProcessBuilder>()

		keeping(directory, started).notify(title, payload, NotificationUrgency.NORMAL)

		icon.readBytes() shouldBe byteArrayOf(1, 2, 3)
		started.single().environment()[WindowsNotificationService.ICON_PATH_VARIABLE] shouldBe
			icon.absolutePathString()
	}

	test("notify starts the process without an icon when it cannot extract one") {
		val started = mutableListOf<ProcessBuilder>()

		WindowsNotificationService(iconDirectory = { error("no config directory") }, run = { started += it })
			.notify(title, payload, NotificationUrgency.NORMAL)

		started.single().environment()[WindowsNotificationService.ICON_PATH_VARIABLE] shouldBe ""
	}

	test("notify writes a process that fails to start to standard error rather than throwing") {
		val directory = tempdir().toPath()
		val errors = ByteArrayOutputStream()
		val original = System.err
		System.setErr(PrintStream(errors, true, Charsets.UTF_8))
		try {
			WindowsNotificationService(
				iconDirectory = { directory },
				run = { throw IOException("powershell not found") },
			).notify(title, payload, NotificationUrgency.NORMAL)
		} finally {
			System.setErr(original)
		}

		errors.toString(Charsets.UTF_8) shouldContain
			"omnisign: Windows toast notification failed: powershell not found"
	}

	test("PowerShell parses the script that shows a toast without an error").config(enabled = onWindows) {
		val process = service.toastProcess(title, payload, NotificationUrgency.CRITICAL, iconPath)
		process.environment()["OMNISIGN_TEST_SCRIPT"] = scriptOf(process)

		val (exitCode, output) = runInstead(
			process,
			$$"""
				$errors = $null
				[System.Management.Automation.Language.Parser]::ParseInput($env:OMNISIGN_TEST_SCRIPT, [ref]$null, [ref]$errors) | Out-Null
				if ($errors.Count -gt 0) { $errors | ForEach-Object { $_.Message }; exit 1 }
				'parsed'
			""".trimIndent(),
		)

		withClue(output) {
			exitCode shouldBe 0
			output shouldContain "parsed"
		}
	}

	test("PowerShell loads the toast's text from the environment as text, quotes and commands alike")
		.config(enabled = onWindows) {
			val marker = tempdir().resolve("ran")
			val body = "x’+(New-Item -ItemType File -Path ’${marker.absolutePath}’)+’"
			val process = service.toastProcess(title, body, NotificationUrgency.CRITICAL, iconPath)

			val (exitCode, output) = runInstead(
				process,
				WindowsNotificationService.LOAD_TOAST_XML + "\n" + $$"""
					$bytes = [Text.Encoding]::UTF8.GetBytes($xml.GetXml())
					$out = [Console]::OpenStandardOutput()
					$out.Write($bytes, 0, $bytes.Length)
					$out.Flush()
				""".trimIndent(),
			)

			withClue(output) {
				exitCode shouldBe 0
				marker.exists() shouldBe false
				textsOf(output) shouldContainExactly listOf(title, body)
			}
		}
})
