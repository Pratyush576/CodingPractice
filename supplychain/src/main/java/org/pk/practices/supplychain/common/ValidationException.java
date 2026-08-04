package org.pk.practices.supplychain.common;

import java.util.List;

/**
 * Collects every violation found during validation rather than the first one
 * — LLD.md §1 Conventions: a caller shouldn't have to round-trip once per
 * field to discover every problem with a request.
 */
public class ValidationException extends RuntimeException {

    private final List<String> violations;

    public ValidationException(List<String> violations) {
        super("Validation failed: " + String.join("; ", violations));
        this.violations = List.copyOf(violations);
    }

    public List<String> violations() {
        return violations;
    }
}
