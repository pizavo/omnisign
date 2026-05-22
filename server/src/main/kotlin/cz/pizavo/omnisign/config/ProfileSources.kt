package cz.pizavo.omnisign.config

import cz.pizavo.omnisign.domain.model.config.ProfileConfig

/**
 * The combinable sources of named signing profiles in a `signing.yml` document.
 *
 * All three are optional and merged into a single profile set keyed by [ProfileConfig.name]
 * when the document is loaded; a name appearing in more than one source is a hard error. A
 * provider who wants a single self-contained file uses only [inline].
 *
 * @property inline Profiles defined directly in `signing.yml`.
 * @property files Paths to files, each holding one bare [ProfileConfig]. Relative paths
 *   resolve against the directory containing `signing.yml`.
 * @property directories Directories scanned recursively for `*.yml` / `*.yaml` files, each
 *   holding one bare [ProfileConfig]. Hidden (dot-prefixed) files and directories are
 *   skipped. Relative paths resolve against the directory containing `signing.yml`.
 */
data class ProfileSources(
	val inline: List<ProfileConfig> = emptyList(),
	val files: List<String> = emptyList(),
	val directories: List<String> = emptyList(),
)
