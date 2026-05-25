package cz.pizavo.omnisign.data.service

import com.sun.jna.Library
import com.sun.jna.Pointer

/**
 * JNA binding for the minimal libp11-kit surface needed to enumerate the configured PKCS#11
 * module paths **without** initialising them.
 *
 * Only two functions are bound, both read-only with respect to token state.
 * [p11_kit_modules_load] performs the `dlopen` of every module registered with p11-kit —
 * honouring its `.module` configuration, `disable-in` / priority directives, and module
 * directory resolution — but does **not** call `C_Initialize`.
 * [p11_kit_module_get_filename] then reads the resolved on-disk path off each loaded module
 * handle.  This is the "load, don't initialise" discovery path: it never reaches the
 * `C_Initialize` phase where misbehaving middleware (e.g. SafeNet eToken) hangs or crashes.
 *
 * Used exclusively from inside [Pkcs11ModuleDiscoveryWorker] (subprocess) so that a faulty
 * module's `dlopen` constructor cannot take down the host JVM.  No in-process consumer of
 * this binding exists.
 *
 * The `char*` returned by [p11_kit_module_get_filename] is mapped to a Kotlin [String]; JNA
 * copies the C string but does not free the original allocation.  The leak is intentional
 * and harmless — the worker process exits immediately after enumeration, so the OS reclaims
 * everything.  For the same reason the module array is never released via
 * `p11_kit_modules_release`: nothing was initialised, and process death is the cleanup.
 */
internal interface P11KitLib : Library {

	/**
	 * Load every PKCS#11 module configured with p11-kit, returning a NULL-terminated array
	 * of `CK_FUNCTION_LIST*` handles (i.e. a `CK_FUNCTION_LIST**`).
	 *
	 * Each module is `dlopen`ed but **not** initialised — no `C_Initialize` is called, so the
	 * dangerous initialisation phase is never reached.  Returns `null` on failure.
	 *
	 * @param reserved Reserved by the API; must be `null`.
	 * @param flags Load flags; [P11_KIT_MODULE_LOAD_FLAGS_NONE] selects managed, uninitialised
	 *   loading.
	 */
	fun p11_kit_modules_load(reserved: String?, flags: Int): Pointer?

	/**
	 * Return the resolved on-disk filename of a loaded PKCS#11 module, or `null` when the
	 * module has no associated file.
	 *
	 * @param module A single `CK_FUNCTION_LIST*` handle from the array returned by
	 *   [p11_kit_modules_load].
	 */
	fun p11_kit_module_get_filename(module: Pointer): String?
}

/**
 * Flag value for [P11KitLib.p11_kit_modules_load] selecting the default managed,
 * **uninitialised** load: modules are `dlopen`ed but `C_Initialize` is not called.
 */
internal const val P11_KIT_MODULE_LOAD_FLAGS_NONE = 0
