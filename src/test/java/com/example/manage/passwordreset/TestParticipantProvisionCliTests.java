package com.example.manage.passwordreset;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class TestParticipantProvisionCliTests {
    private static final String SECRET = "Test004-Strong-Secret!";

    private static final class Terminal implements SecretTerminal {
        final Deque<char[]> secrets = new ArrayDeque<>();
        final Deque<String> lines = new ArrayDeque<>();
        Runnable beforeLine = () -> {};
        Terminal secret(String value) { secrets.add(value.toCharArray()); return this; }
        Terminal line(String value) { lines.add(value); return this; }
        @Override public char[] readSecret(String prompt) { return secrets.removeFirst(); }
        @Override public String readLine(String prompt) { beforeLine.run(); return lines.removeFirst(); }
        @Override public void printSecretLine(String value) { throw new AssertionError("unexpected output"); }
    }

    private record Result(int exit, String output) {}

    private static final class Fixture {
        final String name = "provision" + UUID.randomUUID().toString().replace("-", "");
        final DriverManagerDataSource database = new DriverManagerDataSource(
                "jdbc:h2:mem:" + name + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        final JdbcTemplate jdbc = new JdbcTemplate(database);
        final Map<String, String> environment = new HashMap<>();
        Fixture() {
            jdbc.execute("""
                    create table member(member_id bigint auto_increment primary key,
                      participant_no int not null unique, group_no int not null,
                      login_id varchar(100) not null unique, password varchar(255) not null,
                      name varchar(100), phone varchar(20), test_account boolean not null default false,
                      analysis_reference_member_id bigint,
                      foreign key (analysis_reference_member_id) references member(member_id))
                    """);
            jdbc.update("insert into member(member_id,participant_no,group_no,login_id,password,name) "
                    + "values(4,4,1,'private-p004','unchanged','P004')");
            environment.put("TEST_ACCOUNT_LOCAL_JDBC_URL", "jdbc:mysql://localhost:3306/" + name);
            environment.put("TEST_ACCOUNT_LOCAL_DB_USER", "local-user");
            environment.put("TEST_ACCOUNT_LOCAL_EXPECTED_DATABASE", name);
            environment.put("TEST_ACCOUNT_LOCAL_EXPECTED_HOST", "localhost");
            environment.put("TEST_ACCOUNT_LOCAL_EXPECTED_P004_LOGIN_ID", "private-p004");
        }
        Result run(Terminal terminal, TestParticipantProvisionCli.ConnectionFactory factory, String... args) {
            var out = new ByteArrayOutputStream();
            var err = new ByteArrayOutputStream();
            int exit = TestParticipantProvisionCli.run(args, environment,
                    new PrintStream(out), new PrintStream(err), terminal, factory);
            return new Result(exit, out + "\n" + err);
        }
        Result run(Terminal terminal, String... args) {
            return run(terminal, (config, password) -> database.getConnection(), args);
        }
        int count() { return jdbc.queryForObject("select count(*) from member where login_id='test004'", Integer.class); }
    }

    private static Terminal writeTerminal(String target) {
        return new Terminal().secret("database-secret").line("APPLY " + target + " test004")
                .secret(SECRET).secret(SECRET);
    }

    @Test void dryRunThenWriteCreatesSeparateBcryptAccountAndDuplicateRunFails() {
        Fixture f = new Fixture();
        Result dry = f.run(new Terminal().secret("database-secret"), "--target", "LOCAL", "--p004-member-id", "4");
        assertThat(dry.exit()).isZero();
        assertThat(dry.output()).contains("preflight=OK", "status=DRY_RUN_OK", "changedRows=0");
        assertThat(f.count()).isZero();

        Result write = f.run(writeTerminal("LOCAL"), "--target", "LOCAL", "--p004-member-id", "4", "--write");
        assertThat(write.exit()).isZero();
        assertThat(write.output()).contains("status=WRITE_COMMITTED").doesNotContain(
                SECRET, "database-secret", "jdbc:mysql", "private-p004");
        assertThat(f.count()).isEqualTo(1);
        assertThat(f.jdbc.queryForObject("select member_id from member where login_id='test004'", Long.class))
                .isNotEqualTo(4L);
        assertThat(f.jdbc.queryForObject("select analysis_reference_member_id from member where login_id='test004'", Long.class))
                .isEqualTo(4L);
        assertThat(new BCryptPasswordEncoder().matches(SECRET,
                f.jdbc.queryForObject("select password from member where login_id='test004'", String.class))).isTrue();
        assertThat(f.jdbc.queryForObject("select password from member where member_id=4", String.class)).isEqualTo("unchanged");

        Result duplicate = f.run(new Terminal().secret("database-secret"), "--target", "LOCAL",
                "--p004-member-id", "4", "--write");
        assertThat(duplicate.exit()).isEqualTo(1);
        assertThat(duplicate.output()).contains("VALIDATION_FAILED");
        assertThat(f.count()).isEqualTo(1);
    }

    @Test void insertFailureRollsBackAndKeepsP004Untouched() {
        Fixture f = new Fixture();
        f.jdbc.execute("alter table member add constraint reject_test004 check (login_id <> 'test004')");
        Result result = f.run(writeTerminal("LOCAL"), "--target", "LOCAL", "--p004-member-id", "4", "--write");
        assertThat(result.exit()).isEqualTo(1);
        assertThat(result.output()).contains("DATABASE_FAILED").doesNotContain(SECRET, "database-secret", "jdbc:mysql");
        assertThat(f.count()).isZero();
        assertThat(f.jdbc.queryForObject("select password from member where member_id=4", String.class)).isEqualTo("unchanged");
    }

    @Test void confirmationHappensAfterPreflightConnectionCloses() {
        Fixture f = new Fixture();
        boolean[] preflightClosed = {false};
        int[] opens = {0};
        TestParticipantProvisionCli.ConnectionFactory factory = (config, password) -> {
            Connection connection = f.database.getConnection();
            if (++opens[0] != 1) return connection;
            return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(),
                    new Class<?>[]{Connection.class}, (proxy, method, args) -> {
                        if ("close".equals(method.getName())) preflightClosed[0] = true;
                        try { return method.invoke(connection, args); }
                        catch (java.lang.reflect.InvocationTargetException ex) { throw ex.getCause(); }
                    });
        };
        Terminal terminal = writeTerminal("LOCAL");
        terminal.beforeLine = () -> {
            assertThat(preflightClosed[0]).isTrue();
            assertThat(opens[0]).isEqualTo(1);
        };
        Result result = f.run(terminal, factory, "--target", "LOCAL", "--p004-member-id", "4", "--write");
        assertThat(result.exit()).isZero();
    }

    @Test void commitThenErrorReportsUnknownAndRechecksWithoutRetryingInsert() {
        Fixture f = new Fixture();
        int[] opens = {0};
        TestParticipantProvisionCli.ConnectionFactory factory = (config, password) -> {
            Connection connection = f.database.getConnection();
            if (++opens[0] != 2) return connection;
            return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(),
                    new Class<?>[]{Connection.class}, (proxy, method, args) -> {
                        if ("commit".equals(method.getName())) {
                            connection.commit();
                            throw new SQLException("sensitive jdbc:mysql://secret", "08006");
                        }
                        try { return method.invoke(connection, args); }
                        catch (java.lang.reflect.InvocationTargetException ex) { throw ex.getCause(); }
                    });
        };
        Result result = f.run(writeTerminal("LOCAL"), factory,
                "--target", "LOCAL", "--p004-member-id", "4", "--write");
        assertThat(result.exit()).isEqualTo(1);
        assertThat(result.output()).contains("WRITE_OUTCOME_UNKNOWN", "recheck=PRESENT")
                .doesNotContain("jdbc:mysql", SECRET, "database-secret");
        assertThat(opens[0]).isEqualTo(3);
        assertThat(f.count()).isEqualTo(1);
    }

    @Test void wrongEnvironmentOrIdentityFailsClosedWithoutOpeningConnection() {
        Fixture f = new Fixture();
        TestParticipantProvisionCli.ConnectionFactory forbidden = (config, password) -> {
            throw new AssertionError("connection must not open");
        };
        Result missingProduction = f.run(new Terminal(), forbidden,
                "--target", "PRODUCTION", "--p004-member-id", "4", "--write");
        assertThat(missingProduction.exit()).isEqualTo(1);
        f.environment.put("TEST_ACCOUNT_LOCAL_EXPECTED_HOST", "other-host");
        Result hostMismatch = f.run(new Terminal(), forbidden,
                "--target", "LOCAL", "--p004-member-id", "4", "--write");
        assertThat(hostMismatch.exit()).isEqualTo(1);
        f.environment.put("TEST_ACCOUNT_LOCAL_EXPECTED_HOST", "localhost");
        f.environment.put("TEST_ACCOUNT_LOCAL_EXPECTED_DATABASE", "other_database");
        Result databaseMismatch = f.run(new Terminal().secret("database-secret"),
                "--target", "LOCAL", "--p004-member-id", "4", "--write");
        assertThat(databaseMismatch.exit()).isEqualTo(1);
        f.environment.put("TEST_ACCOUNT_LOCAL_EXPECTED_DATABASE", f.name);
        f.environment.put("TEST_ACCOUNT_LOCAL_EXPECTED_P004_LOGIN_ID", "wrong");
        Result identityMismatch = f.run(new Terminal().secret("database-secret"),
                "--target", "LOCAL", "--p004-member-id", "4", "--write");
        assertThat(identityMismatch.exit()).isEqualTo(1);
        assertThat(f.count()).isZero();
    }

    @Test void productionRequiresItsOwnTargetSettingsAndApprovalPhrase() {
        Fixture f = new Fixture();
        f.environment.put("TEST_ACCOUNT_PRODUCTION_JDBC_URL", "jdbc:mysql://production.example:3306/" + f.name);
        f.environment.put("TEST_ACCOUNT_PRODUCTION_DB_USER", "production-user");
        f.environment.put("TEST_ACCOUNT_PRODUCTION_EXPECTED_DATABASE", f.name);
        f.environment.put("TEST_ACCOUNT_PRODUCTION_EXPECTED_HOST", "production.example");
        f.environment.put("TEST_ACCOUNT_PRODUCTION_EXPECTED_P004_LOGIN_ID", "private-p004");
        Result denied = f.run(new Terminal().secret("database-secret").line("APPLY LOCAL test004"),
                "--target", "PRODUCTION", "--p004-member-id", "4", "--write");
        assertThat(denied.exit()).isEqualTo(1);
        assertThat(f.count()).isZero();
        Result approved = f.run(writeTerminal("PRODUCTION"),
                "--target", "PRODUCTION", "--p004-member-id", "4", "--write");
        assertThat(approved.exit()).isZero();
        assertThat(approved.output()).contains("target=PRODUCTION", "status=WRITE_COMMITTED")
                .doesNotContain("production.example", "production-user", SECRET);
        assertThat(f.count()).isEqualTo(1);
    }
}
