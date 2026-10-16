import com.limingyuan.bookkeeping.BookkeepingApplication;
import com.limingyuan.bookkeeping.identity.application.UserBootstrap;
import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.simple.JdbcClient;

/** Short-lived development server for the monthly analysis fixtures. It does not call a model. */
public class FixtureServer {
    private static final Path STOP = Path.of(System.getProperty("user.home"), ".config", "bookkeeping", "ai-acceptance.stop");
    private static final Pattern MIGRATION = Pattern.compile("^V(\\d+)__.+\\.sql$");

    public static void main(String[] args) throws Exception {
        if (args.length == 1 && "--self-check".equals(args[0])) {
            selfCheckMigrations();
            return;
        }
        String url = System.getenv("DB_URL");
        String username = System.getenv("DB_USERNAME");
        String password = System.getenv("DB_PASSWORD");
        String datasource = datasourceRefusal(url);
        if (datasource != null || blank(username) || blank(password)) {
            System.err.println(datasource == null ? "REFUSING datasource schema" : datasource);
            System.exit(2);
        }
        // The application migrates inside SpringApplication.run. This process turns
        // that off, then refuses a pending script before the application starts.
        disableAutomaticMigration();
        requireCurrentSchema(url, username, password);
        System.setProperty("spring.datasource.url", url);
        System.setProperty("spring.datasource.username", username);
        System.setProperty("spring.datasource.password", password);
        Files.createDirectories(STOP.getParent());
        Files.deleteIfExists(STOP);
        SpringApplication application = new SpringApplication(BookkeepingApplication.class);
        application.setAdditionalProfiles("local");
        ConfigurableApplicationContext context = application.run("--server.address=127.0.0.1", "--server.port=18080");
        JdbcClient jdbc = context.getBean(JdbcClient.class);
        String catalog = jdbc.sql("SELECT DATABASE()").query(String.class).single();
        if (!"bookkeeping".equals(catalog)) {
            System.err.println("REFUSING catalog=" + catalog);
            SpringApplication.exit(context);
            System.exit(3);
        }
        if (!"false".equals(System.getenv("FIXTURE_ENSURE_USERS"))) {
            UserBootstrap bootstrap = context.getBean(UserBootstrap.class);
            bootstrap.ensureUser(required("AI_ACCEPTANCE_A_USERNAME"), required("AI_ACCEPTANCE_A_PASSWORD"));
            bootstrap.ensureUser(required("AI_ACCEPTANCE_B_USERNAME"), required("AI_ACCEPTANCE_B_PASSWORD"));
        }
        System.out.println("READY catalog=" + catalog + " address=127.0.0.1 port=18080");
        try {
            while (!Files.exists(STOP)) Thread.sleep(300);
        } finally {
            SpringApplication.exit(context);
        }
    }

    static void disableAutomaticMigration() {
        System.setProperty("spring.flyway.enabled", "false");
    }

    static String migrationRefusal(Set<Integer> applied, boolean failed, Set<Integer> scripts) {
        var expected = appliedBaseline();
        if (failed || !applied.equals(expected)) return "REFUSING flyway versions " + applied;
        if (!scripts.containsAll(applied)) return "REFUSING migration scripts are not visible " + scripts;
        var pending = new TreeSet<>(scripts);
        pending.removeAll(applied);
        if (!pending.isEmpty()) return "REFUSING pending migrations " + pending;
        return null;
    }

    private static void selfCheckMigrations() {
        var applied = appliedBaseline();
        var pending = new TreeSet<>(applied);
        pending.add(12);
        disableAutomaticMigration();
        String refusal = migrationRefusal(applied, false, pending);
        if (refusal == null || !refusal.contains("pending") || !"false".equals(System.getProperty("spring.flyway.enabled"))) {
            System.err.println("SELF-CHECK did not refuse a pending migration before startup");
            System.exit(1);
        }
        if (migrationRefusal(applied, false, applied) != null || migrationRefusal(applied, true, applied) == null) {
            System.err.println("SELF-CHECK misread the current schema");
            System.exit(1);
        }
        if (datasourceRefusal("jdbc:mysql://db.example.com:3306/bookkeeping") == null
                || datasourceRefusal("jdbc:mysql://10.1.2.3:3306/bookkeeping") == null
                || datasourceRefusal("jdbc:mysql://127.0.0.1:3306/bookkeeping") != null
                || datasourceRefusal("jdbc:mysql://localhost:3306/bookkeeping") != null
                || datasourceRefusal("jdbc:mysql://localhost/bookkeeping") != null) {
            System.err.println("SELF-CHECK misread the datasource target");
            System.exit(1);
        }
        System.err.println(refusal);
        System.err.println(datasourceRefusal("jdbc:mysql://db.example.com:3306/bookkeeping"));
        System.out.println("datasource_self_check_ok");
        System.out.println("migration_self_check_ok");
    }

