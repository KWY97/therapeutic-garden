package com.example.manage.healingeffect;

import java.io.PrintStream;
import java.sql.*;
import java.util.*;

/** Explicit Production Raw-to-Summary rebuild entry point. It never creates schema or starts Spring. */
public final class ProductionHealingImprovementRebuildCli {
    private static final Set<String> VALUED = Set.of("--site-id",
            "--confirm-environment", "--confirm-source-sha");
    private static final Set<String> FLAGS = Set.of("--dry-run", "--write");

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
            var guard = new ProductionDatabaseTargetGuard();
            var config = guard.config(environment);
            var expected = guard.rebuildExpectations(environment);
            if (dryRun) ProductionCliArguments.rejectWriteConfirmationsForDryRun(options);
            else ProductionCliArguments.requireWriteConfirmations(options,
                    config.expectedSourceSha256(), false);
            var rebuildExpected = new HealingImprovementDatabaseRebuilder.ExpectedResult(
                    expected.overall(), expected.memberSpots(), expected.participantOverall());

            out.println("mode=" + (dryRun ? "DRY_RUN" : "WRITE") + ", target=" + config.targetLabel()
                    + ", siteId=" + siteId + ", expectedSiteName=" + config.expectedSiteName());
            out.println("expectedSourceSha256=" + config.expectedSourceSha256());

            HealingImprovementDatabaseRebuilder.Result preflight;
            try (var connection = connections.open(config)) {
                guard.validateTarget(connection, config, siteId);
                var state = guard.rawState(connection);
                guard.validateStoredRaw(state, config, siteId, expected.sessions(), expected.measurements());
                printState(out, state);
                preflight = new HealingImprovementDatabaseRebuilder().execute(
                        connection, siteId, true, rebuildExpected);
            }
            requireExpected(preflight, rebuildExpected);
            if (dryRun) {
                printResult(out, preflight);
                return 0;
            }

            HealingImprovementDatabaseRebuilder.Result result;
            try (var connection = connections.open(config)) {
                guard.validateTarget(connection, config, siteId);
                var state = guard.rawState(connection);
                guard.validateStoredRaw(state, config, siteId, expected.sessions(), expected.measurements());
                result = new HealingImprovementDatabaseRebuilder().execute(
                        connection, siteId, false, rebuildExpected);
            }
            requireExpected(result, rebuildExpected);
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

    private static void requireExpected(HealingImprovementDatabaseRebuilder.Result result,
            HealingImprovementDatabaseRebuilder.ExpectedResult expected) {
        boolean status = "DRY_RUN".equals(result.status()) || "REBUILT".equals(result.status());
        if (!status || !result.schemaReady() || result.overallRows() != expected.overallRows()
                || result.memberSpotRows() != expected.memberSpotRows()
                || result.participantOverallRows() != expected.participantOverallRows())
            throw new IllegalStateException("Rebuild 결과가 expected rollout 상태와 일치하지 않습니다.");
    }

    private static void printState(PrintStream out, ProductionDatabaseTargetGuard.RawState state) {
        out.println("rawBatches=" + state.batches() + ", rawSessions=" + state.sessions()
                + ", rawMeasurements=" + state.measurements());
    }

    private static void printResult(PrintStream out, HealingImprovementDatabaseRebuilder.Result result) {
        out.println("status=" + result.status() + ", schemaReady=" + result.schemaReady());
        out.println("overall=" + result.overallRows() + ", memberSpot=" + result.memberSpotRows()
                + ", participantOverall=" + result.participantOverallRows());
        result.warnings().forEach(out::println);
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
