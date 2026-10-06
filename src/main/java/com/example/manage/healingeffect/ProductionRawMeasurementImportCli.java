package com.example.manage.healingeffect;

import java.io.PrintStream;
import java.nio.file.Path;
import java.sql.*;
import java.util.*;

/** Explicit Production raw import entry point. It never creates schema or starts Spring. */
public final class ProductionRawMeasurementImportCli {
    private static final Set<String> VALUED = Set.of("--file", "--site-id",
            "--confirm-environment", "--confirm-source-sha");
    private static final Set<String> FLAGS = Set.of("--dry-run", "--write", "--confirm-empty-raw");

    public static void main(String[] args) {
        int exit = run(args, System.getenv(), System.out, System.err,
                config -> DriverManager.getConnection(config.jdbcUrl(), config.user(), config.password()));
        if (exit != 0) System.exit(exit);
    }

    static int run(String[] args, Map<String, String> environment, PrintStream out, PrintStream err,
            ProductionDatabaseTargetGuard.ConnectionFactory connections) {
        try {
            var options = ProductionCliArguments.parse(args, VALUED, FLAGS);
            boolean dryRun = ProductionCliArguments.dryRun(options);
            long siteId = ProductionCliArguments.positiveSiteId(options);
            String filename = options.get("--file");
            if (filename == null) throw new IllegalArgumentException("--file <absolute-xlsx-path>가 필요합니다.");
            Path file = Path.of(filename);
            if (!file.isAbsolute()) throw new IllegalArgumentException("--file은 absolute path여야 합니다.");

            var guard = new ProductionDatabaseTargetGuard();
            var config = guard.config(environment);
            var expected = guard.rawExpectations(environment);
            if (dryRun) ProductionCliArguments.rejectWriteConfirmationsForDryRun(options);
            else ProductionCliArguments.requireWriteConfirmations(options,
                    config.expectedSourceSha256(), true);

            var source = new RawMeasurementParser().parse(file);
            if (!config.expectedSourceSha256().equals(source.sha256()))
                throw new IllegalArgumentException("parsed source SHA가 expected SHA와 일치하지 않습니다.");
            if (source.sessions().size() != expected.sessions()
                    || source.measurementCount() != expected.measurements())
                throw new IllegalArgumentException("parsed Raw count가 expected rollout count와 일치하지 않습니다.");

            out.println("mode=" + (dryRun ? "DRY_RUN" : "WRITE") + ", target=" + config.targetLabel()
                    + ", siteId=" + siteId + ", expectedSiteName=" + config.expectedSiteName());
            out.println("sourceSha256=" + source.sha256() + ", parsedSessions=" + source.sessions().size()
                    + ", parsedMeasurements=" + source.measurementCount());

            RawMeasurementDatabaseImporter.Result preflight;
            try (var connection = connections.open(config)) {
                guard.validateTarget(connection, config, siteId);
                guard.requireRawSchema(connection);
                guard.validateSummaryCounts(connection, siteId, expected.overall(), expected.memberSpots());
                var state = guard.rawState(connection);
                guard.validateInitialOrSameRaw(state, config, siteId, expected.sessions(), expected.measurements());
                printState(out, state);
                preflight = new RawMeasurementDatabaseImporter().execute(connection, source, siteId, true);
            }
            validatePreflight(preflight, expected);
            if (dryRun) {
                printResult(out, preflight);
                return 0;
            }

            RawMeasurementDatabaseImporter.Result result;
            try (var connection = connections.open(config)) {
                guard.validateTarget(connection, config, siteId);
                guard.requireRawSchema(connection);
                guard.validateSummaryCounts(connection, siteId, expected.overall(), expected.memberSpots());
                var state = guard.rawState(connection);
                guard.validateInitialOrSameRaw(state, config, siteId, expected.sessions(), expected.measurements());
                result = new RawMeasurementDatabaseImporter().execute(connection, source, siteId, false);
            }
            if (!("IMPORTED".equals(result.status()) && result.sessions() == expected.sessions()
                    && result.measurements() == expected.measurements())
                    && !("ALREADY_IMPORTED".equals(result.status())
                    && result.sessions() == 0 && result.measurements() == 0))
                throw new IllegalStateException("Raw importer 결과가 expected 상태와 일치하지 않습니다.");
            printResult(out, result);
            return 0;
        } catch (SQLException ex) {
            err.println("DATABASE_FAILED SQLState=" + safeSqlState(ex) + "; rollback");
            return 1;
        } catch (Exception ex) {
            err.println("VALIDATION_FAILED: " + safeMessage(ex, environment));
            return 1;
        }
    }

    private static void validatePreflight(RawMeasurementDatabaseImporter.Result result,
            ProductionDatabaseTargetGuard.RawExpectations expected) {
        boolean fresh = "DRY_RUN".equals(result.status()) && result.schemaReady()
                && result.sessions() == expected.sessions() && result.measurements() == expected.measurements();
        boolean already = "ALREADY_IMPORTED".equals(result.status()) && result.schemaReady()
                && result.sessions() == 0 && result.measurements() == 0;
        if (!fresh && !already) throw new IllegalStateException("Raw dry-run 결과가 expected 상태와 일치하지 않습니다.");
    }

    private static void printState(PrintStream out, ProductionDatabaseTargetGuard.RawState state) {
        out.println("rawExistingBatches=" + state.batches() + ", rawExistingSessions=" + state.sessions()
                + ", rawExistingMeasurements=" + state.measurements());
    }

    private static void printResult(PrintStream out, RawMeasurementDatabaseImporter.Result result) {
        out.println("status=" + result.status() + ", schemaReady=" + result.schemaReady()
                + ", sessions=" + result.sessions() + ", measurements=" + result.measurements());
    }

    private static String safeSqlState(SQLException ex) {
        return ex.getSQLState() == null ? "unknown" : ex.getSQLState().replaceAll("[^A-Za-z0-9]", "");
    }

    private static String safeMessage(Exception ex, Map<String, String> environment) {
        String message = ex.getMessage();
        if (message == null || message.isBlank()) return ex.getClass().getSimpleName();
        for (String key : List.of(ProductionDatabaseTargetGuard.JDBC_URL,
                ProductionDatabaseTargetGuard.DB_USER, ProductionDatabaseTargetGuard.DB_PASSWORD)) {
            String secret = environment.get(key);
            if (secret != null && !secret.isBlank()) message = message.replace(secret, "[REDACTED]");
        }
        return message.replaceAll("jdbc:mysql://\\S+", "[REDACTED_JDBC_URL]");
    }
}
