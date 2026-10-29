package com.example.manage.personaleeg;

import java.io.PrintStream;
import java.net.URI;
import java.sql.*;
import java.util.*;

/** Guarded operator CLI for listing and switching only the personal EEG active pointer. */
public final class ProductionPersonalEegVersionCli {
    public static final String JDBC_URL = "PERSONAL_EEG_PROD_JDBC_URL";
    public static final String DB_USER = "PERSONAL_EEG_PROD_DB_USER";
    public static final String DB_PASSWORD = "PERSONAL_EEG_PROD_DB_PASSWORD";
    public static final String EXPECTED_DATABASE = "PERSONAL_EEG_PROD_EXPECTED_DATABASE";
    public static final String EXPECTED_SITE_ID = "PERSONAL_EEG_PROD_EXPECTED_SITE_ID";
    public static final String EXPECTED_SITE_NAME = "PERSONAL_EEG_PROD_EXPECTED_SITE_NAME";
    public static final String TARGET_LABEL = "PERSONAL_EEG_PROD_TARGET_LABEL";
    public static final String EXPECTED_ADMIN_ROWS = "PERSONAL_EEG_PROD_EXPECTED_ADMIN_ROWS";
    public static final String EXPECTED_MEMBER_ROWS = "PERSONAL_EEG_PROD_EXPECTED_MEMBER_ROWS";
    public static final String EXPECTED_OPERATING_MEMBERS = "PERSONAL_EEG_PROD_EXPECTED_OPERATING_MEMBERS";
    public static final String EXPECTED_TEST_ACCOUNTS = "PERSONAL_EEG_PROD_EXPECTED_TEST_ACCOUNTS";
    public static final String EXPECTED_LEGACY_SESSIONS = "PERSONAL_EEG_PROD_EXPECTED_LEGACY_SESSIONS";
    public static final String EXPECTED_LEGACY_RESULTS = "PERSONAL_EEG_PROD_EXPECTED_LEGACY_RESULTS";
    public static final String EXPECTED_SUMMARY_ROWS = "PERSONAL_EEG_PROD_EXPECTED_SUMMARY_ROWS";

    private static final Set<String> VALUED = Set.of("--activate-batch", "--confirm-environment",
            "--confirm-target", "--confirm-database", "--confirm-batch-id", "--confirm-version",
            "--confirm-source-sha", "--expect-current");
    private static final Set<String> FLAGS = Set.of("--list", "--legacy", "--write",
            "--confirm-legacy", "--allow-production-tunnel");

    record Config(String jdbcUrl, String user, String password, String expectedDatabase,
            long expectedSiteId, String expectedSiteName, String targetLabel,
            ProtectedState expectedProtectedState) {
        @Override
        public String toString() {
            return "Config[target=" + targetLabel + ",database=" + expectedDatabase
                    + ",siteId=" + expectedSiteId + ",credentials=[REDACTED]]";
        }
    }

    record ProtectedState(int admins, int members, int operatingMembers, int testAccounts,
            int legacySessions, int legacyResults, int summaryRows) {
    }

    @FunctionalInterface
    interface ConnectionFactory {
        Connection open(Config config) throws SQLException;
    }

    public static void main(String[] args) {
        int exit = run(args, System.getenv(), System.out, System.err,
                config -> DriverManager.getConnection(config.jdbcUrl(), config.user(), config.password()));
        if (exit != 0)
            System.exit(exit);
    }

    static int run(String[] args, Map<String, String> environment, PrintStream out, PrintStream err,
            ConnectionFactory connections) {
        try {
            var options = parse(args);
            boolean list = options.containsKey("--list");
            boolean legacy = options.containsKey("--legacy");
            boolean activate = options.containsKey("--activate-batch");
            if ((list ? 1 : 0) + (legacy ? 1 : 0) + (activate ? 1 : 0) != 1)
                throw new IllegalArgumentException("--list, --activate-batch, --legacy 중 정확히 하나가 필요합니다.");
            Config config = config(environment);
            validateJdbcTarget(config.jdbcUrl(), options.containsKey("--allow-production-tunnel"));
            if (list) {
                rejectWriteArguments(options);
                return list(config, connections, out);
            }
            requireWriteArguments(options, config, activate, legacy);
            long batchId = activate ? positive(options.get("--activate-batch"), "--activate-batch") : 0;
            Long expectedCurrent = expectedCurrent(options.get("--expect-current"));
            return write(config, connections, out, batchId, expectedCurrent, legacy, options);
        } catch (SQLException ex) {
            err.println("DATABASE_FAILED SQLState=" + safeSqlState(ex) + "; rollback");
            return 1;
        } catch (Exception ex) {
            err.println("VALIDATION_FAILED: " + safeMessage(ex, environment));
            return 1;
        }
    }

