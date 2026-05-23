package cz.pizavo.omnisign.config

import cz.pizavo.omnisign.domain.model.config.GlobalConfig

/**
 * Provider-authored server signing/validation policy, loaded read-only from `signing.yml`.
 *
 * This is the server's equivalent of the desktop "global settings + profiles": it carries
 * everything about how the server signs and validates, while `server.yml` keeps to how the
 * server is exposed and secured. It is a dedicated provider-facing schema rather than a
 * serialization of [cz.pizavo.omnisign.domain.model.config.AppConfig], so it can omit the
 * fields that are meaningless server-side (active profile, trusted-list drafts, renewal
 * jobs, OS scheduler) and is folded into an `AppConfig` by [SigningConfigLoader].
 *
 * Secrets (e.g. a TSA HTTP Basic password) never live here — they are resolved from the
 * environment / OS credential store.
 *
 * @property global Global signing and validation defaults. Mirrors [GlobalConfig], including
 *   the process-global token/trust knobs (`customPkcs11Libraries`,
 *   `pkcs11ProbeTimeoutSeconds`, `trustedListRefreshIntervalHours`).
 * @property profiles The named profiles, supplied via any combination of [ProfileSources].
 */
data class SigningConfig(
	val global: GlobalConfig = GlobalConfig(),
	val profiles: ProfileSources = ProfileSources(),
)
