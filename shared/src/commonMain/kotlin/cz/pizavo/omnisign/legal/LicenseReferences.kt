package cz.pizavo.omnisign.legal

/**
 * SPDX identifier of the licence OmniSign itself is distributed under.
 *
 * Published alongside the third-party credits so a caller can tell the application's own terms
 * apart from those of the libraries it bundles.
 */
const val OMNISIGN_LICENSE_ID: String = "AGPL-3.0-or-later"

/**
 * Location of OmniSign's own source code.
 *
 * The GNU AGPL requires a user interacting with the program over a network to be offered the
 * corresponding source, which is why this travels in the `GET /api/v1/credits` response rather
 * than only in the packaged `LICENSE.md`.
 */
const val OMNISIGN_SOURCE_URL: String = "https://github.com/pizavo/omnisign"

/**
 * Location of the full, generated third-party notices.
 *
 * Every surface points here for the complete list — including the verbatim attribution notices
 * the shipped artifacts carry — rather than reproducing all of it in a dialog or a terminal.
 */
const val THIRD_PARTY_NOTICES_URL: String = "$OMNISIGN_SOURCE_URL/blob/main/THIRD-PARTY.md"
