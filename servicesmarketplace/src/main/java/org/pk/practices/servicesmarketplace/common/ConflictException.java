package org.pk.practices.servicesmarketplace.common;

/**
 * A compare-and-swap / optimistic-concurrency loss — e.g. the hire CAS
 * (DESIGN.md §4.4) or the credit-balance CAS (§4.3). The caller is expected
 * to treat this as "the world moved," not a hard server failure.
 */
public class ConflictException extends RuntimeException {

    public ConflictException(String message) {
        super(message);
    }
}
