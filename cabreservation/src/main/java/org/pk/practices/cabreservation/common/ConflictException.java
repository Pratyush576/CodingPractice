package org.pk.practices.cabreservation.common;

/**
 * A compare-and-swap / optimistic-concurrency loss — the caller is expected
 * to retry (against the next-closest driver, the next radius tier, ...) not
 * treat this as a hard failure. See DESIGN.md §4.3's double-dispatch race.
 */
public class ConflictException extends RuntimeException {

    public ConflictException(String message) {
        super(message);
    }
}