    private static int list(Config config, ConnectionFactory connections, PrintStream out)
            throws SQLException {
        try (var connection = connections.open(config)) {
            connection.setReadOnly(true);
            connection.setAutoCommit(false);
            if ("MySQL".equals(connection.getMetaData().getDatabaseProductName()))
                try (var statement = connection.createStatement()) {
                    statement.execute("SET TRANSACTION READ ONLY");
                }
            validateTarget(connection, config);
            var state = protectedState(connection);
            requireProtectedState(config, state);
            var integrity = new PersonalEegBatchIntegrity();
            var active = integrity.active(connection);
            var batches = integrity.list(connection);
            printHeader(out, "LIST", config, state);
            printActive(out, active);
            batches.forEach(row -> printBatch(out, row, config.expectedSiteId()));
            connection.rollback();
            if (batches.stream().anyMatch(row -> !row.valid())
                    || (!active.legacy() && batches.stream().noneMatch(row -> row.batchId() == active.batchId())))
                throw new IllegalArgumentException("유효하지 않은 배치 또는 활성 포인터가 있습니다.");
            return 0;
        }
    }

    private static int write(Config config, ConnectionFactory connections, PrintStream out,
            long batchId, Long expectedCurrent, boolean legacy, Map<String, String> options)
            throws SQLException {
        try (var connection = connections.open(config)) {
            validateTarget(connection, config);
            ProtectedState before = protectedState(connection);
            requireProtectedState(config, before);
            var integrity = new PersonalEegBatchIntegrity();
            var current = integrity.active(connection);
            printHeader(out, "WRITE", config, before);
            printActive(out, current);

            PersonalEegBatchIntegrity.BatchStatus target = null;
            if (!legacy) {
                target = integrity.requireValid(connection, batchId, config.expectedSiteId());
                printBatch(out, target, config.expectedSiteId());
                if (Long.toString(batchId).equals(options.get("--confirm-batch-id")) == false)
                    throw new IllegalArgumentException("confirmed batch ID가 target과 일치하지 않습니다.");
                if (!target.versionLabel().equals(options.get("--confirm-version")))
                    throw new IllegalArgumentException("confirmed version이 target과 일치하지 않습니다.");
                if (!target.sourceSha256().equals(options.get("--confirm-source-sha")))
                    throw new IllegalArgumentException("confirmed source SHA가 target과 일치하지 않습니다.");
            }

            if (legacy && current.legacy()) {
                out.println("status=ALREADY_LEGACY");
                return 0;
            }
            if (!legacy && Objects.equals(current.batchId(), batchId)) {
                out.println("status=ALREADY_ACTIVE,batchId=" + batchId + ",version=" + target.versionLabel());
                return 0;
            }
            if (!Objects.equals(current.batchId(), expectedCurrent))
                throw new IllegalArgumentException("현재 활성 버전이 --expect-current와 일치하지 않습니다.");

            var versions = new PersonalEegVersionStore();
            if (legacy)
                versions.returnToLegacyExpected(connection, expectedCurrent);
            else
                versions.activateExpected(connection, batchId, expectedCurrent);
            connection.setAutoCommit(true);

            var after = integrity.active(connection);
            ProtectedState protectedAfter = protectedState(connection);
            if (!before.equals(protectedAfter))
                throw new IllegalStateException("보호 대상 계정/Summary/legacy count가 변경되었습니다.");
            if (legacy ? !after.legacy() : !Objects.equals(after.batchId(), batchId))
                throw new IllegalStateException("활성 포인터 변경 결과 불일치");
            out.println(legacy ? "status=RETURNED_TO_LEGACY"
                    : "status=ACTIVATED,batchId=" + batchId + ",version=" + target.versionLabel());
            return 0;
        }
    }

    private static void validateTarget(Connection connection, Config config) throws SQLException {
        if (!PersonalEegDatabaseImporter.schemaReady(connection))
            throw new IllegalArgumentException("개인 EEG 스키마 준비 필요");
        try (var statement = connection.prepareStatement("select database()");
             var rows = statement.executeQuery()) {
            if (!rows.next() || !config.expectedDatabase().equals(rows.getString(1)) || rows.next())
                throw new IllegalArgumentException("실제 database가 expected database와 일치하지 않습니다.");
        }
        try (var statement = connection.prepareStatement("select name from site where site_id=?")) {
            statement.setLong(1, config.expectedSiteId());
            try (var rows = statement.executeQuery()) {
                if (!rows.next() || !config.expectedSiteName().equals(rows.getString(1)) || rows.next())
                    throw new IllegalArgumentException("대상 Site ID/name이 expected Site와 일치하지 않습니다.");
            }
        }
    }

