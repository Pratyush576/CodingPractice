package org.pk.practices.supplychain.auth;

import org.pk.practices.supplychain.common.AuthenticationException;
import org.pk.practices.supplychain.common.ValidationException;
import org.pk.practices.supplychain.party.Party;
import org.pk.practices.supplychain.party.PartyRepository;
import org.pk.practices.supplychain.party.PartyRole;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

public class AuthService {

    private final PartyRepository partyRepository;
    private final SessionManager sessionManager;

    public AuthService(PartyRepository partyRepository, SessionManager sessionManager) {
        this.partyRepository = partyRepository;
        this.sessionManager = sessionManager;
    }

    public RegisterOutcome register(RegisterRequest request) {
        List<String> violations = new ArrayList<>();
        requireNonBlank(violations, "tenantId", request.tenantId());
        requireNonBlank(violations, "name", request.name());
        requireNonBlank(violations, "email", request.email());
        if (request.password() == null || request.password().length() < 8) {
            violations.add("password must be at least 8 characters");
        }

        PartyRole role = null;
        if (request.role() == null || request.role().isBlank()) {
            violations.add("role is required");
        } else {
            try {
                role = PartyRole.valueOf(request.role().trim().toUpperCase());
            } catch (IllegalArgumentException e) {
                violations.add("role must be one of " + Arrays.toString(PartyRole.values()) + ", got '" + request.role() + "'");
            }
        }
        if (!violations.isEmpty()) {
            throw new ValidationException(violations);
        }

        // tenantId is free text with no other validation — this is the one guard against the
        // easy-to-make mistake of two accounts meant to share data ending up in different
        // tenants over a typo. A genuinely new tenant just needs one explicit confirmation.
        if (!request.isConfirmedNewTenant() && !partyRepository.tenantExists(request.tenantId())) {
            return new RegisterOutcome.NewTenantConfirmationRequired(request.tenantId());
        }

        Party party = new Party(
                UUID.randomUUID().toString(),
                request.tenantId(),
                role,
                request.name(),
                request.email().trim().toLowerCase(),
                PasswordHasher.hash(request.password()),
                Instant.now()
        );
        partyRepository.insert(party); // throws ConflictException on a duplicate email
        return new RegisterOutcome.Registered(toLoginResult(party));
    }

    public LoginResult login(LoginRequest request) {
        String email = request.email() == null ? "" : request.email().trim().toLowerCase();
        String password = request.password() == null ? "" : request.password();
        Party party = partyRepository.findByEmail(email)
                .filter(p -> PasswordHasher.verify(password, p.passwordHash()))
                // Same message either way — don't tell a caller whether the email or the password was wrong.
                .orElseThrow(() -> new AuthenticationException("Invalid email or password"));
        return toLoginResult(party);
    }

    public void logout(String token) {
        sessionManager.invalidate(token);
    }

    private LoginResult toLoginResult(Party party) {
        String token = sessionManager.createSession(party);
        return new LoginResult(token, party.partyId(), party.tenantId(), party.role(), party.name());
    }

    private static void requireNonBlank(List<String> violations, String field, String value) {
        if (value == null || value.isBlank()) {
            violations.add(field + " is required");
        }
    }
}
