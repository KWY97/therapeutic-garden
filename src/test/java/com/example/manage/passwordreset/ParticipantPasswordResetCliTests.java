package com.example.manage.passwordreset;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.security.SecureRandom;
import java.sql.Connection;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ParticipantPasswordResetCliTests {
    private record Run(int exit, String output, FakeTerminal terminal) {}

    @Test
    void defaultDryRunGeneratesElevenDistinctStrongPasswordsWithoutWritingOrLoggingSecrets() {
        Fixture fixture = new Fixture();
        FakeTerminal terminal = new FakeTerminal().secret("database-secret");

        Run result = run(fixture, terminal, "--target", "LOCAL", "--generate");

        assertThat(result.exit()).isZero();
        assertThat(result.output()).contains("mode=DRY_RUN", "preflight=OK", "exactParticipants=11",
                "status=DRY_RUN_OK", "changedRows=0", "ddlAuto=none", "webServer=off")
                .doesNotContain("database-secret", "$2a$", "$2b$", "$2y$");
        assertThat(fixture.passwords()).containsExactlyElementsOf(fixture.originalPasswords);

        List<String> generated = result.terminal().displayedPasswords();
        assertThat(generated).hasSize(11).doesNotHaveDuplicates();
        assertThat(generated).allMatch(password -> password.length() >= 16);
        generated.forEach(password -> assertThat(result.output()).doesNotContain(password));
    }

    @Test
    void writeCommitsExactlyP001ThroughP011AndLeavesOtherTablesAndAccountsUntouched() {
        Fixture fixture = new Fixture();
        FakeTerminal terminal = new FakeTerminal().secret("database-secret")
                .line("APPLY LOCAL P001-P011");

        Run result = run(fixture, terminal, "--target", "LOCAL", "--generate", "--write");

        assertThat(result.exit()).isZero();
        assertThat(result.output()).contains("status=WRITE_COMMITTED", "changedRows=11", "target=LOCAL");
        List<String> generated = result.terminal().displayedPasswords();
        var encoder = new BCryptPasswordEncoder();
        for (int participantNo = 1; participantNo <= 11; participantNo++) {
            assertThat(encoder.matches(generated.get(participantNo - 1), fixture.password(participantNo))).isTrue();
        }
        assertThat(fixture.password(12)).isEqualTo("old-password-12");
        assertThat(fixture.jdbc.queryForObject("select password from admin where admin_id=1", String.class))
                .isEqualTo("admin-password-unchanged");
        assertThat(fixture.jdbc.queryForObject("select metric_value from measurement where id=1", Integer.class)).isEqualTo(77);
    }

    @Test
    void reuseUsesHiddenDoubleEntryAndProducesTheSamePasswords() {
        Fixture fixture = new Fixture();
        FakeTerminal terminal = new FakeTerminal().secret("database-secret");
        List<String> reused = reusablePasswords();
        reused.forEach(password -> terminal.secret(password).secret(password));
        terminal.line("APPLY LOCAL P001-P011");

        Run result = run(fixture, terminal, "--target", "LOCAL", "--reuse", "--write");

        assertThat(result.exit()).isZero();
        var encoder = new BCryptPasswordEncoder();
        for (int participantNo = 1; participantNo <= 11; participantNo++) {
            assertThat(encoder.matches(reused.get(participantNo - 1), fixture.password(participantNo))).isTrue();
        }
        assertThat(terminal.secretOutput).isEmpty();
        reused.forEach(password -> assertThat(result.output()).doesNotContain(password));
    }

    @Test
    void failureOnOneUpdateRollsBackAllElevenChanges() {
        Fixture fixture = new Fixture();
        fixture.jdbc.execute("alter table member add constraint reject_p006_change "
                + "check (participant_no <> 6 or password = 'old-password-6')");
        FakeTerminal terminal = new FakeTerminal().secret("database-secret")
                .line("APPLY LOCAL P001-P011");

        Run result = run(fixture, terminal, "--target", "LOCAL", "--generate", "--write");

        assertThat(result.exit()).isEqualTo(1);
        assertThat(result.output()).contains("DATABASE_FAILED", "rollback attempted")
                .doesNotContain("database-secret", "jdbc:");
        assertThat(fixture.passwords()).containsExactlyElementsOf(fixture.originalPasswords);
        assertThat(fixture.jdbc.queryForObject("select password from admin where admin_id=1", String.class))
                .isEqualTo("admin-password-unchanged");
        assertThat(fixture.jdbc.queryForObject("select metric_value from measurement where id=1", Integer.class)).isEqualTo(77);
    }

    @Test
    void missingParticipantOrWrongConfirmationFailsWithoutWriting() {
        Fixture missing = new Fixture();
        missing.jdbc.update("delete from member where participant_no=7");
        Run missingResult = run(missing, new FakeTerminal().secret("database-secret"),
                "--target", "LOCAL", "--generate", "--write");
        assertThat(missingResult.exit()).isEqualTo(1);
        assertThat(missingResult.output()).contains("participant_no=1~11");

        Fixture unconfirmed = new Fixture();
        Run confirmationResult = run(unconfirmed, new FakeTerminal().secret("database-secret").line("no"),
                "--target", "LOCAL", "--generate", "--write");
        assertThat(confirmationResult.exit()).isEqualTo(1);
        assertThat(confirmationResult.output()).contains("확인 문구가 일치하지 않아")
                .doesNotContain("database-secret");
        assertThat(unconfirmed.passwords()).containsExactlyElementsOf(unconfirmed.originalPasswords);
    }

    @Test
    void argumentsAndTargetGuardsAreFailClosed() {
        for (String[] args : List.of(
                new String[]{},
                new String[]{"--generate"},
                new String[]{"--target", "STAGING", "--generate"},
                new String[]{"--target", "LOCAL"},
                new String[]{"--target", "LOCAL", "--generate", "--reuse"},
                new String[]{"--target", "PRODUCTION", "--generate"},
                new String[]{"--target", "LOCAL", "--generate", "--allow-production-tunnel"},
                new String[]{"--target", "PRODUCTION", "--reuse", "--allow-production-tunnel",
                        "--allow-production-tunnel"},
                new String[]{"--target", "LOCAL", "--generate", "--dry-run", "--write"},
                new String[]{"--target", "LOCAL", "--generate", "--password", "secret"})) {
            assertThatThrownBy(() -> ResetArguments.parse(args)).isInstanceOf(IllegalArgumentException.class);
        }
        assertThat(ResetArguments.parse(new String[]{"--target", "LOCAL", "--generate"}).write()).isFalse();

        assertThatThrownBy(() -> DatabaseTarget.LOCAL.config(environment(
                "jdbc:mysql://remote.example:3306/manage", "manage"), false))
                .hasMessageContaining("loopback");
        assertThatThrownBy(() -> DatabaseTarget.PRODUCTION.config(environment(
                "jdbc:mysql://127.0.0.1:53326/railway", "railway"), false))
                .hasMessageContaining("--allow-production-tunnel");
        assertThat(DatabaseTarget.PRODUCTION.config(environment(
                "jdbc:mysql://127.0.0.1:53326/railway", "railway"), true).target())
                .isEqualTo(DatabaseTarget.PRODUCTION);
        assertThat(DatabaseTarget.PRODUCTION.config(environment(
                "jdbc:mysql://remote.example:3306/railway", "railway"), false).target())
                .isEqualTo(DatabaseTarget.PRODUCTION);
        assertThatThrownBy(() -> DatabaseTarget.PRODUCTION.config(environment(
                "jdbc:mysql://remote.example:3306/railway", "railway"), true))
                .hasMessageContaining("loopback");
        assertThatThrownBy(() -> DatabaseTarget.PRODUCTION.config(environment(
                "jdbc:mysql://user:secret@remote.example:3306/manage", "manage"), false))
                .hasMessageContaining("비밀번호");
        assertThatThrownBy(() -> DatabaseTarget.PRODUCTION.config(environment(
                "jdbc:mysql://remote.example:3306/manage?password=secret", "manage"), false))
                .hasMessageContaining("비밀번호");
    }

    @Test
    void productionTunnelRequiresExplicitApprovalAndStillRunsDatabasePreflightOnly() {
        Fixture fixture = new Fixture();
        FakeTerminal terminal = new FakeTerminal().secret("database-secret");
        reusablePasswords().forEach(password -> terminal.secret(password).secret(password));

        Run result = run(fixture, terminal, "--target", "PRODUCTION", "--reuse", "--dry-run",
                "--allow-production-tunnel");

        assertThat(result.exit()).isZero();
        assertThat(result.output()).contains("target=PRODUCTION", "productionTunnel=EXPLICITLY_ALLOWED",
                        "preflight=OK", "exactParticipants=11", "status=DRY_RUN_OK", "changedRows=0")
                .doesNotContain("database-secret", "jdbc:", fixture.environment.get(
                        "PASSWORD_RESET_PRODUCTION_DB_USER"));
        assertThat(fixture.passwords()).containsExactlyElementsOf(fixture.originalPasswords);
    }

    @Test
    void productionTunnelStillRejectsUnexpectedActualDatabaseBeforePasswordChanges() {
        Fixture fixture = new Fixture();
        fixture.environment.put("PASSWORD_RESET_PRODUCTION_EXPECTED_DATABASE", "wrong_database");

        Run result = run(fixture, new FakeTerminal().secret("database-secret"),
                "--target", "PRODUCTION", "--reuse", "--allow-production-tunnel");

        assertThat(result.exit()).isEqualTo(1);
        assertThat(result.output()).contains("EXPECTED_DATABASE")
                .doesNotContain("database-secret", "jdbc:", "wrong_database");
        assertThat(fixture.passwords()).containsExactlyElementsOf(fixture.originalPasswords);
    }

    @Test
    void reuseRejectsShortDuplicateAndMismatchedPasswords() {
        assertThatThrownBy(() -> ParticipantPasswords.readForReuse(
                new FakeTerminal().secret("too-short").secret("too-short")))
                .hasMessageContaining("최소 16자");
        assertThatThrownBy(() -> ParticipantPasswords.readForReuse(
                new FakeTerminal().secret("long-enough-password-1").secret("different-password-1")))
                .hasMessageContaining("일치하지");

        FakeTerminal duplicate = new FakeTerminal();
        duplicate.secret("long-enough-password").secret("long-enough-password")
                .secret("long-enough-password").secret("long-enough-password");
        assertThatThrownBy(() -> ParticipantPasswords.readForReuse(duplicate))
                .hasMessageContaining("모두 달라야");
    }

    @Test
    void completeBracketedPasteEnvelopeIsRemovedWithoutWeakeningControlCharacterChecks() {
        String password = "Saved-Password-001-!";
        char[] wrapped = ("\u001B[200~" + password + "\u001B[201~").toCharArray();

        char[] normalized = SecretInput.normalizeBracketedPaste(wrapped);

        assertThat(normalized).containsExactly(password.toCharArray());
        assertThat(wrapped).containsOnly('\0');

        FakeTerminal terminal = new FakeTerminal();
        terminal.secret(new String(normalized)).secret(new String(normalized));
        for (int participantNo = 2; participantNo <= 11; participantNo++) {
            String other = "Saved-Password-%03d-!".formatted(participantNo);
            terminal.secret(other).secret(other);
        }
        try (ParticipantPasswords accepted = ParticipantPasswords.readForReuse(terminal)) {
            assertThat(accepted.valueFor(1)).containsExactly(password.toCharArray());
        }
        Arrays.fill(normalized, '\0');
    }

    @Test
    void incompleteOrEmbeddedPasteControlsAndLineBreaksRemainRejected() {
        for (String malformed : List.of(
                "\u001B[200~Saved-Password-001-!",
                "Saved-Password-001-!\u001B[201~")) {
            char[] input = malformed.toCharArray();
            assertThatThrownBy(() -> SecretInput.normalizeBracketedPaste(input))
                    .hasMessageContaining("완전하지 않은 bracketed paste");
            assertThat(input).containsOnly('\0');
        }

        for (String stillUnsafe : List.of(
                "Saved-Password-001-!\n",
                "Saved-Password-001-!\r",
                "Saved-Password-001-!\t",
                "\u001B[200~Saved-Password-001-!\n\u001B[201~",
                "\u001B[200~\u001B[200~Saved-Password-001-!\u001B[201~\u001B[201~")) {
            char[] normalized = SecretInput.normalizeBracketedPaste(stillUnsafe.toCharArray());
            FakeTerminal terminal = new FakeTerminal()
                    .secret(new String(normalized)).secret(new String(normalized));
            assertThatThrownBy(() -> ParticipantPasswords.readForReuse(terminal))
                    .hasMessageContaining("제어 문자");
            Arrays.fill(normalized, '\0');
        }
    }

    private Run run(Fixture fixture, FakeTerminal terminal, String... args) {
        var bytes = new ByteArrayOutputStream();
        var output = new PrintStream(bytes);
        int exit = ParticipantPasswordResetCli.run(args, fixture.environment, output, output,
                terminal, new SecureRandom(), (config, password) -> fixture.dataSource.getConnection());
        return new Run(exit, bytes.toString(), terminal);
    }

    private static Map<String, String> environment(String url, String database) {
        Map<String, String> environment = new HashMap<>();
        environment.put("PASSWORD_RESET_LOCAL_JDBC_URL", url);
        environment.put("PASSWORD_RESET_LOCAL_DB_USER", "test-user");
        environment.put("PASSWORD_RESET_LOCAL_EXPECTED_DATABASE", database);
        environment.put("PASSWORD_RESET_PRODUCTION_JDBC_URL", url);
        environment.put("PASSWORD_RESET_PRODUCTION_DB_USER", "test-user");
        environment.put("PASSWORD_RESET_PRODUCTION_EXPECTED_DATABASE", database);
        return environment;
    }

    private static List<String> reusablePasswords() {
        List<String> result = new ArrayList<>();
        for (int participantNo = 1; participantNo <= 11; participantNo++) {
            result.add("Saved-Password-%03d-!".formatted(participantNo));
        }
        return result;
    }

    private static final class Fixture {
        final DriverManagerDataSource dataSource = new DriverManagerDataSource();
        final JdbcTemplate jdbc;
        final Map<String, String> environment;
        final List<String> originalPasswords = new ArrayList<>();

        Fixture() {
            String database = "reset_" + UUID.randomUUID().toString().replace("-", "");
            dataSource.setDriverClassName("org.h2.Driver");
            dataSource.setUrl("jdbc:h2:mem:" + database + ";MODE=MySQL;DB_CLOSE_DELAY=-1");
            dataSource.setUsername("sa");
            jdbc = new JdbcTemplate(dataSource);
            jdbc.execute("create table member(member_id bigint primary key, participant_no int not null unique, "
                    + "login_id varchar(100) not null unique, password varchar(100) not null)");
            jdbc.execute("create table admin(admin_id bigint primary key, password varchar(100) not null)");
            jdbc.execute("create table measurement(id bigint primary key, metric_value int not null)");
            for (int participantNo = 1; participantNo <= 12; participantNo++) {
                String password = "old-password-" + participantNo;
                originalPasswords.add(password);
                jdbc.update("insert into member values(?,?,?,?)", (long) participantNo, participantNo,
                        "participant-" + participantNo, password);
            }
            jdbc.update("insert into admin values(1,'admin-password-unchanged')");
            jdbc.update("insert into measurement values(1,77)");
            String actualDatabase = jdbc.queryForObject("select database()", String.class);
            environment = environment("jdbc:mysql://127.0.0.1:3306/" + actualDatabase, actualDatabase);
        }

        String password(int participantNo) {
            return jdbc.queryForObject("select password from member where participant_no=?", String.class, participantNo);
        }

        List<String> passwords() {
            return jdbc.queryForList("select password from member order by participant_no", String.class);
        }
    }

    private static final class FakeTerminal implements SecretTerminal {
        final Deque<char[]> secrets = new ArrayDeque<>();
        final Deque<String> lines = new ArrayDeque<>();
        final List<String> secretOutput = new ArrayList<>();

        FakeTerminal secret(String value) {
            secrets.add(value.toCharArray());
            return this;
        }

        FakeTerminal line(String value) {
            lines.add(value);
            return this;
        }

        @Override public char[] readSecret(String prompt) {
            if (secrets.isEmpty()) throw new IllegalStateException("no test secret for " + prompt);
            return secrets.removeFirst();
        }

        @Override public String readLine(String prompt) {
            if (lines.isEmpty()) throw new IllegalStateException("no test line for " + prompt);
            return lines.removeFirst();
        }

        @Override public void printSecretLine(String text) {
            secretOutput.add(text);
        }

        List<String> displayedPasswords() {
            return secretOutput.stream().filter(line -> line.matches("P\\d{3}: .+"))
                    .map(line -> line.substring(line.indexOf(':') + 2)).toList();
        }
    }
}
