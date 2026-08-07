package org.pk.practices.cabreservation.auth;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory only — sessions don't survive a restart and aren't shared across
 * instances. Fine for a single local dev process; not a production auth model.
 */
public class SessionManager {

    private static final SecureRandom RANDOM = new SecureRandom();

    private final Map<String, Session> sessionsByToken = new ConcurrentHashMap<>();
    private final Duration ttl;

    public SessionManager(Duration ttl) {
        this.ttl = ttl;
    }

    public String createSession(AuthenticatedAccount account) {
        String token = generateToken();
        sessionsByToken.put(token, new Session(account, Instant.now().plus(ttl)));
        return token;
    }

    public Optional<AuthenticatedAccount> resolve(String token) {
        if (token == null) {
            return Optional.empty();
        }
        Session session = sessionsByToken.get(token);
        if (session == null) {
            return Optional.empty();
        }
        if (Instant.now().isAfter(session.expiresAt())) {
            sessionsByToken.remove(token);
            return Optional.empty();
        }
        return Optional.of(session.account());
    }

    public void invalidate(String token) {
        sessionsByToken.remove(token);
    }

    private static String generateToken() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private record Session(AuthenticatedAccount account, Instant expiresAt) {}
}
