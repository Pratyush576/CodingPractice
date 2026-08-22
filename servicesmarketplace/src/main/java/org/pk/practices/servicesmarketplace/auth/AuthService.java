package org.pk.practices.servicesmarketplace.auth;

import org.pk.practices.servicesmarketplace.common.AuthenticationException;
import org.pk.practices.servicesmarketplace.common.ValidationException;
import org.pk.practices.servicesmarketplace.credit.CreditLedgerService;
import org.pk.practices.servicesmarketplace.customer.Customer;
import org.pk.practices.servicesmarketplace.customer.CustomerRepository;
import org.pk.practices.servicesmarketplace.pro.Pro;
import org.pk.practices.servicesmarketplace.pro.ProProfile;
import org.pk.practices.servicesmarketplace.pro.ProRepository;
import org.pk.practices.servicesmarketplace.pro.VerificationStatus;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class AuthService {

    private final CustomerRepository customerRepository;
    private final ProRepository proRepository;
    private final CreditLedgerService creditLedgerService;
    private final SessionManager sessionManager;

    public AuthService(CustomerRepository customerRepository, ProRepository proRepository,
                        CreditLedgerService creditLedgerService, SessionManager sessionManager) {
        this.customerRepository = customerRepository;
        this.proRepository = proRepository;
        this.creditLedgerService = creditLedgerService;
        this.sessionManager = sessionManager;
    }

    public LoginResult registerCustomer(RegisterCustomerRequest request) {
        List<String> violations = new ArrayList<>();
        requireNonBlank(violations, "name", request.name());
        requireNonBlank(violations, "email", request.email());
        requirePassword(violations, request.password());
        if (!violations.isEmpty()) {
            throw new ValidationException(violations);
        }

        Customer customer = new Customer(
                UUID.randomUUID().toString(),
                request.name(),
                request.email().trim().toLowerCase(),
                PasswordHasher.hash(request.password()),
                null,
                Instant.now()
        );
        customerRepository.insert(customer); // throws ConflictException on a duplicate email
        return toLoginResult(customer);
    }

    public LoginResult registerPro(RegisterProRequest request) {
        List<String> violations = new ArrayList<>();
        requireNonBlank(violations, "businessName", request.businessName());
        requireNonBlank(violations, "email", request.email());
        requirePassword(violations, request.password());
        if (request.profile() == null) {
            violations.add("profile is required");
        } else {
            requireNonBlank(violations, "profile.categoryId", request.profile().categoryId());
            if (request.profile().lat() == null) violations.add("profile.lat is required");
            if (request.profile().lng() == null) violations.add("profile.lng is required");
            if (request.profile().radiusKm() == null) violations.add("profile.radiusKm is required");
        }
        if (!violations.isEmpty()) {
            throw new ValidationException(violations);
        }

        String proId = UUID.randomUUID().toString();
        Pro pro = new Pro(
                proId,
                request.businessName(),
                request.email().trim().toLowerCase(),
                PasswordHasher.hash(request.password()),
                VerificationStatus.UNVERIFIED,
                null,
                null,
                Instant.now()
        );
        ProProfile profile = new ProProfile(
                proId,
                request.profile().categoryId(),
                request.profile().lat(),
                request.profile().lng(),
                request.profile().radiusKm(),
                request.profile().startingPrice(),
                null,
                null,
                Instant.now()
        );
        proRepository.insert(pro, profile); // throws ConflictException on a duplicate email
        creditLedgerService.openBalance(proId);
        return toLoginResult(pro);
    }

    public LoginResult login(LoginRequest request) {
        String email = request.email() == null ? "" : request.email().trim().toLowerCase();
        String password = request.password() == null ? "" : request.password();

        var customer = customerRepository.findByEmail(email).filter(c -> PasswordHasher.verify(password, c.passwordHash()));
        if (customer.isPresent()) {
            return toLoginResult(customer.get());
        }
        var pro = proRepository.findByEmail(email).filter(p -> PasswordHasher.verify(password, p.passwordHash()));
        if (pro.isPresent()) {
            return toLoginResult(pro.get());
        }
        // Same message either way — don't tell a caller whether the email or the password was wrong.
        throw new AuthenticationException("Invalid email or password");
    }

    public void logout(String token) {
        sessionManager.invalidate(token);
    }

    private LoginResult toLoginResult(Customer customer) {
        AuthenticatedAccount account = new AuthenticatedAccount(customer.customerId(), AccountType.CUSTOMER, customer.name());
        String token = sessionManager.createSession(account);
        return new LoginResult(token, customer.customerId(), AccountType.CUSTOMER, customer.name());
    }

    private LoginResult toLoginResult(Pro pro) {
        AuthenticatedAccount account = new AuthenticatedAccount(pro.proId(), AccountType.PRO, pro.businessName());
        String token = sessionManager.createSession(account);
        return new LoginResult(token, pro.proId(), AccountType.PRO, pro.businessName());
    }

    private static void requireNonBlank(List<String> violations, String field, String value) {
        if (value == null || value.isBlank()) {
            violations.add(field + " is required");
        }
    }

    private static void requirePassword(List<String> violations, String password) {
        if (password == null || password.length() < 8) {
            violations.add("password must be at least 8 characters");
        }
    }
}
