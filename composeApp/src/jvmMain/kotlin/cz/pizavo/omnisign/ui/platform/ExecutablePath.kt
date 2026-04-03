package cz.pizavo.omnisign.ui.platform

import cz.pizavo.omnisign.data.service.SelfExecutableResolver
import org.koin.mp.KoinPlatform

/**
 * JVM implementation — delegates to the Koin-provided [SelfExecutableResolver].
 */
actual fun resolveExecutablePath(): String? =
    KoinPlatform.getKoin().get<SelfExecutableResolver>().resolve()
