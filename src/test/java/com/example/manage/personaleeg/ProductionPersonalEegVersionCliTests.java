package com.example.manage.personaleeg;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.io.*;
import java.nio.file.*;
import java.sql.*;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProductionPersonalEegVersionCliTests {
    private record Run(int exit, String output) {}

    private static final class Fixture {
        final DriverManagerDataSource dataSource;
        final JdbcTemplate jdbc;
        final Map<String, String> environment;
        final String database;

        Fixture() throws Exception {
            String name = "eeg_prod_cli_" + UUID.randomUUID().toString().replace("-", "");
            dataSource = new DriverManagerDataSource("jdbc:h2:mem:" + name
                    + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1", "sa", "");
            jdbc = new JdbcTemplate(dataSource);
            jdbc.execute("create table site(site_id bigint primary key,name varchar(100) not null)");
            jdbc.execute("create table admin(admin_id bigint primary key)");
            jdbc.execute("create table member(member_id bigint primary key,participant_no int not null unique,test_account boolean not null default false)");
            jdbc.execute("create table healing_course(course_id bigint primary key,site_id bigint not null,code varchar(10) not null)");
            jdbc.execute("create table healing_spot(spot_id bigint primary key,course_id bigint not null,code varchar(10) not null)");
            jdbc.execute("create table healing_measurement_session(id bigint primary key)");
            jdbc.execute("create table healing_spot_measurement(id bigint primary key)");
            jdbc.execute("create table member_healing_spot_effect_summary(id bigint primary key)");
            jdbc.update("insert into site values(1,'Production Site')");
            jdbc.update("insert into admin values(1)");
            for (int participant = 1; participant <= 11; participant++)
                jdbc.update("insert into member values(?,?,false)", participant, participant);
            jdbc.update("insert into member values(12,900004,true)");
            jdbc.update("insert into healing_course values(10,1,'HC-A')");
            jdbc.update("insert into healing_spot values(1,10,'HS1'),(2,10,'HS2')");
            jdbc.update("insert into healing_measurement_session values(1)");
            jdbc.update("insert into healing_spot_measurement values(1)");
            jdbc.update("insert into member_healing_spot_effect_summary values(1),(2)");
            String migration = Files.readString(Path.of("docs/personal-eeg-analysis-schema.sql"));
            String statements = migration.lines().filter(line -> !line.stripLeading().startsWith("--"))
                    .reduce("", (left, right) -> left + right + "\n");
            for (String sql : statements.split(";")) if (!sql.isBlank()) jdbc.execute(sql);
            seedBatch(1, "eeg-v1", "a".repeat(64), 101, false);
            seedBatch(2, "eeg-v2", "b".repeat(64), 201, true);
            try (var connection = dataSource.getConnection();
                 var statement = connection.prepareStatement("select database()");
                 var rows = statement.executeQuery()) {
                rows.next(); database = rows.getString(1);
            }
            var env = new HashMap<String, String>();
            env.put(ProductionPersonalEegVersionCli.JDBC_URL,
                    "jdbc:mysql://production-mysql.internal:3306/" + database);
            env.put(ProductionPersonalEegVersionCli.DB_USER, "operator-user");
            env.put(ProductionPersonalEegVersionCli.DB_PASSWORD, "operator-secret");
            env.put(ProductionPersonalEegVersionCli.EXPECTED_DATABASE, database);
            env.put(ProductionPersonalEegVersionCli.EXPECTED_SITE_ID, "1");
            env.put(ProductionPersonalEegVersionCli.EXPECTED_SITE_NAME, "Production Site");
            env.put(ProductionPersonalEegVersionCli.TARGET_LABEL, "railway-production-test");
            env.put(ProductionPersonalEegVersionCli.EXPECTED_ADMIN_ROWS, "1");
            env.put(ProductionPersonalEegVersionCli.EXPECTED_MEMBER_ROWS, "12");
            env.put(ProductionPersonalEegVersionCli.EXPECTED_OPERATING_MEMBERS, "11");
            env.put(ProductionPersonalEegVersionCli.EXPECTED_TEST_ACCOUNTS, "1");
            env.put(ProductionPersonalEegVersionCli.EXPECTED_LEGACY_SESSIONS, "1");
            env.put(ProductionPersonalEegVersionCli.EXPECTED_LEGACY_RESULTS, "1");
            env.put(ProductionPersonalEegVersionCli.EXPECTED_SUMMARY_ROWS, "2");
            environment = Map.copyOf(env);
        }

        private void seedBatch(long batch, String version, String sha, long session, boolean complete) {
            jdbc.update("insert into personal_eeg_analysis_batch values(?,?,?,?,?,current_timestamp,1,2,?,?)",
                    batch, sha, version + ".xlsx", version, 1, complete ? 2 : 1, complete ? 0 : 1);
            jdbc.update("insert into personal_eeg_analysis_session values(?,?,?,?,?,DATE '2026-08-14',DATE '2026-08-14')",
                    session, batch, 2, 1, 10);
            jdbc.update("insert into personal_eeg_spot_result values(?,?,?,?,?,?,?)",
                    session + 1, session, 1, 10, complete ? 9 : null, 5, complete ? 6 : null);
            jdbc.update("insert into personal_eeg_spot_result values(?,?,?,?,?,?,?)",
                    session + 2, session, 2, 10, 8, 5, 7);
        }

        ProductionPersonalEegVersionCli.ConnectionFactory connections() {
            return ignored -> dataSource.getConnection();
        }

        Long active() {
            return jdbc.query("select batch_id from personal_eeg_active_version where id=1",
                    rows -> rows.next() ? (Long) rows.getObject(1) : null);
        }
    }

    private Run run(Fixture fixture, String... args) {
        var bytes = new ByteArrayOutputStream();
        var stream = new PrintStream(bytes);
        int exit = ProductionPersonalEegVersionCli.run(args, fixture.environment,
                stream, stream, fixture.connections());
        return new Run(exit, bytes.toString());
    }

    private String[] activate(long batch, String version, String expectedCurrent) {
        return new String[]{"--activate-batch", Long.toString(batch), "--write",
                "--confirm-environment", "production",
                "--confirm-target", "railway-production-test",
                "--confirm-database", "eeg_prod_placeholder",
                "--confirm-batch-id", Long.toString(batch),
                "--confirm-version", version, "--confirm-source-sha",
                (batch == 1 ? "a" : "b").repeat(64), "--expect-current", expectedCurrent};
    }

    private String[] withDatabase(String[] args, String database) {
        return withValue(args, "--confirm-database", database);
    }

    private String[] withValue(String[] args, String key, String value) {
        String[] copy = args.clone();
        for (int index = 0; index < copy.length - 1; index++)
            if (copy[index].equals(key)) copy[index + 1] = value;
        return copy;
    }

    private String[] legacy(String database, String expectedCurrent) {
        return new String[]{"--legacy", "--write", "--confirm-environment", "production",
                "--confirm-target", "railway-production-test", "--confirm-database", database,
                "--confirm-legacy", "--expect-current", expectedCurrent};
    }

    @Test void listIsReadOnlyAndReportsActiveAndEveryBatchIntegrityWithoutSecrets() throws Exception {
        var fixture = new Fixture();
        var result = run(fixture, "--list");

        assertThat(result.exit()).isZero();
        assertThat(result.output()).contains("mode=LIST", "active=legacy", "batchId=1",
                        "batchId=2", "integrity=VALID", "protectedState=admins:1,members:12",
                        "operatingMembers:11,testAccounts:1")
                .doesNotContain(fixture.environment.get(ProductionPersonalEegVersionCli.DB_PASSWORD),
                        fixture.environment.get(ProductionPersonalEegVersionCli.DB_USER),
                        fixture.environment.get(ProductionPersonalEegVersionCli.JDBC_URL));
        assertThat(fixture.active()).isNull();
    }

    @Test void activateSwitchLegacyAndRepeatedCommandsAreSafe() throws Exception {
        var fixture = new Fixture();
        String[] activateV2 = withDatabase(activate(2, "eeg-v2", "legacy"), fixture.database);
        var first = run(fixture, activateV2);
        assertThat(first.exit()).isZero();
        assertThat(first.output()).contains("status=ACTIVATED", "batchId=2", "version=eeg-v2");
        assertThat(fixture.active()).isEqualTo(2L);

        assertThat(run(fixture, activateV2).output()).contains("status=ALREADY_ACTIVE");
        assertThat(fixture.active()).isEqualTo(2L);

        var v1 = run(fixture, withDatabase(activate(1, "eeg-v1", "2"), fixture.database));
        assertThat(v1.exit()).isZero();
        assertThat(fixture.active()).isEqualTo(1L);

        var legacy = run(fixture, legacy(fixture.database, "1"));
        assertThat(legacy.exit()).isZero();
        assertThat(legacy.output()).contains("status=RETURNED_TO_LEGACY");
        assertThat(fixture.active()).isNull();
        assertThat(run(fixture, legacy(fixture.database, "1")).output()).contains("status=ALREADY_LEGACY");

        assertThat(fixture.jdbc.queryForObject("select count(*) from admin", Integer.class)).isEqualTo(1);
        assertThat(fixture.jdbc.queryForObject("select count(*) from member", Integer.class)).isEqualTo(12);
        assertThat(fixture.jdbc.queryForObject("select count(*) from healing_measurement_session", Integer.class)).isEqualTo(1);
        assertThat(fixture.jdbc.queryForObject("select count(*) from healing_spot_measurement", Integer.class)).isEqualTo(1);
        assertThat(fixture.jdbc.queryForObject("select count(*) from member_healing_spot_effect_summary", Integer.class)).isEqualTo(2);
    }

    @Test void failClosedArgumentsTargetAndExpectedCurrentNeverChangePointer() throws Exception {
        var fixture = new Fixture();
        for (String[] args : List.of(new String[]{}, new String[]{"--list", "--write"},
                new String[]{"--legacy"},
                withDatabase(activate(2, "wrong-version", "legacy"), fixture.database),
                withValue(withDatabase(activate(2, "eeg-v2", "legacy"), fixture.database),
                        "--confirm-source-sha", "c".repeat(64)),
                withDatabase(activate(2, "eeg-v2", "1"), fixture.database))) {
            assertThat(run(fixture, args).exit()).isEqualTo(1);
            assertThat(fixture.active()).isNull();
        }

        var wrongDatabase = new HashMap<>(fixture.environment);
        wrongDatabase.put(ProductionPersonalEegVersionCli.EXPECTED_DATABASE, "wrong_database");
        var bytes = new ByteArrayOutputStream();
        var stream = new PrintStream(bytes);
        assertThat(ProductionPersonalEegVersionCli.run(new String[]{"--list"}, wrongDatabase,
                stream, stream, fixture.connections())).isEqualTo(1);

        assertThatThrownBy(() -> {
            try (var connection = fixture.dataSource.getConnection()) {
                new PersonalEegVersionStore().activateExpected(connection, 2, 1L);
            }
        }).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("expected current");
        assertThat(fixture.active()).isNull();
    }

    @Test void invalidBatchIsListedButCannotActivateAndConnectionErrorsAreRedacted() throws Exception {
        var fixture = new Fixture();
        fixture.jdbc.update("delete from personal_eeg_spot_result where session_id=201 and spot_id=2");
        var listed = run(fixture, "--list");
        assertThat(listed.exit()).isEqualTo(1);
        assertThat(listed.output()).contains("integrity=INVALID", "result-count", "spots-per-session");
        var activated = run(fixture, withDatabase(activate(2, "eeg-v2", "legacy"), fixture.database));
        assertThat(activated.exit()).isEqualTo(1);
        assertThat(fixture.active()).isNull();

        String secret = fixture.environment.get(ProductionPersonalEegVersionCli.DB_PASSWORD);
        String url = fixture.environment.get(ProductionPersonalEegVersionCli.JDBC_URL);
        var bytes = new ByteArrayOutputStream();
        var stream = new PrintStream(bytes);
        int exit = ProductionPersonalEegVersionCli.run(new String[]{"--list"}, fixture.environment,
                stream, stream, config -> { throw new SQLException(config.jdbcUrl() + " "
                        + config.user() + " " + config.password(), "08001"); });
        assertThat(exit).isEqualTo(1);
        assertThat(bytes.toString()).contains("DATABASE_FAILED", "08001")
                .doesNotContain(secret, url, fixture.environment.get(ProductionPersonalEegVersionCli.DB_USER));
    }

    @Test void loopbackRequiresExplicitProductionTunnelFlagAndRemoteRejectsIt() throws Exception {
        var fixture = new Fixture();
        var local = new HashMap<>(fixture.environment);
        local.put(ProductionPersonalEegVersionCli.JDBC_URL, "jdbc:mysql://127.0.0.1:53326/" + fixture.database);
        var bytes = new ByteArrayOutputStream();
        var stream = new PrintStream(bytes);
        assertThat(ProductionPersonalEegVersionCli.run(new String[]{"--list"}, local,
                stream, stream, fixture.connections())).isEqualTo(1);
        assertThat(ProductionPersonalEegVersionCli.run(
                new String[]{"--list", "--allow-production-tunnel"}, local,
                stream, stream, fixture.connections())).isZero();
        assertThat(run(fixture, "--list", "--allow-production-tunnel").exit()).isEqualTo(1);
    }
}
