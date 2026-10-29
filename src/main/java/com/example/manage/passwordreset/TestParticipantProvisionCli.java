package com.example.manage.passwordreset;

import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.io.PrintStream;
import java.net.URI;
import java.nio.CharBuffer;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.Locale;
import java.util.Map;

/** Explicit JDBC utility; it never starts Spring or changes schema. */
public final class TestParticipantProvisionCli {
    @FunctionalInterface
    interface ConnectionFactory {
        Connection open(DatabaseConfig config, char[] databasePassword) throws SQLException;
    }

    private record Options(DatabaseTarget target, long p004MemberId, boolean write,
                           boolean allowProductionTunnel) {
        static Options parse(String[] args) {
            DatabaseTarget target = null;
            Long p004MemberId = null;
            boolean write = false;
            boolean dryRun = false;
            boolean tunnel = false;
            for (int i = 0; i < args.length; i++) {
                switch (args[i]) {
                    case "--target" -> {
                        if (target != null || ++i >= args.length)
                            throw invalid("--target LOCAL|PRODUCTION이 필요합니다.");
                        try {
                            target = DatabaseTarget.valueOf(args[i].toUpperCase(Locale.ROOT));
                        } catch (IllegalArgumentException exception) {
                            throw invalid("--target은 LOCAL 또는 PRODUCTION이어야 합니다.");
                        }
                    }
                    case "--p004-member-id" -> {
                        if (p004MemberId != null || ++i >= args.length)
                            throw invalid("--p004-member-id가 필요합니다.");
                        try {
                            p004MemberId = Long.parseLong(args[i]);
                        } catch (NumberFormatException exception) {
                            throw invalid("P004 member_id 형식이 올바르지 않습니다.");
                        }
                    }
                    case "--write" -> {
                        if (write)
                            throw invalid("--write가 중복되었습니다.");
                        write = true;
                    }
                    case "--dry-run" -> {
                        if (dryRun)
                            throw invalid("--dry-run이 중복되었습니다.");
                        dryRun = true;
                    }
                    case "--allow-production-tunnel" -> {
                        if (tunnel)
                            throw invalid("터널 옵션이 중복되었습니다.");
                        tunnel = true;
                    }
                    default -> throw invalid("허용되지 않은 인수가 있습니다. 비밀값은 명령행에 넣지 마세요.");
                }
            }
            if (target == null || p004MemberId == null || p004MemberId <= 0)
                throw invalid("--target LOCAL|PRODUCTION 및 유효한 --p004-member-id가 필요합니다.");
            if (write && dryRun)
                throw invalid("--dry-run과 --write를 함께 사용할 수 없습니다.");
            if (tunnel && target != DatabaseTarget.PRODUCTION)
                throw invalid("--allow-production-tunnel은 PRODUCTION에서만 허용합니다.");
            return new Options(target, p004MemberId, write, tunnel);
        }
    }

    private record Preflight(int participantNo) {
    }

    private TestParticipantProvisionCli() {
    }

    public static void main(String[] args) {
        int exit;
        try (SecretTerminal terminal = SecretTerminal.system()) {
            exit = run(args, System.getenv(), System.out, System.err, terminal,
                    (config, password) -> DriverManager.getConnection(config.jdbcUrl(), config.user(),
                            new String(password)));
        } catch (RuntimeException exception) {
            System.err.println("TERMINAL_REQUIRED: 대화형 로컬 터미널이 필요합니다.");
            exit = 1;
        }
        if (exit != 0)
            System.exit(exit);
    }