    private static ProtectedState protectedState(Connection connection) throws SQLException {
        return new ProtectedState(count(connection, "select count(*) from admin"),
                count(connection, "select count(*) from member"),
                count(connection, "select count(*) from member where participant_no between 1 and 11 and test_account=false"),
                count(connection, "select count(*) from member where test_account=true"),
                count(connection, "select count(*) from healing_measurement_session"),
                count(connection, "select count(*) from healing_spot_measurement"),
                count(connection, "select count(*) from member_healing_spot_effect_summary"));
    }

    private static void requireProtectedState(Config config, ProtectedState actual) {
        if (!config.expectedProtectedState().equals(actual))
            throw new IllegalArgumentException("계정/Summary/legacy count가 expected 기준과 일치하지 않습니다.");
    }

    private static void printHeader(PrintStream out, String mode, Config config, ProtectedState state) {
        out.println("mode=" + mode + ",target=" + config.targetLabel() + ",database="
                + config.expectedDatabase() + ",siteId=" + config.expectedSiteId()
                + ",siteName=" + config.expectedSiteName());
        out.println("protectedState=admins:" + state.admins() + ",members:" + state.members()
                + ",operatingMembers:" + state.operatingMembers() + ",testAccounts:"
                + state.testAccounts() + ",legacySessions:" + state.legacySessions()
                + ",legacyResults:" + state.legacyResults() + ",summaryRows:" + state.summaryRows());
    }

    private static void printActive(PrintStream out, PersonalEegBatchIntegrity.ActiveVersion active) {
        out.println(active.legacy() ? "active=legacy"
                : "active=batchId:" + active.batchId() + ",version:" + active.versionLabel());
    }

    private static void printBatch(PrintStream out, PersonalEegBatchIntegrity.BatchStatus row,
            long expectedSiteId) {
        out.println("batchId=" + row.batchId() + ",version=" + row.versionLabel()
                + ",sourceSha256=" + row.sourceSha256() + ",siteId=" + row.siteId()
                + ",targetSite=" + (row.siteId() == expectedSiteId)
                + ",declared=" + row.declaredSessions() + "/" + row.declaredResults()
                + ",actual=" + row.actualSessions() + "/" + row.actualResults()
                + ",complete=" + row.actualComplete() + ",missing=" + row.actualMissing()
                + ",integrity=" + (row.valid() ? "VALID" : "INVALID:" + String.join("|", row.issues())));
    }

    private static Config config(Map<String, String> environment) {
        String database = required(environment, EXPECTED_DATABASE);
        if (!database.matches("[A-Za-z0-9_]+"))
            throw new IllegalArgumentException("expected database 형식 오류");
        long siteId = positive(required(environment, EXPECTED_SITE_ID), EXPECTED_SITE_ID);
        String siteName = required(environment, EXPECTED_SITE_NAME);
        String target = required(environment, TARGET_LABEL);
        if (!target.matches("[A-Za-z0-9._-]{1,100}") || siteName.chars().anyMatch(Character::isISOControl))
            throw new IllegalArgumentException("target/Site 식별자 형식 오류");
        var expected = new ProtectedState(positiveInt(environment, EXPECTED_ADMIN_ROWS),
                positiveInt(environment, EXPECTED_MEMBER_ROWS),
                positiveInt(environment, EXPECTED_OPERATING_MEMBERS),
                positiveInt(environment, EXPECTED_TEST_ACCOUNTS),
                positiveInt(environment, EXPECTED_LEGACY_SESSIONS),
                positiveInt(environment, EXPECTED_LEGACY_RESULTS),
                positiveInt(environment, EXPECTED_SUMMARY_ROWS));
        return new Config(required(environment, JDBC_URL), required(environment, DB_USER),
                required(environment, DB_PASSWORD), database, siteId, siteName, target, expected);
    }

    private static void validateJdbcTarget(String url, boolean allowTunnel) {
        if (!url.startsWith("jdbc:mysql://") || url.chars().anyMatch(Character::isWhitespace)
                || url.toLowerCase(Locale.ROOT).matches(".*(?:[?&](?:user|password)=|@).*"))
            throw new IllegalArgumentException("credential 없는 MySQL JDBC URL이 필요합니다.");
        URI uri;
        try {
            uri = URI.create(url.substring("jdbc:".length()).split("\\?", 2)[0]);
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("JDBC URL 형식 오류");
        }
        String host = uri.getHost();
        if (host == null || host.isBlank() || uri.getPath() == null || !uri.getPath().matches("/[A-Za-z0-9_]+"))
            throw new IllegalArgumentException("JDBC host/database 형식 오류");
        boolean loopback = host.equalsIgnoreCase("localhost") || host.equals("127.0.0.1")
                || host.equals("::1") || host.equals("0:0:0:0:0:0:0:1");
        if (loopback != allowTunnel)
            throw new IllegalArgumentException(loopback
                    ? "loopback Production tunnel에는 --allow-production-tunnel이 필요합니다."
                    : "remote JDBC에는 --allow-production-tunnel을 사용할 수 없습니다.");
    }