    static String datasourceRefusal(String url) {
        if (!"bookkeeping".equals(schemaName(url))) return "REFUSING datasource schema";
        if (!localHost(url)) return "REFUSING remote datasource";
        return null;
    }

    private static boolean localHost(String url) {
        String host = databaseHost(url);
        return "localhost".equals(host) || "127.0.0.1".equals(host) || "::1".equals(host);
    }

    private static String databaseHost(String url) {
        if (url == null || !url.startsWith("jdbc:mysql://")) return "";
        String rest = url.substring("jdbc:mysql://".length());
        int slash = rest.indexOf('/');
        String authority = slash < 0 ? rest : rest.substring(0, slash);
        int query = authority.indexOf('?');
        if (query >= 0) authority = authority.substring(0, query);
        if (authority.indexOf('@') >= 0) return "";
        if (authority.startsWith("[")) {
            int end = authority.indexOf(']');
            return end < 0 ? "" : authority.substring(1, end);
        }
        int colon = authority.indexOf(':');
        return colon < 0 ? authority : authority.substring(0, colon);
    }

    private static TreeSet<Integer> appliedBaseline() {
        var expected = new TreeSet<Integer>();
        for (int version = 1; version <= 11; version++) expected.add(version);
        return expected;
    }

    private static void requireCurrentSchema(String url, String username, String password) throws Exception {
        String datasource = datasourceRefusal(url);
        if (datasource != null) {
            System.err.println(datasource);
            System.exit(2);
        }
        try (var connection = DriverManager.getConnection(url, username, password)) {
            String catalog = connection.getCatalog();
            if (!"bookkeeping".equals(catalog)) throw new IllegalStateException("refusing catalog " + catalog);
            var versions = new TreeSet<Integer>();
            boolean failed = false;
            try (var statement = connection.createStatement();
                    var rows = statement.executeQuery(
                            "SELECT version, success FROM flyway_schema_history WHERE version IS NOT NULL ORDER BY installed_rank")) {
                while (rows.next()) {
                    versions.add(Integer.parseInt(rows.getString(1)));
                    if (rows.getInt(2) != 1) failed = true;
                }
            }
            String refusal = migrationRefusal(versions, failed, migrationVersionsOnClasspath());
            if (refusal != null) {
                System.err.println(refusal);
                System.exit(2);
            }
        }
    }

    private static TreeSet<Integer> migrationVersionsOnClasspath() throws IOException, URISyntaxException {
        var versions = new TreeSet<Integer>();
        var resources = FixtureServer.class.getClassLoader().getResources("db/migration");
        while (resources.hasMoreElements()) {
            var url = resources.nextElement();
            if (!"file".equals(url.getProtocol())) continue;
            try (var files = Files.list(Path.of(url.toURI()))) {
                files.forEach(path -> {
                    var matcher = MIGRATION.matcher(path.getFileName().toString());
                    if (matcher.matches()) versions.add(Integer.parseInt(matcher.group(1)));
                });
            }
        }
        return versions;
    }

    private static String required(String name) {
        String value = System.getenv(name);
        if (blank(value)) throw new IllegalStateException("missing " + name);
        return value;
    }

    private static boolean blank(String value) { return value == null || value.isBlank(); }

    private static String schemaName(String url) {
        if (url == null || !url.startsWith("jdbc:mysql://")) return "missing";
        int slash = url.indexOf('/', "jdbc:mysql://".length());
        if (slash < 0 || slash == url.length() - 1) return "missing";
        int query = url.indexOf('?', slash + 1);
        return query < 0 ? url.substring(slash + 1) : url.substring(slash + 1, query);
    }
}
