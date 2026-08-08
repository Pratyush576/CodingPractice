package org.pk.practices.cabreservation.auth;

import org.pk.practices.cabreservation.common.AuthenticationException;
import org.pk.practices.cabreservation.common.ValidationException;
import org.pk.practices.cabreservation.driver.CarIcon;
import org.pk.practices.cabreservation.driver.Driver;
import org.pk.practices.cabreservation.driver.DriverRepository;
import org.pk.practices.cabreservation.driver.DriverStatus;
import org.pk.practices.cabreservation.driver.Vehicle;
import org.pk.practices.cabreservation.rider.Rider;
import org.pk.practices.cabreservation.rider.RiderRepository;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class AuthService {

    private final RiderRepository riderRepository;
    private final DriverRepository driverRepository;
    private final SessionManager sessionManager;

    public AuthService(RiderRepository riderRepository, DriverRepository driverRepository, SessionManager sessionManager) {
        this.riderRepository = riderRepository;
        this.driverRepository = driverRepository;
        this.sessionManager = sessionManager;
    }

    public LoginResult registerRider(RegisterRiderRequest request) {
        List<String> violations = new ArrayList<>();
        requireNonBlank(violations, "name", request.name());
        requireNonBlank(violations, "email", request.email());
        requirePassword(violations, request.password());
        if (!violations.isEmpty()) {
            throw new ValidationException(violations);
        }

        Rider rider = new Rider(
                UUID.randomUUID().toString(),
                request.name(),
                request.email().trim().toLowerCase(),
                PasswordHasher.hash(request.password()),
                null,
                null,
                Instant.now()
        );
        riderRepository.insert(rider); // throws ConflictException on a duplicate email
        return toLoginResult(rider);
    }

    public LoginResult registerDriver(RegisterDriverRequest request) {
        List<String> violations = new ArrayList<>();
        requireNonBlank(violations, "name", request.name());
        requireNonBlank(violations, "email", request.email());
        requirePassword(violations, request.password());
        if (request.vehicle() == null) {
            violations.add("vehicle is required");
        } else {
            requireNonBlank(violations, "vehicle.plate", request.vehicle().plate());
        }
        if (!violations.isEmpty()) {
            throw new ValidationException(violations);
        }

        String driverId = UUID.randomUUID().toString();
        String vehicleId = UUID.randomUUID().toString();
        String productType = request.vehicle().productType() == null || request.vehicle().productType().isBlank()
                ? "STANDARD" : request.vehicle().productType().trim().toUpperCase();
        CarIcon carIcon = CarIcon.parse(request.vehicle().carIcon());

        Driver driver = new Driver(
                driverId,
                request.name(),
                request.email().trim().toLowerCase(),
                PasswordHasher.hash(request.password()),
                vehicleId,
                DriverStatus.OFFLINE,
                null, null, null, null,
                0,
                Instant.now(),
                Instant.now()
        );
        Vehicle vehicle = new Vehicle(vehicleId, driverId, request.vehicle().plate(), request.vehicle().make(),
                request.vehicle().model(), productType, carIcon);
        driverRepository.insert(driver, vehicle); // throws ConflictException on a duplicate email
        return toLoginResult(driver);
    }

    public LoginResult login(LoginRequest request) {
        String email = request.email() == null ? "" : request.email().trim().toLowerCase();
        String password = request.password() == null ? "" : request.password();

        var rider = riderRepository.findByEmail(email).filter(r -> PasswordHasher.verify(password, r.passwordHash()));
        if (rider.isPresent()) {
            return toLoginResult(rider.get());
        }
        var driver = driverRepository.findByEmail(email).filter(d -> PasswordHasher.verify(password, d.passwordHash()));
        if (driver.isPresent()) {
            return toLoginResult(driver.get());
        }
        // Same message either way — don't tell a caller whether the email or the password was wrong.
        throw new AuthenticationException("Invalid email or password");
    }

    public void logout(String token) {
        sessionManager.invalidate(token);
    }

    private LoginResult toLoginResult(Rider rider) {
        AuthenticatedAccount account = new AuthenticatedAccount(rider.riderId(), AccountType.RIDER, rider.name());
        String token = sessionManager.createSession(account);
        return new LoginResult(token, rider.riderId(), AccountType.RIDER, rider.name());
    }

    private LoginResult toLoginResult(Driver driver) {
        AuthenticatedAccount account = new AuthenticatedAccount(driver.driverId(), AccountType.DRIVER, driver.name());
        String token = sessionManager.createSession(account);
        return new LoginResult(token, driver.driverId(), AccountType.DRIVER, driver.name());
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
