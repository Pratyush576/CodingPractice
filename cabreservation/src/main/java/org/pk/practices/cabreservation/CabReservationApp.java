package org.pk.practices.cabreservation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.javalin.Javalin;
import io.javalin.json.JavalinJackson;
import redis.clients.jedis.JedisPooled;
import org.pk.practices.cabreservation.admin.AdminStatsRepository;
import org.pk.practices.cabreservation.admin.AdminTripRepository;
import org.pk.practices.cabreservation.admin.PostgresAdminStatsRepository;
import org.pk.practices.cabreservation.admin.PostgresAdminTripRepository;
import org.pk.practices.cabreservation.api.AdminController;
import org.pk.practices.cabreservation.api.AuthController;
import org.pk.practices.cabreservation.api.DriverController;
import org.pk.practices.cabreservation.api.TripController;
import org.pk.practices.cabreservation.auth.AuthService;
import org.pk.practices.cabreservation.auth.SessionManager;
import org.pk.practices.cabreservation.common.Database;
import org.pk.practices.cabreservation.driver.DriverRepository;
import org.pk.practices.cabreservation.driver.DriverService;
import org.pk.practices.cabreservation.driver.PostgresDriverRepository;
import org.pk.practices.cabreservation.eventbus.InProcessEventBus;
import org.pk.practices.cabreservation.geo.GeoIndex;
import org.pk.practices.cabreservation.geo.H3CellResolver;
import org.pk.practices.cabreservation.geo.RedisGeoIndex;
import org.pk.practices.cabreservation.matching.MatchOfferTimeoutSweeper;
import org.pk.practices.cabreservation.matching.MatchingEngine;
import org.pk.practices.cabreservation.payment.PaymentRepository;
import org.pk.practices.cabreservation.payment.PaymentService;
import org.pk.practices.cabreservation.payment.PayoutRepository;
import org.pk.practices.cabreservation.payment.PayoutService;
import org.pk.practices.cabreservation.payment.PostgresPaymentRepository;
import org.pk.practices.cabreservation.payment.PostgresPayoutRepository;
import org.pk.practices.cabreservation.payment.gateway.FakePaymentGateway;
import org.pk.practices.cabreservation.payment.gateway.FakePayoutProvider;
import org.pk.practices.cabreservation.payment.gateway.PaymentGateway;
import org.pk.practices.cabreservation.payment.gateway.PayoutProvider;
import org.pk.practices.cabreservation.pricing.BaseFarePricingStrategy;
import org.pk.practices.cabreservation.pricing.PricingStrategy;
import org.pk.practices.cabreservation.rider.PostgresRiderRepository;
import org.pk.practices.cabreservation.rider.RiderRepository;
import org.pk.practices.cabreservation.trip.PostgresTripRepository;
import org.pk.practices.cabreservation.trip.TripRepository;
import org.pk.practices.cabreservation.trip.TripService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.Map;

/**
 * Wires the Cab Reservation module end to end: Postgres (Trip/Driver/Rider
 * state) + Redis (geospatial index), exposed over the REST endpoints in
 * DESIGN.md §4.1–§4.4. Reads connection details from environment variables,
 * defaulting to the values docker-compose.yml starts up with — so
 * {@code docker compose up -d cabreservation-postgres cabreservation-redis}
 * followed by {@code ./gradlew :cabreservation:run} is the entire local setup.
 */
public class CabReservationApp {

    private static final Logger log = LoggerFactory.getLogger(CabReservationApp.class);

