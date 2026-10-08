package com.example.manage.passwordreset;

import java.io.PrintStream;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/** One-time P001-P011 reset utility. It uses JDBC directly and never starts Spring or a web server. */
public final class ParticipantPasswordResetCli {
    @FunctionalInterface
    interface ConnectionFactory {
        Connection open(DatabaseConfig config, char[] databasePassword) throws SQLException;
    }

    private ParticipantPasswordResetCli() {}

    public static void main(String[] args) {
        int exit;
        try (SecretTerminal terminal = SecretTerminal.system()) {
            exit = run(args, System.getenv(), System.out, System.err, terminal, new SecureRandom(),
                    (config, password) -> DriverManager.getConnection(config.jdbcUrl(), config.user(),
                            new String(password)));
        } catch (RuntimeException ex) {
            System.err.println("TERMINAL_REQUIRED: 대화형 로컬 터미널에서 실행해야 합니다.");
            exit = 1;
        }
        if (exit != 0) System.exit(exit);
    }

    static int run(String[] args, Map<String, String> environment, PrintStream out, PrintStream err,
            SecretTerminal terminal, SecureRandom random, ConnectionFactory connections) {
        char[] databasePassword = null;
        Map<Integer, String> hashes = null;
        try {
            ResetArguments options = ResetArguments.parse(args);
            DatabaseConfig config = options.target().config(environment, options.allowProductionTunnel());
            out.println("mode=" + options.modeLabel() + ", target=" + options.target()
                    + ", productionTunnel=" + (options.allowProductionTunnel() ? "EXPLICITLY_ALLOWED" : "NO")
                    + ", ddlAuto=none, webServer=off");

            databasePassword = terminal.readSecret(options.target() + " DB password: ");
            if (databasePassword.length == 0) throw new IllegalArgumentException("DB password가 비어 있습니다.");

            ParticipantPasswordResetter resetter = new ParticipantPasswordResetter();
            List<ParticipantPasswordResetter.Account> accounts;
            try (Connection connection = connections.open(config, databasePassword)) {
                options.target().validateActualDatabase(connection, config);
                accounts = resetter.inspect(connection, false);
            }
            out.println("preflight=OK, exactParticipants=11");
            for (var account : accounts) {
                out.println("P%03d memberId=%d loginId=%s".formatted(
                        account.participantNo(), account.memberId(), account.maskedLoginId()));
            }

            try (ParticipantPasswords passwords = options.passwordMode() == PasswordMode.GENERATE
                    ? ParticipantPasswords.generate(random, terminal)
                    : ParticipantPasswords.readForReuse(terminal)) {
                hashes = resetter.encode(passwords);
            }
            out.println("passwordValidation=OK, count=11, minimumLength=16, allDistinct=true, bcrypt=true");

            if (!options.write()) {
                out.println("status=DRY_RUN_OK, changedRows=0");
                return 0;
            }

            out.println("sessionWarning=password change does not invalidate existing HTTP sessions");
            String expectedConfirmation = "APPLY " + options.target() + " P001-P011";
            String confirmation = terminal.readLine("변경하려면 '" + expectedConfirmation + "' 입력: ");
            if (!expectedConfirmation.equals(confirmation)) {
                throw new IllegalArgumentException("확인 문구가 일치하지 않아 변경하지 않았습니다.");
            }

            try (Connection connection = connections.open(config, databasePassword)) {
                options.target().validateActualDatabase(connection, config);
                resetter.write(connection, hashes);
            }
            out.println("status=WRITE_COMMITTED, changedRows=11, target=" + options.target());
            return 0;
        } catch (IllegalArgumentException ex) {
            err.println("VALIDATION_FAILED: " + ex.getMessage());
            return 1;
        } catch (SQLException ex) {
            err.println("DATABASE_FAILED SQLState=" + safeSqlState(ex)
                    + "; transaction rollback attempted; verify database state before retry");
            return 1;
        } catch (RuntimeException ex) {
            err.println("RESET_FAILED: no secret or connection detail was logged; verify database state before retry");
            return 1;
        } finally {
            if (databasePassword != null) Arrays.fill(databasePassword, '\0');
            if (hashes != null) hashes.replaceAll((participantNo, hash) -> "[CLEARED]");
        }
    }

    private static String safeSqlState(SQLException ex) {
        String state = ex.getSQLState();
        return state == null ? "unknown" : state.replaceAll("[^A-Za-z0-9]", "");
    }
}
