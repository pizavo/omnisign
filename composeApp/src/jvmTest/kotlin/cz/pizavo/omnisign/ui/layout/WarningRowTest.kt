package cz.pizavo.omnisign.ui.layout

import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runComposeUiTest
import cz.pizavo.omnisign.domain.model.result.AnnotatedWarning
import cz.pizavo.omnisign.domain.model.text.LocalizableText
import cz.pizavo.omnisign.domain.model.text.MessageKey
import io.kotest.core.spec.style.FunSpec
import omnisign.composeapp.generated.resources.Res
import omnisign.composeapp.generated.resources.action_close
import omnisign.composeapp.generated.resources.warningrow_affected_certificates
import org.jetbrains.compose.resources.getString

/**
 * UI tests for [WarningRow] and the dialog listing a warning's affected certificates, which opens from
 * inside the warning's selectable text.
 */
@OptIn(ExperimentalTestApi::class)
class WarningRowTest : FunSpec({

	val warning = AnnotatedWarning(
		summary = LocalizableText.of(MessageKey.WARNING_REVOCATION_NOT_FOUND, "2 certificates"),
		affectedIds = listOf("C-AAAA", "C-BBBB"),
		idNames = mapOf("C-AAAA" to "Alice"),
	)

	test("the affected certificates dialog lists a named certificate with its id and an unnamed one by its id") {
		runComposeUiTest {
			setContent { WarningRow(warning) }
			onNode(hasClickAction()).performClick()

			onNodeWithText("Alice").assertExists()
			onNodeWithText("C-AAAA").assertExists()
			onNodeWithText("C-BBBB").assertExists()
		}
	}

	test("a mouse press on the affected certificates dialog's title leaves it open, without a crash") {
		val title = getString(Res.string.warningrow_affected_certificates)
		runComposeUiTest {
			setContent { WarningRow(warning) }
			onNode(hasClickAction()).performClick()

			onNodeWithText(title).performMouseInput { click() }

			onNodeWithText(title).assertExists()
			onNodeWithText("Alice").assertExists()
		}
	}

	test("a mouse click on the affected certificates dialog's Close label closes it, without a crash") {
		val title = getString(Res.string.warningrow_affected_certificates)
		val close = getString(Res.string.action_close)
		runComposeUiTest {
			setContent { WarningRow(warning) }
			onNode(hasClickAction()).performClick()
			onNodeWithText(title).assertExists()

			onNodeWithText(close).performMouseInput { click() }

			onNodeWithText(title).assertDoesNotExist()
		}
	}
})