    private static void requireWriteArguments(Map<String, String> options, Config config,
            boolean activate, boolean legacy) {
        if (!options.containsKey("--write"))
            throw new IllegalArgumentException("상태 변경에는 --write가 필요합니다.");
        if (!"production".equals(options.get("--confirm-environment")))
            throw new IllegalArgumentException("--confirm-environment production이 필요합니다.");
        if (!config.targetLabel().equals(options.get("--confirm-target")))
            throw new IllegalArgumentException("--confirm-target이 expected target과 일치하지 않습니다.");
        if (!config.expectedDatabase().equals(options.get("--confirm-database")))
            throw new IllegalArgumentException("--confirm-database가 expected database와 일치하지 않습니다.");
        if (!options.containsKey("--expect-current"))
            throw new IllegalArgumentException("--expect-current legacy|<batch-id>가 필요합니다.");
        if (activate && (options.containsKey("--confirm-legacy")
                || !options.containsKey("--confirm-batch-id") || !options.containsKey("--confirm-version")
                || !options.containsKey("--confirm-source-sha")))
            throw new IllegalArgumentException("배치 활성화 확인 인자가 부족하거나 충돌합니다.");
        if (legacy && (!options.containsKey("--confirm-legacy")
                || options.containsKey("--confirm-batch-id") || options.containsKey("--confirm-version")
                || options.containsKey("--confirm-source-sha")))
            throw new IllegalArgumentException("legacy 복귀 확인 인자가 부족하거나 충돌합니다.");
    }

    private static void rejectWriteArguments(Map<String, String> options) {
        for (String key : Set.of("--write", "--legacy", "--confirm-environment", "--confirm-target",
                "--confirm-database", "--confirm-batch-id", "--confirm-version", "--confirm-legacy",
                "--confirm-source-sha", "--expect-current"))
            if (options.containsKey(key))
                throw new IllegalArgumentException("--list에는 write 인자를 사용할 수 없습니다.");
    }

    private static Map<String, String> parse(String[] args) {
        var result = new LinkedHashMap<String, String>();
        for (int index = 0; index < args.length; index++) {
            String key = args[index];
            if ((!VALUED.contains(key) && !FLAGS.contains(key)) || result.containsKey(key))
                throw new IllegalArgumentException("잘못된 argument: " + key);
            if (VALUED.contains(key)) {
                if (++index >= args.length || args[index].startsWith("--"))
                    throw new IllegalArgumentException("argument 값 누락: " + key);
                result.put(key, args[index]);
            } else
                result.put(key, "true");
        }
        return Map.copyOf(result);
    }

    private static Long expectedCurrent(String value) {
        if ("legacy".equals(value))
            return null;
        return positive(value, "--expect-current");
    }

    private static int count(Connection connection, String sql) throws SQLException {
        try (var statement = connection.prepareStatement(sql); var rows = statement.executeQuery()) {
            rows.next();
            return rows.getInt(1);
        }
    }

    private static long positive(String value, String label) {
        try {
            long parsed = Long.parseLong(value);
            if (parsed <= 0)
                throw new NumberFormatException();
            return parsed;
        } catch (RuntimeException ex) {
            throw new IllegalArgumentException("양의 값 필요: " + label);
        }
    }

    private static int positiveInt(Map<String, String> environment, String key) {
        long value = positive(required(environment, key), key);
        if (value > Integer.MAX_VALUE)
            throw new IllegalArgumentException("정수 범위 오류: " + key);
        return (int) value;
    }

    private static String required(Map<String, String> environment, String key) {
        String value = environment.get(key);
        if (value == null || value.isBlank())
            throw new IllegalArgumentException("환경변수 필요: " + key);
        return value;
    }

    private static String safeSqlState(SQLException ex) {
        return ex.getSQLState() == null ? "unknown" : ex.getSQLState().replaceAll("[^A-Za-z0-9]", "");
    }

    private static String safeMessage(Exception ex, Map<String, String> environment) {
        String message = ex.getMessage();
        if (message == null || message.isBlank())
            return ex.getClass().getSimpleName();
        for (String key : List.of(JDBC_URL, DB_USER, DB_PASSWORD)) {
            String secret = environment.get(key);
            if (secret != null && !secret.isBlank())
                message = message.replace(secret, "[REDACTED]");
        }
        return message.replaceAll("jdbc:mysql://\\S+", "[REDACTED_JDBC_URL]");
    }
}
