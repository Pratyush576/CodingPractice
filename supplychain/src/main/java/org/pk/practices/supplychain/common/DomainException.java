package org.pk.practices.supplychain.common;

/**
 * A business rule was violated (e.g. an illegal state transition) — as
 * opposed to bad input ({@link ValidationException}) or a concurrency loss
 * ({@link ConflictException}). LLD.md §1 Conventions.
 */
public class DomainException extends RuntimeException {

    private final String type;

    public DomainException(String type, String message) {
        super(message);
        this.type = type;
    }

    public String type() {
        return type;
    }
}
