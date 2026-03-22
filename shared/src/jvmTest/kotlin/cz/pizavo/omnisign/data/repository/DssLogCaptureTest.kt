package cz.pizavo.omnisign.data.repository

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly

/**
 * Verifies [DssLogCapture] parent-level capture lifecycle and message collection.
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
	
	test("captures WARN messages from the parent logger itself") {
		val parentName = "eu.europa.esig"
		val capture = DssLogCapture(listOf(parentName))
		capture.start()
		
		val logger = org.slf4j.LoggerFactory.getLogger(parentName)
		logger.warn("parent level message")
		
		val result = capture.stop()
		result.shouldContainExactly("parent level message")
	}
	
	test("captures WARN messages from child loggers via parent appender") {
		val parentName = "eu.europa.esig"
		val childName = "eu.europa.esig.dss.spi.validation.RevocationDataLoadingStrategy"
		val capture = DssLogCapture(listOf(parentName))
		capture.start()
		
		val logger = org.slf4j.LoggerFactory.getLogger(childName)
		logger.warn("OCSP DSS Exception: Unable to retrieve OCSP response")
		
		val result = capture.stop()
		result.shouldContainExactly("OCSP DSS Exception: Unable to retrieve OCSP response")
	}
	
	test("does not capture DEBUG messages from child loggers") {
		val parentName = "eu.europa.esig"
		val childName = "eu.europa.esig.dss.spi.validation.SignatureValidationContext"
		val capture = DssLogCapture(listOf(parentName))
		capture.start()
		
		val logger = org.slf4j.LoggerFactory.getLogger(childName)
		logger.debug("should be ignored")
		logger.warn("should be captured")
		
		val result = capture.stop()
		result.shouldContainExactly("should be captured")
	}
	
	test("deduplicates repeated messages") {
		val parentName = "eu.europa.esig"
		val childName = "eu.europa.esig.dss.spi.validation.RevocationDataVerifier"
		val capture = DssLogCapture(listOf(parentName))
		capture.start()
		
		val logger = org.slf4j.LoggerFactory.getLogger(childName)
		logger.warn("duplicate warning")
		logger.warn("duplicate warning")
		logger.warn("unique warning")
		
		val result = capture.stop()
		result.shouldContainExactly("duplicate warning", "unique warning")
	}
	
	test("excluded prefixes are filtered out") {
		val parentName = "eu.europa.esig"
		val excludedChild = "eu.europa.esig.dss.tsl.runnable.SomeTask"
		val includedChild = "eu.europa.esig.dss.spi.validation.SomeValidator"
		val capture = DssLogCapture(
			listOf(parentName),
			excludedPrefixes = listOf("eu.europa.esig.dss.tsl."),
		)
		capture.start()
		
		org.slf4j.LoggerFactory.getLogger(excludedChild).warn("tsl noise")
		org.slf4j.LoggerFactory.getLogger(includedChild).warn("useful warning")
		
		val result = capture.stop()
		result.shouldContainExactly("useful warning")
	}
	
	test("stop detaches appender so subsequent logs are not captured") {
		val parentName = "eu.europa.esig"
		val childName = "eu.europa.esig.dss.spi.CertificateExtensionsUtils"
		val capture = DssLogCapture(listOf(parentName))
		capture.start()
		
		val logger = org.slf4j.LoggerFactory.getLogger(childName)
		logger.warn("first")
		capture.stop()
		
		logger.warn("after stop")
		capture.stop().shouldBeEmpty()
	}
	
	test("captures messages from multiple different child loggers in one session") {
		val parentName = "eu.europa.esig"
		val capture = DssLogCapture(listOf(parentName))
		capture.start()
		
		org.slf4j.LoggerFactory.getLogger("eu.europa.esig.dss.spi.validation.RevocationDataVerifier")
			.warn("revocation warning")
		org.slf4j.LoggerFactory.getLogger("eu.europa.esig.dss.service.tsp.OnlineTSPSource")
			.warn("tsp warning")
		org.slf4j.LoggerFactory.getLogger("eu.europa.esig.dss.spi.CertificateExtensionsUtils")
			.warn("cert warning")
		
		val result = capture.stop()
		result.shouldContainExactly("revocation warning", "tsp warning", "cert warning")
	}
})