    static int run(String[] args, Map<String, String> environment, PrintStream out, PrintStream err,
                   SecretTerminal terminal, ConnectionFactory connections) {
        char[] databasePassword = null;
        char[] password = null;
        char[] confirmation = null;
        try {
            Options options = Options.parse(args);
            String prefix = "TEST_ACCOUNT_" + options.target();
            DatabaseConfig config = options.target().config(environment, options.allowProductionTunnel(), prefix);
            String expectedHost = required(environment, prefix + "_EXPECTED_HOST");
            String expectedLogin = required(environment, prefix + "_EXPECTED_P004_LOGIN_ID");
            String urlHost = URI.create(config.jdbcUrl().substring("jdbc:".length())).getHost();
            if (urlHost == null || !expectedHost.equalsIgnoreCase(urlHost))
                throw invalid("접속 주소가 선택한 환경의 EXPECTED_HOST와 일치하지 않습니다.");

            out.println("mode=" + (options.write() ? "WRITE" : "DRY_RUN") + ", target=" + options.target()
                    + ", productionTunnel=" + options.allowProductionTunnel() + ", ddlAuto=none, webServer=off");
            databasePassword = terminal.readSecret(options.target() + " DB password: ");
            if (databasePassword.length == 0)
                throw invalid("DB password가 비어 있습니다.");

            Preflight preflight;
            try (Connection connection = connections.open(config, databasePassword)) {
                connection.setReadOnly(true);
                preflight = inspect(connection, config, options.p004MemberId(), expectedLogin, false);
            }
            out.println("preflight=OK, target=" + options.target() + ", databaseMatched=true, hostMatched=true"
                    + ", p004Matched=true, test004Absent=true, proposedParticipantNo=" + preflight.participantNo()
                    + ", changedRows=0");
            if (!options.write()) {
                out.println("status=DRY_RUN_OK, changedRows=0");
                return 0;
            }

            // Human input happens after closing the read-only connection, before any row lock.
            String phrase = "APPLY " + options.target() + " test004";
            if (!phrase.equals(terminal.readLine("생성하려면 '" + phrase + "' 입력: ")))
                throw invalid("확인 문구가 일치하지 않아 변경하지 않았습니다.");
            password = terminal.readSecret("test004 password: ");
            confirmation = terminal.readSecret("test004 password again: ");
            if (!Arrays.equals(password, confirmation) || password.length < 8
                    || StandardCharsets.UTF_8.encode(CharBuffer.wrap(password)).remaining() > 72)
                throw invalid("비밀번호 확인 또는 길이가 유효하지 않습니다.");
            String hash = new BCryptPasswordEncoder().encode(CharBuffer.wrap(password));

            boolean commitAttempted = false;
            try (Connection connection = connections.open(config, databasePassword)) {
                connection.setAutoCommit(false);
                try {
                    Preflight locked = inspect(connection, config, options.p004MemberId(), expectedLogin, true);
                    try (var statement = connection.prepareStatement("""
                            insert into member (participant_no, group_no, login_id, password, name, phone,
                                                test_account, analysis_reference_member_id)
                            values (?, 0, 'test004', ?, '테스트 참가자', null, true, ?)
                            """)) {
                        statement.setInt(1, locked.participantNo());
                        statement.setString(2, hash);
                        statement.setLong(3, options.p004MemberId());
                        if (statement.executeUpdate() != 1)
                            throw new SQLException("insert count mismatch");
                    }
                    commitAttempted = true;
                    connection.commit();
                    out.println("status=WRITE_COMMITTED, target=" + options.target()
                            + ", participantNo=" + locked.participantNo());
                    return 0;
                } catch (SQLException | RuntimeException exception) {
                    if (!commitAttempted) {
                        try {
                            connection.rollback();
                        } catch (SQLException ignored) { /* inspect before retry */ }
                    }
                    throw exception;
                }
            } catch (SQLException exception) {
                if (commitAttempted) {
                    err.println("WRITE_OUTCOME_UNKNOWN: commit 결과가 불확실합니다. 재실행 전에 test004 행을 수동 확인하세요.");
                    recheck(connections, config, databasePassword, options.p004MemberId(), err);
                    return 1;
                }
                throw exception;
            }
        } catch (IllegalArgumentException exception) {
            err.println("VALIDATION_FAILED: 입력 또는 접속 대상 검증에 실패했습니다.");
            return 1;
        } catch (SQLException exception) {
            err.println("DATABASE_FAILED SQLState=" + safeSqlState(exception)
                    + "; 변경 여부를 확인한 뒤 재시도하세요.");
            return 1;
        } catch (RuntimeException exception) {
            err.println("PROVISION_FAILED: 비밀값과 연결 정보는 출력하지 않습니다. DB 상태를 확인하세요.");
            return 1;
        } finally {
            if (databasePassword != null)
                Arrays.fill(databasePassword, '\0');
            if (password != null)
                Arrays.fill(password, '\0');
            if (confirmation != null)
                Arrays.fill(confirmation, '\0');
        }
    }

    private static Preflight inspect(Connection connection, DatabaseConfig config, long p004Id,
                                     String expectedLogin, boolean lock) throws SQLException {
        config.target().validateActualDatabase(connection, config);
        try (var statement = connection.prepareStatement("select participant_no, test_account, login_id from member where member_id=?"
                + (lock ? " for update" : ""))) {
            statement.setLong(1, p004Id);
            try (var rows = statement.executeQuery()) {
                if (!rows.next() || rows.getInt(1) != 4 || rows.getBoolean(2)
                        || !expectedLogin.equals(rows.getString(3)) || rows.next())
                    throw invalid("P004 식별 정보가 일치하지 않습니다.");
            }
        }
        try (var statement = connection.prepareStatement("select count(*) from member where login_id='test004'");
             var rows = statement.executeQuery()) {
            if (!rows.next() || rows.getInt(1) != 0 || rows.next())
                throw invalid("test004 계정이 이미 존재합니다.");
        }
        try (var statement = connection.createStatement();
             var rows = statement.executeQuery("select greatest(coalesce(max(participant_no),0)+1,900004) from member")) {
            if (!rows.next())
                throw invalid("다음 참가자 번호를 확인할 수 없습니다.");
            return new Preflight(rows.getInt(1));
        }
    }

    private static void recheck(ConnectionFactory connections, DatabaseConfig config, char[] databasePassword,
                                long p004Id, PrintStream err) {
        try (Connection connection = connections.open(config, databasePassword)) {
            connection.setReadOnly(true);
            config.target().validateActualDatabase(connection, config);
            try (var statement = connection.prepareStatement("""
                    select count(*) from member where login_id='test004'
                    and test_account=true and analysis_reference_member_id=?
                    """)) {
                statement.setLong(1, p004Id);
                try (var rows = statement.executeQuery()) {
                    rows.next();
                    err.println(rows.getInt(1) == 1 ? "recheck=PRESENT; 수동으로 행과 해시를 확인하세요."
                            : "recheck=ABSENT_OR_MISMATCH; 수동으로 DB 상태를 확인하세요.");
                }
            }
        } catch (SQLException | RuntimeException exception) {
            err.println("recheck=FAILED; 수동으로 DB 상태를 확인하세요.");
        }
    }

    private static String required(Map<String, String> environment, String name) {
        String value = environment.get(name);
        if (value == null || value.isBlank() || value.chars().anyMatch(Character::isISOControl))
            throw invalid("필수 접속 식별 설정이 없거나 유효하지 않습니다.");
        return value.strip();
    }

    private static String safeSqlState(SQLException exception) {
        String state = exception.getSQLState();
        return state == null ? "unknown" : state.replaceAll("[^A-Za-z0-9]", "");
    }

    private static IllegalArgumentException invalid(String message) {
        return new IllegalArgumentException(message);
    }
}
