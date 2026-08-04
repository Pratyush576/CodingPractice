package org.pk.practices.supplychain;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.javalin.Javalin;
import io.javalin.json.JavalinJackson;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringSerializer;
import org.pk.practices.supplychain.api.AuthController;
import org.pk.practices.supplychain.api.BookingController;
import org.pk.practices.supplychain.api.CapacityOfferingController;
import org.pk.practices.supplychain.auth.AuthService;
import org.pk.practices.supplychain.auth.SessionManager;
import org.pk.practices.supplychain.booking.BookingService;
import org.pk.practices.supplychain.booking.BookingServiceImpl;
import org.pk.practices.supplychain.booking.PostgresBookingRepository;
import org.pk.practices.supplychain.common.Database;
import org.pk.practices.supplychain.common.OutboxEventPublisher;
import org.pk.practices.supplychain.matching.CapacityOfferingRepository;
import org.pk.practices.supplychain.matching.CapacityOfferingService;
import org.pk.practices.supplychain.matching.CapacityOfferingServiceImpl;
import org.pk.practices.supplychain.matching.MatchingService;
import org.pk.practices.supplychain.matching.MatchingServiceImpl;
import org.pk.practices.supplychain.matching.PostgresCapacityOfferingRepository;
import org.pk.practices.supplychain.outbox.OutboxRelay;
import org.pk.practices.supplychain.party.PartyRepository;
import org.pk.practices.supplychain.party.PostgresPartyRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.Map;
import java.util.Properties;

/**
 * Wires Booking Service end to end: Postgres (booking data + outbox) and the
 * outbox relay to Kafka, exposed over the REST endpoints in LLD.md §2. Reads
 * connection details from environment variables, defaulting to the values
 * docker-compose.yml starts up with — so `docker compose up -d` followed by
 * `./gradlew :supplychain:run` is the entire local setup.
 */
public class BookingServiceApp {

    private static final Logger log = LoggerFactory.getLogger(BookingServiceApp.class);

    public static void main(String[] args) {
        String jdbcUrl = env("SUPPLYCHAIN_JDBC_URL", "jdbc:postgresql://localhost:5432/supplychain");
        String jdbcUser = env("SUPPLYCHAIN_JDBC_USER", "supplychain");
        String jdbcPassword = env("SUPPLYCHAIN_JDBC_PASSWORD", "supplychain");
        String kafkaBootstrap = env("SUPPLYCHAIN_KAFKA_BOOTSTRAP", "localhost:9092");
        int port = Integer.parseInt(env("SUPPLYCHAIN_PORT", "7070"));

        ObjectMapper objectMapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

        Database database = new Database(jdbcUrl, jdbcUser, jdbcPassword);
        database.runSchema("schema.sql");
        log.info("Connected to {} and applied schema.sql", jdbcUrl);

        KafkaProducer<String, String> producer = new KafkaProducer<>(kafkaProducerProps(kafkaBootstrap));

        OutboxEventPublisher eventPublisher = new OutboxEventPublisher(objectMapper);
        PostgresBookingRepository repository = new PostgresBookingRepository(database, eventPublisher);

        CapacityOfferingRepository capacityOfferingRepository = new PostgresCapacityOfferingRepository(database);
        MatchingService matchingService = new MatchingServiceImpl(capacityOfferingRepository);
        CapacityOfferingService capacityOfferingService = new CapacityOfferingServiceImpl(capacityOfferingRepository);

        BookingService bookingService = new BookingServiceImpl(repository, matchingService);

        PartyRepository partyRepository = new PostgresPartyRepository(database);
        SessionManager sessionManager = new SessionManager(Duration.ofHours(8));
        AuthService authService = new AuthService(partyRepository, sessionManager);

        OutboxRelay relay = new OutboxRelay(database, producer, objectMapper);
        relay.start(500);

        Javalin app = Javalin.create(config -> {
            config.jsonMapper(new JavalinJackson(objectMapper, false));
            // supplychain/src/main/resources/public/{index.html,style.css,app.js} — the
            // Shipper create-booking form + Operator management view, served at "/".
            config.staticFiles.add("/public", io.javalin.http.staticfiles.Location.CLASSPATH);
        });
        // AuthController registers its `before` filter first — every /v1/bookings*
        // request resolves an actor from its Bearer token before BookingController runs.
        new AuthController(authService, sessionManager, partyRepository).register(app);
        new BookingController(bookingService, partyRepository).register(app);
        new CapacityOfferingController(capacityOfferingService).register(app);

        // Catch-all: anything not already mapped by a controller above (a Jackson
        // deserialization failure on a malformed request body, a bug, ...) still gets
        // logged with a real stack trace and still comes back as JSON — Javalin's own
        // default error response for an unmapped exception is plain text, not JSON,
        // which is what app.js's `JSON.parse(text)` was choking on.
        app.exception(Exception.class, (e, ctx) -> {
            log.error("Unhandled exception on {} {}", ctx.method(), ctx.path(), e);
            ctx.status(500).json(Map.of("error", "INTERNAL_ERROR", "message", "Something went wrong — check server logs"));
        });

        app.start(port);
        log.info("Booking Service listening on :{}", port);

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            log.info("Shutting down");
            app.stop();
            relay.close();
            producer.close();
            database.close();
        }));
    }

    private static Properties kafkaProducerProps(String bootstrapServers) {
        Properties props = new Properties();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        props.put(ProducerConfig.ACKS_CONFIG, "all");
        return props;
    }

    private static String env(String name, String defaultValue) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? defaultValue : value;
    }
}
