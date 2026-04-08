package cz.pizavo.omnisign.api

import cz.pizavo.omnisign.domain.model.error.OperationError

/**
 * Wrapper exception that carries a domain [OperationError] through Ktor's exception pipeline.
 *
 * Domain errors are sealed interfaces that do not extend [Throwable]. This wrapper allows
 * them to be thrown from route handlers and caught by the [StatusPages] plugin for uniform
 * HTTP error mapping.
 *
 * @property operationError The domain error to surface.
 */
class OperationException(val operationError: OperationError) :
	RuntimeException(operationError.message, operationError.cause)

