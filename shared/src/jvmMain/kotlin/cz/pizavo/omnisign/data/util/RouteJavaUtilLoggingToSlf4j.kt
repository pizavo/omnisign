package cz.pizavo.omnisign.data.util

import org.slf4j.bridge.SLF4JBridgeHandler

/**
 * Route all `java.util.logging` output through SLF4J from now on, by putting the SLF4J project's
 * [SLF4JBridgeHandler] in place of the JUL root logger's handlers, by default the console handler
 * that prints to stderr in a two-line format of its own. A library that logs through JUL then goes
 * through the Logback configuration like the rest of the application.
 *
 * The library this is for is veraPDF, which DSS runs to check PDF/A conformance: its parser warns
 * about every structural fault it tolerates in a document, as PDFBox's does, and each Logback
 * configuration keeps both at ERROR.
 *
 * JUL applies its own threshold before the bridge sees a record, INFO unless configured otherwise,
 * so a DEBUG root logger does not pull in the debug output of the JDK and of the other libraries
 * that log through JUL, such as JAXB and JNA.
 *
 * The root logger's handlers are process-global, so call this once at host startup, before anything
 * logs through JUL. Calling it again still leaves a single bridge.
 */
fun routeJavaUtilLoggingToSlf4j() {
	SLF4JBridgeHandler.removeHandlersForRootLogger()
	SLF4JBridgeHandler.install()
}
