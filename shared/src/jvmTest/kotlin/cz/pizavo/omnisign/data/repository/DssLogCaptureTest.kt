package cz.pizavo.omnisign.data.repository

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly

/**
 * Verifies [DssLogCapture] start/stop lifecycle and message collection.
 *
 * These tests exercise the reflection-based Logback integration which is available
 * in the test runtime because `logback-classic` is a transitive dependency of the
 * DSS libraries.
 */
class DssLogCaptureTest : FunSpec({
	
	test("stop returns empty list when no log events were emitted") {
		val capture = DssLogCapture()
		capture.start()
		val result = capture.stop()
		result.shouldBeEmpty()
	}
	
	test("stop returns empty list when start was never called") {
		val capture = DssLogCapture()
		capture.stop().shouldBeEmpty()
	}
	
	test("captures WARN messages from monitored loggers") {
		val loggerName = "eu.europa.esig.dss.spi.validation.SignatureValidationContext"
		val capture = DssLogCapture(listOf(loggerName))
		capture.start()
		
		val logger = org.slf4j.LoggerFactory.getLogger(loggerName)
		logger.warn("test capture message")
		
		val result = capture.stop()
		result.shouldContainExactly("test capture message")
	}
	
	test("does not capture DEBUG messages") {
		val loggerName = "eu.europa.esig.dss.spi.validation.SignatureValidationContext"
		val capture = DssLogCapture(listOf(loggerName))
		capture.start()
		
		val logger = org.slf4j.LoggerFactory.getLogger(loggerName)
		logger.debug("should be ignored")
		logger.warn("should be captured")
		
		val result = capture.stop()
		result.shouldContainExactly("should be captured")
	}
	
	test("deduplicates repeated messages") {
		val loggerName = "eu.europa.esig.dss.spi.validation.RevocationDataVerifier"
		val capture = DssLogCapture(listOf(loggerName))
		capture.start()
		
		val logger = org.slf4j.LoggerFactory.getLogger(loggerName)
		logger.warn("duplicate warning")
		logger.warn("duplicate warning")
		logger.warn("unique warning")
		
		val result = capture.stop()
		result.shouldContainExactly("duplicate warning", "unique warning")
	}
	
	test("stop detaches appender so subsequent logs are not captured") {
		val loggerName = "eu.europa.esig.dss.spi.CertificateExtensionsUtils"
		val capture = DssLogCapture(listOf(loggerName))
		capture.start()
		
		val logger = org.slf4j.LoggerFactory.getLogger(loggerName)
		logger.warn("first")
		capture.stop()
		
		logger.warn("after stop")
		capture.stop().shouldBeEmpty()
	}
})

