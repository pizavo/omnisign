package cz.pizavo.omnisign.data.service

import java.io.File
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Unit tests for PKCS#11 middleware discovery in [Pkcs11Discoverer].
 *
 * Covers:
 * - JVM-bitness-aware candidate selection ([Pkcs11Discoverer.candidatesForOs])
 * - Filename-pattern heuristic ([Pkcs11Discoverer.isPkcs11FileName])
 * - Vendor name derivation ([Pkcs11Discoverer.deriveMiddlewareName])
 * - Deduplication and merge logic in [Pkcs11Discoverer.discoverTokens]
 */
class Pkcs11DiscovererTest {

    private fun discoverer() = Pkcs11Discoverer()

    @Test
    fun `64-bit JVM on Windows fallback list contains only Program Files paths, not System32`() {
        val paths = discoverer().candidatesForOs("windows 10", jvmIs64Bit = true).map { it.second }

        assertTrue(paths.none { it.contains("System32") || it.contains("SysWOW64") || it.contains("(x86)") },
            "System32/SysWOW64 paths must not appear in the fallback list — they are covered by dir-scan. " +
                "Found: ${paths.filter { it.contains("System32") || it.contains("SysWOW64") }}")
        assertTrue(paths.all { it.contains("Program Files") || it.contains("SoftHSM2") },
            "Expected only Program Files / standalone install paths in 64-bit fallback list")
    }

    @Test
    fun `32-bit JVM on Windows fallback list contains only Program Files x86 paths`() {
        val paths = discoverer().candidatesForOs("windows 10", jvmIs64Bit = false).map { it.second }

        assertTrue(paths.none { it.contains("System32") || it.contains("SysWOW64") },
            "System32/SysWOW64 paths must not appear in the fallback list — they are covered by dir-scan. " +
                "Found: ${paths.filter { it.contains("System32") || it.contains("SysWOW64") }}")
        assertTrue(
            paths.all { it.contains("(x86)") },
            "Expected only 32-bit Program Files (x86) paths but found: " +
                paths.filter { !it.contains("(x86)") },
        )
    }

    @Test
    fun `64-bit and 32-bit Windows candidate sets are disjoint`() {
        val d = discoverer()
        val paths64 = d.candidatesForOs("windows 10", jvmIs64Bit = true).map { it.second }.toSet()
        val paths32 = d.candidatesForOs("windows 10", jvmIs64Bit = false).map { it.second }.toSet()

        val overlap = paths64 intersect paths32
        assertTrue(overlap.isEmpty(), "Expected no overlap between 64-bit and 32-bit paths but found: $overlap")
    }

    @Test
    fun `64-bit JVM on Linux picks lib64 and multiarch paths`() {
        val paths = discoverer().candidatesForOs("linux", jvmIs64Bit = true).map { it.second }

        assertTrue(paths.any { it.contains("lib64") || it.contains("x86_64") || it.contains("aarch64") },
            "Expected at least one 64-bit Linux path")
    }

    @Test
    fun `32-bit JVM on Linux does not list lib64 or multiarch paths`() {
        val paths = discoverer().candidatesForOs("linux", jvmIs64Bit = false).map { it.second }

        assertTrue(paths.none { it.contains("lib64") || it.contains("x86_64") || it.contains("aarch64") },
            "Expected no 64-bit Linux paths but found: " +
                paths.filter { it.contains("lib64") || it.contains("x86_64") || it.contains("aarch64") })
    }

    @Test
    fun `isPkcs11FileName matches known PKCS11 naming patterns`() {
        val d = discoverer()
        assertTrue(d.isPkcs11FileName("eTPKCS11.dll"))
        assertTrue(d.isPkcs11FileName("opensc-pkcs11.dll"))
        assertTrue(d.isPkcs11FileName("libsofthsm2.so"))
        assertTrue(d.isPkcs11FileName("iidp11.dll"))
        assertTrue(d.isPkcs11FileName("cmP11.dll"))
        assertTrue(d.isPkcs11FileName("libcryptoki.so"))
    }

    @Test
    fun `isPkcs11FileName rejects unrelated DLLs`() {
        val d = discoverer()
        assertFalse(d.isPkcs11FileName("kernel32.dll"))
        assertFalse(d.isPkcs11FileName("ntdll.dll"))
        assertFalse(d.isPkcs11FileName("user32.dll"))
        assertFalse(d.isPkcs11FileName("libssl.so"))
    }

