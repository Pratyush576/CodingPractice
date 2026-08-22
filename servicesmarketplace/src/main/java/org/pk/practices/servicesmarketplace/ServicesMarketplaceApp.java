package org.pk.practices.servicesmarketplace;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.javalin.Javalin;
import io.javalin.json.JavalinJackson;
import org.pk.practices.servicesmarketplace.api.AuthController;
import org.pk.practices.servicesmarketplace.api.CreditController;
import org.pk.practices.servicesmarketplace.api.LeadController;
import org.pk.practices.servicesmarketplace.api.ProProfileController;
import org.pk.practices.servicesmarketplace.api.RequestController;
import org.pk.practices.servicesmarketplace.auth.AuthService;
import org.pk.practices.servicesmarketplace.auth.SessionManager;
import org.pk.practices.servicesmarketplace.common.Database;
import org.pk.practices.servicesmarketplace.credit.CreditLedgerRepository;
import org.pk.practices.servicesmarketplace.credit.CreditLedgerService;
import org.pk.practices.servicesmarketplace.credit.PostgresCreditLedgerRepository;
import org.pk.practices.servicesmarketplace.customer.CustomerRepository;
import org.pk.practices.servicesmarketplace.customer.PostgresCustomerRepository;
import org.pk.practices.servicesmarketplace.eventbus.InProcessEventBus;
import org.pk.practices.servicesmarketplace.lead.LeadRepository;
import org.pk.practices.servicesmarketplace.lead.LeadService;
import org.pk.practices.servicesmarketplace.lead.PostgresLeadRepository;
import org.pk.practices.servicesmarketplace.matching.MatchingEngine;
import org.pk.practices.servicesmarketplace.pro.PostgresProRepository;
import org.pk.practices.servicesmarketplace.pro.ProRepository;
import org.pk.practices.servicesmarketplace.quote.MessageRepository;
import org.pk.practices.servicesmarketplace.quote.PostgresMessageRepository;
import org.pk.practices.servicesmarketplace.quote.PostgresQuoteRepository;
import org.pk.practices.servicesmarketplace.quote.QuoteMessagingService;
import org.pk.practices.servicesmarketplace.quote.QuoteRepository;
import org.pk.practices.servicesmarketplace.request.PostgresRequestRepository;
import org.pk.practices.servicesmarketplace.request.RequestRepository;
import org.pk.practices.servicesmarketplace.request.RequestService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.Map;

/**
 * Wires the Local Services Marketplace module end to end: Postgres only (no
 * Redis/H3 — see the phased implementation plan's Context section for why),
 * exposed over the REST endpoints in DESIGN.md §4. Reads connection details
 * from environment variables, defaulting to the values docker-compose.yml
 * starts up with — so {@code docker compose up -d servicesmarketplace-postgres}
 * followed by {@code ./gradlew :servicesmarketplace:run} is the entire local setup.
 */
public class ServicesMarketplaceApp {

    private static final Logger log = LoggerFactory.getLogger(ServicesMarketplaceApp.class);

    public static void main(String[] args) {
        String jdbcUrl = env("SERVICESMARKETPLACE_JDBC_URL", "jdbc:postgresql://localhost:5434/servicesmarketplace");
        String jdbcUser = env("SERVICESMARKETPLACE_JDBC_USER", "servicesmarketplace");
        String jdbcPassword = env("SERVICESMARKETPLACE_JDBC_PASSWORD", "servicesmarketplace");
        int port = Integer.parseInt(env("SERVICESMARKETPLACE_PORT", "7072"));

        ObjectMapper objectMapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

        Database database = new Database(jdbcUrl, jdbcUser, jdbcPassword);
        database.runSchema("schema.sql");
        log.info("Connected to {} and applied schema.sql", jdbcUrl);

        CustomerRepository customerRepository = new PostgresCustomerRepository(database);
        ProRepository proRepository = new PostgresProRepository(database);
        RequestRepository requestRepository = new PostgresRequestRepository(database);
        LeadRepository leadRepository = new PostgresLeadRepository(database);
        QuoteRepository quoteRepository = new PostgresQuoteRepository(database);
        MessageRepository messageRepository = new PostgresMessageRepository(database);
        CreditLedgerRepository creditLedgerRepository = new PostgresCreditLedgerRepository(database);

        SessionManager sessionManager = new SessionManager(Duration.ofHours(8));
        CreditLedgerService creditLedgerService = new CreditLedgerService(creditLedgerRepository);
        AuthService authService = new AuthService(customerRepository, proRepository, creditLedgerService, sessionManager);

        InProcessEventBus eventBus = new InProcessEventBus();
        RequestService requestService = new RequestService(requestRepository, eventBus);
        new MatchingEngine(requestRepository, proRepository, leadRepository, eventBus); // subscribes to REQUEST_POSTED
        LeadService leadService = new LeadService(leadRepository, creditLedgerService);
        QuoteMessagingService quoteMessagingService = new QuoteMessagingService(quoteRepository, messageRepository, leadRepository, eventBus); // also subscribes to REQUEST_HIRED

        Javalin app = Javalin.create(config -> {
            config.jsonMapper(new JavalinJackson(objectMapper, false));
            // servicesmarketplace/src/main/resources/public/{index.html,style.css,app.js}
            config.staticFiles.add("/public", io.javalin.http.staticfiles.Location.CLASSPATH);
        });
        new AuthController(authService, sessionManager).register(app);
        new RequestController(requestService, quoteMessagingService).register(app);
        new LeadController(leadService, quoteMessagingService).register(app);
        new CreditController(creditLedgerService).register(app);
        new ProProfileController(proRepository).register(app);

        app.exception(Exception.class, (e, ctx) -> {
            log.error("Unhandled exception on {} {}", ctx.method(), ctx.path(), e);
            ctx.status(500).json(Map.of("error", "INTERNAL_ERROR", "message", "Something went wrong — check server logs"));
        });

        app.start(port);
        log.info("Local Services Marketplace listening on :{}", port);

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            log.info("Shutting down");
            app.stop();
            eventBus.close();
            database.close();
        }));
    }

    private static String env(String name, String defaultValue) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? defaultValue : value;
    }
}