    public static void main(String[] args) {
        String jdbcUrl = env("CABRESERVATION_JDBC_URL", "jdbc:postgresql://localhost:5433/cabreservation");
        String jdbcUser = env("CABRESERVATION_JDBC_USER", "cabreservation");
        String jdbcPassword = env("CABRESERVATION_JDBC_PASSWORD", "cabreservation");
        String redisHost = env("CABRESERVATION_REDIS_HOST", "localhost");
        int redisPort = Integer.parseInt(env("CABRESERVATION_REDIS_PORT", "6379"));
        int port = Integer.parseInt(env("CABRESERVATION_PORT", "7071"));
        String adminToken = env("CABRESERVATION_ADMIN_TOKEN", "dev-admin-token");

        ObjectMapper objectMapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

        Database database = new Database(jdbcUrl, jdbcUser, jdbcPassword);
        database.runSchema("schema.sql");
        log.info("Connected to {} and applied schema.sql", jdbcUrl);

        JedisPooled jedis = new JedisPooled(redisHost, redisPort);
        log.info("Connected to Redis at {}:{}", redisHost, redisPort);

        RiderRepository riderRepository = new PostgresRiderRepository(database);
        DriverRepository driverRepository = new PostgresDriverRepository(database);
        TripRepository tripRepository = new PostgresTripRepository(database);

        SessionManager sessionManager = new SessionManager(Duration.ofHours(8));
        AuthService authService = new AuthService(riderRepository, driverRepository, sessionManager);

        GeoIndex geoIndex = new RedisGeoIndex(jedis);
        H3CellResolver h3CellResolver = new H3CellResolver();
        DriverService driverService = new DriverService(driverRepository, geoIndex, h3CellResolver);

        PricingStrategy pricingStrategy = new BaseFarePricingStrategy();
        InProcessEventBus eventBus = new InProcessEventBus();
        TripService tripService = new TripService(tripRepository, driverService, eventBus, pricingStrategy);
        MatchingEngine matchingEngine = new MatchingEngine(tripRepository, driverRepository, driverService,
                geoIndex, h3CellResolver, eventBus);

        PaymentRepository paymentRepository = new PostgresPaymentRepository(database);
        PaymentGateway paymentGateway = new FakePaymentGateway();
        new PaymentService(tripRepository, paymentRepository, paymentGateway, eventBus); // subscribes to TRIP_COMPLETED; charges the rider (§4.7 AR)

        PayoutRepository payoutRepository = new PostgresPayoutRepository(database);
        PayoutProvider payoutProvider = new FakePayoutProvider();
        new PayoutService(tripRepository, payoutRepository, payoutProvider, eventBus); // subscribes to TRIP_COMPLETED; pays the driver (§4.7 AP)

        AdminStatsRepository adminStatsRepository = new PostgresAdminStatsRepository(database);
        AdminTripRepository adminTripRepository = new PostgresAdminTripRepository(database);

        MatchOfferTimeoutSweeper sweeper = new MatchOfferTimeoutSweeper(tripRepository, matchingEngine);
        sweeper.start(1000);

        Javalin app = Javalin.create(config -> {
            config.jsonMapper(new JavalinJackson(objectMapper, false));
            // cabreservation/src/main/resources/public/{index.html,style.css,app.js,admin.html,admin.js}
            config.staticFiles.add("/public", io.javalin.http.staticfiles.Location.CLASSPATH);
        });
        new AuthController(authService, sessionManager).register(app);
        new DriverController(driverService, matchingEngine, tripService, riderRepository, driverRepository, payoutRepository).register(app);
        new TripController(tripService, riderRepository, driverRepository, paymentRepository).register(app);
        new AdminController(adminToken, adminStatsRepository, adminTripRepository).register(app);
        if ("dev-admin-token".equals(adminToken)) {
            log.warn("CABRESERVATION_ADMIN_TOKEN not set — /v1/admin/stats is protected by the default dev token only");
        }

        app.exception(Exception.class, (e, ctx) -> {
            log.error("Unhandled exception on {} {}", ctx.method(), ctx.path(), e);
            ctx.status(500).json(Map.of("error", "INTERNAL_ERROR", "message", "Something went wrong — check server logs"));
        });

        app.start(port);
        log.info("Cab Reservation service listening on :{}", port);

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            log.info("Shutting down");
            app.stop();
            sweeper.close();
            eventBus.close();
            jedis.close();
            database.close();
        }));
    }

    private static String env(String name, String defaultValue) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? defaultValue : value;
    }
}
