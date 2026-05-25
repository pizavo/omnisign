package cz.pizavo.omnisign.data.service

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.mockk.every
import io.mockk.mockk
import java.io.File

/**
 * Verifies [Pkcs11LibP11KitModuleResolver]'s policy on top of the libp11-kit subprocess
 * enumeration: dropping the p11-kit trust policy module, dropping paths that no longer exist
 * on disk, de-duplication, and graceful empties.  The native enumeration itself
 * ([Pkcs11Prober.discoverModulePaths]) is mocked.
 */
class Pkcs11LibP11KitModuleResolverTest : FunSpec({

	fun resolver(prober: Pkcs11Prober) = Pkcs11LibP11KitModuleResolver(prober = prober)

	test("resolveModulePaths returns existing module paths and drops the p11-kit trust module") {
		val opensc = File.createTempFile("opensc-pkcs11", ".so").also { it.deleteOnExit() }
		val trust = File.createTempFile("p11-kit-trust", ".so").also { it.deleteOnExit() }
		val prober = mockk<Pkcs11Prober> {
			every { discoverModulePaths(any()) } returns listOf(opensc.absolutePath, trust.absolutePath)
		}

		resolver(prober).resolveModulePaths().shouldContainExactly(opensc.absolutePath)
	}

	test("resolveModulePaths drops paths that do not exist on disk") {
		val prober = mockk<Pkcs11Prober> {
			every { discoverModulePaths(any()) } returns listOf("/nonexistent/opensc-pkcs11.so")
		}

		resolver(prober).resolveModulePaths().shouldBeEmpty()
	}

	test("resolveModulePaths returns empty when only the p11-kit trust module is registered") {
		val trust = File.createTempFile("p11-kit-trust", ".so").also { it.deleteOnExit() }
		val prober = mockk<Pkcs11Prober> {
			every { discoverModulePaths(any()) } returns listOf(trust.absolutePath)
		}

		resolver(prober).resolveModulePaths().shouldBeEmpty()
	}

	test("resolveModulePaths de-duplicates repeated paths") {
		val opensc = File.createTempFile("opensc-pkcs11", ".so").also { it.deleteOnExit() }
		val prober = mockk<Pkcs11Prober> {
			every { discoverModulePaths(any()) } returns listOf(opensc.absolutePath, opensc.absolutePath)
		}

		resolver(prober).resolveModulePaths().shouldHaveSize(1)
	}

	test("resolveModulePaths returns empty when nothing is discovered") {
		val prober = mockk<Pkcs11Prober> {
			every { discoverModulePaths(any()) } returns emptyList()
		}

		resolver(prober).resolveModulePaths().shouldBeEmpty()
	}
})