    @Test
    fun `deriveMiddlewareName identifies SafeNet eToken paths`() {
        val d = discoverer()
        assertEquals("SafeNet eToken", d.deriveMiddlewareName("C:\\Windows\\System32\\eTPKCS11.dll"))
        assertEquals("SafeNet eToken", d.deriveMiddlewareName("/usr/lib/libeTPkcs11.so"))
    }

    @Test
    fun `deriveMiddlewareName identifies Gemalto IDPrime paths`() {
        val d = discoverer()
        assertEquals("Thales/Gemalto IDPrime", d.deriveMiddlewareName("C:\\Windows\\System32\\gclib.dll"))
        assertEquals("Thales/Gemalto IDPrime", d.deriveMiddlewareName("/usr/lib/libgclib.so"))
    }

    @Test
    fun `deriveMiddlewareName identifies OpenSC paths`() {
        val d = discoverer()
        assertEquals("OpenSC", d.deriveMiddlewareName("/usr/lib/opensc-pkcs11.so"))
        assertEquals("OpenSC", d.deriveMiddlewareName("C:\\Windows\\System32\\opensc-pkcs11.dll"))
    }

    @Test
    fun `deriveMiddlewareName falls back to filename for unknown libraries`() {
        assertEquals("acme-token.dll", discoverer().deriveMiddlewareName("C:\\Some\\Path\\acme-token.dll"))
    }

    @Test
    fun `discoverTokens deduplicates same canonical path from multiple sources`() {
        val tmpFile = File.createTempFile("opensc-pkcs11", ".so").also { it.deleteOnExit() }

        val tokens = discoverer().discoverTokens(
            userPkcs11Libraries = listOf(
                "OpenSC (source 1)" to tmpFile.absolutePath,
                "OpenSC (source 2)" to tmpFile.absolutePath,
            )
        )

        assertEquals(1, tokens.filter { it.path == tmpFile.absolutePath }.size,
            "Duplicate canonical path should appear exactly once")
    }

    @Test
    fun `discoverTokens includes user-supplied library when file exists`() {
        val tmpFile = File.createTempFile("custom-pkcs11", ".so").also { it.deleteOnExit() }

        val tokens = discoverer().discoverTokens(
            userPkcs11Libraries = listOf("My Custom Token" to tmpFile.absolutePath)
        )

        assertTrue(tokens.any { it.path == tmpFile.absolutePath },
            "User-supplied library path should appear in discovered tokens")
    }

    @Test
    fun `discoverTokens ignores user-supplied library when file does not exist`() {
        val tokens = discoverer().discoverTokens(
            userPkcs11Libraries = listOf("Ghost Token" to "/tmp/does-not-exist-pkcs11.so")
        )

        assertFalse(tokens.any { it.path == "/tmp/does-not-exist-pkcs11.so" },
            "Non-existent user-supplied library should be excluded")
    }

    @Test
    fun `discoverViaOs returns empty list without throwing on unknown OS`() {
        val result = runCatching { discoverer().discoverViaOs(os = "haiku", jvmIs64Bit = true) }
        assertTrue(result.isSuccess, "discoverViaOs should never throw")
    }

    @Test
    fun `discoverTokens picks up PKCS11-named files from app-data drop directory`() {
        val dropDir = File.createTempFile("pkcs11-drop", "").also { it.delete(); it.mkdirs(); it.deleteOnExit() }
        val libFile = File(dropDir, "vendor-pkcs11.so").also { it.createNewFile(); it.deleteOnExit() }

        val tokens = discoverer().discoverTokens(appDataPkcs11Dir = dropDir)
        assertTrue(tokens.any { it.path == libFile.absolutePath },
            "Library placed in drop directory should be discovered automatically")

        dropDir.deleteRecursively()
    }

    @Test
    fun `discoverTokens ignores non-PKCS11-named files in drop directory`() {
        val dropDir = File.createTempFile("pkcs11-drop", "").also { it.delete(); it.mkdirs(); it.deleteOnExit() }
        val unrelated = File(dropDir, "readme.txt").also { it.createNewFile(); it.deleteOnExit() }

        val tokens = discoverer().discoverTokens(appDataPkcs11Dir = dropDir)
        assertFalse(tokens.any { it.path == unrelated.absolutePath },
            "Non-PKCS11 files in the drop directory should be ignored")

        dropDir.deleteRecursively()
    }
}
