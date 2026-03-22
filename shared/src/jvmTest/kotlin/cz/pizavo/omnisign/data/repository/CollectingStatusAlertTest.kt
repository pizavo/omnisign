package cz.pizavo.omnisign.data.repository

import eu.europa.esig.dss.alert.status.MessageStatus
import eu.europa.esig.dss.alert.status.ObjectStatus
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldStartWith

/**
 * Verifies the [CollectingStatusAlert] collects, deduplicates, and drains
 * alert messages as expected by the signing/archiving/validation repositories.
 */
class CollectingStatusAlertTest : FunSpec({
	
	test("drain returns empty list when no alerts have been fired") {
		val alert = CollectingStatusAlert()
		alert.drain().shouldBeEmpty()
	}
	
	test("drain returns all collected messages in order") {
		val alert = CollectingStatusAlert()
		
		val status1 = ObjectStatus().apply { message = "first warning" }
		val status2 = ObjectStatus().apply { message = "second warning" }
		
		alert.alert(status1)
		alert.alert(status2)
		
		alert.drain().shouldContainExactly("first warning", "second warning")
	}
	
	test("drain clears the buffer so subsequent drain returns empty") {
		val alert = CollectingStatusAlert()
		
		val status = ObjectStatus().apply { message = "ephemeral" }
		alert.alert(status)
		
		alert.drain() shouldHaveSize 1
		alert.drain().shouldBeEmpty()
	}
	
	test("blank and null messages are ignored") {
		val alert = CollectingStatusAlert()
		
		alert.alert(ObjectStatus())
		alert.alert(ObjectStatus().apply { message = "" })
		alert.alert(ObjectStatus().apply { message = "   " })
		alert.alert(ObjectStatus().apply { message = "real warning" })
		
		alert.drain().shouldContainExactly("real warning")
	}
	
	test("messages collected after drain are returned by next drain") {
		val alert = CollectingStatusAlert()
		
		alert.alert(ObjectStatus().apply { message = "batch-1" })
		alert.drain()
		
		alert.alert(ObjectStatus().apply { message = "batch-2" })
		alert.drain().shouldContainExactly("batch-2")
	}
	
	test("ObjectStatus with per-object details appends them to the message") {
		val alert = CollectingStatusAlert()
		
		val status = ObjectStatus().apply {
			message = "Revocation data is missing for one or more certificate(s)."
			addRelatedObjectIdentifierAndErrorMessage("C-ABCD1234", "Revocation data is skipped for untrusted chain!")
			addRelatedObjectIdentifierAndErrorMessage("C-EFGH5678", "No CRL found!")
		}
		
		alert.alert(status)
		
		val result = alert.drain()
		result shouldHaveSize 1
		result[0] shouldStartWith "Revocation data is missing for one or more certificate(s)."
		result[0] shouldContain "C-ABCD1234"
		result[0] shouldContain "Revocation data is skipped for untrusted chain!"
		result[0] shouldContain "C-EFGH5678"
		result[0] shouldContain "No CRL found!"
	}
	
	test("ObjectStatus with details but no base message still produces output") {
		val status = ObjectStatus().apply {
			addRelatedObjectIdentifierAndErrorMessage("C-XYZ", "Some detail")
		}
		
		val formatted = CollectingStatusAlert.formatStatus(status)
		formatted shouldContain "C-XYZ"
		formatted shouldContain "Some detail"
	}
	
	test("formatStatus returns base message for plain MessageStatus") {
		val status = MessageStatus().apply { message = "plain warning" }
		CollectingStatusAlert.formatStatus(status) shouldBe "plain warning"
	}
	
	test("formatStatus returns null for MessageStatus with no message") {
		val status = MessageStatus()
		CollectingStatusAlert.formatStatus(status).shouldBeNull()
	}
})

