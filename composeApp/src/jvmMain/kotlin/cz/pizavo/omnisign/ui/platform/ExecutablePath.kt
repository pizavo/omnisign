package cz.pizavo.omnisign.ui.platform

import cz.pizavo.omnisign.data.service.SelfExecutableResolver

/**
 * JVM implementation — delegates to [SelfExecutableResolver].
 */
actual fun resolveExecutablePath(): String? =
    SelfExecutableResolver.resolve()

