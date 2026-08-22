package org.pk.practices.servicesmarketplace.common;

/**
 * A business rule was violated (e.g. an illegal Request/Lead-lifecycle
 * transition) — as opposed to bad input ({@link ValidationException}) or a
 * concurrency loss ({@link ConflictException}).
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
