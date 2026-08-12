package cz.pizavo.omnisign.data.repository

/**
 * Recognises the one failure that means the finished CMS signature did not fit the `/Contents`
 * placeholder reserved for it before the document was digested.
 *
 * A PDF signature covers the file through `/ByteRange`, whose offsets must be fixed before the CMS
 * exists, so the space is reserved up front and cannot grow afterwards without invalidating the
 * digest just signed (see [DssSigningRepository]'s `contentSizeForLevel`). When the real signature
 * exceeds that reservation, PDFBox aborts the save and writes nothing — the input document is left
 * untouched, but the private-key operation and any timestamp request have already been spent.
 *
 * Unlike [DocumentInputErrorDetector], which keys off dedicated DSS exception types, there is no
 * type to match here: PDFBox raises a plain `IOException` from its writer, so the message text is
 * the only signal available. Matching is therefore restricted to the invariant fragment of that
 * text and is case-insensitive; if a PDFBox upgrade reworded it, the failure would simply fall back
 * to the operation's generic error, which is the behaviour that existed before this detector.
 */
class SignatureSpaceErrorDetector {

	/**
	 * Whether [exception] — or anything in its cause chain — reports that the signature did not fit
	 * its reserved space.
	 */
	fun isSignatureTooLarge(exception: Throwable): Boolean =
		generateSequence(exception) { it.cause }
			.any { it.message?.contains(NOT_ENOUGH_SPACE, ignoreCase = true) == true }

	private companion object {
		/**
		 * Invariant fragment of PDFBox's writer message ("Can't write signature, not enough space;
		 * adjust it with SignatureOptions.setPreferredSignatureSize").
		 */
		const val NOT_ENOUGH_SPACE = "not enough space"
	}
}
