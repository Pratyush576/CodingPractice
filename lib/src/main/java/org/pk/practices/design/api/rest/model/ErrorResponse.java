package org.pk.practices.design.api.rest.model;

/**
 * Uniform error response body returned for all non-2xx responses.
 *
 * <p>Having a consistent error shape across all endpoints means API consumers can
 * write a single error-handling path instead of guessing the response format per
 * endpoint. The HTTP status code on the response line already carries the numeric
 * code; this body adds a human-readable description.
 *
 * <p>JSON representation:
 * <pre>
 * {
 *   "error": "Employee not found: 99"
 * }
 * </pre>
 *
 * @param error short human-readable description of what went wrong
 */
public record ErrorResponse(String error) {}
