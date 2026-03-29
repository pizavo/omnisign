## ──────────────────────────────────────────────────────────────────────────────
## ProGuard / R8 rules for OmniSign Desktop (Compose Multiplatform)
##
## Every library below is an *optional* dependency that some transitive JAR
## references but is never needed at runtime.  The `-dontwarn` directives tell
## ProGuard to stop treating these unresolved references as fatal errors.
## ──────────────────────────────────────────────────────────────────────────────

# --- OSGi / BND (Woodstox, JAXB, etc. ship optional OSGi activators) --------
-dontwarn org.osgi.framework.**
-dontwarn aQute.bnd.annotation.**

# --- GraalVM native-image (Angus Activation ships a Feature class) -----------
-dontwarn org.graalvm.nativeimage.**

# --- FastInfoset / StAX-Ex (optional JAXB serialisation codecs) --------------
-dontwarn com.sun.xml.fastinfoset.**
-dontwarn org.jvnet.fastinfoset.**
-dontwarn org.jvnet.staxex.**

# --- Conscrypt (optional TLS provider for Apache HttpClient 5) ---------------
-dontwarn org.conscrypt.**

# --- Brotli (optional HTTP content-encoding for Apache HttpClient 5) ---------
-dontwarn org.brotli.dec.**

# --- Legacy logging frameworks (bridges in commons-logging) ------------------
-dontwarn org.apache.avalon.framework.logger.**
-dontwarn org.apache.log4j.**
-dontwarn org.apache.logging.log4j.**
-dontwarn org.apache.log.**

# --- Servlet API (commons-logging ServletContextCleaner) ---------------------
-dontwarn javax.servlet.**

# --- JSR 305 / 308 annotations (ByteBuddy nullability qualifiers) ------------
-dontwarn javax.annotation.**

# --- FindBugs annotations (ByteBuddy agent) ---------------------------------
-dontwarn edu.umd.cs.findbugs.annotations.**

# --- Decoroutinator bytecode-processor intrinsics ----------------------------
-dontwarn dev.reformator.bytecodeprocessor.intrinsics.**

# --- Woodstox shaded MSV driver (never used at runtime) ----------------------
-dontwarn com.ctc.wstx.shaded.msv_core.driver.textui.Driver

# --- VeraPDF Structured-Accessibility module (optional) ----------------------
-dontwarn org.verapdf.gf.model.impl.sa.**

# --- D-Bus: API drift between javakeyring's expected version and actual ------
-dontwarn com.github.javakeyring.internal.kde.KWalletBackend
-dontwarn org.freedesktop.secret.handlers.MessageHandler
-dontwarn org.freedesktop.dbus.errors.Error

# --- Signature-polymorphic methods (MethodHandle.invokeExact, VarHandle) -----
#     ProGuard cannot resolve these because they are compiler-intrinsic; the
#     JVM handles them via invokedynamic / signature polymorphism at runtime.
-dontwarn java.lang.invoke.MethodHandle
-dontwarn java.lang.invoke.VarHandle

# --- commons-logging Log4j 2 adapter (superclass in an optional JAR) ---------
-dontwarn org.apache.commons.logging.impl.Log4jApiLogFactory$LogAdapter

