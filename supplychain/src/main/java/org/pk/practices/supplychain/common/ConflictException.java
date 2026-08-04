package org.pk.practices.supplychain.common;

/**
 * A compare-and-swap / optimistic-concurrency loss — LLD.md §1 Conventions:
 * the caller is expected to retry or re-read, not treat this as a hard failure.
 */
public class ConflictException extends RuntimeException {

    public ConflictException(String message) {
        super(message);
    }
}
