package com.example.manage.healingeffect;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.*;
import java.nio.file.Path;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class ProductionRawMeasurementImportCliTests {
    @TempDir Path directory;

    private record Run(int exit, String output) {}

    private Run run(ProductionCliTestFixture fixture, Path file, Map<String, String> env, String... args) {
        var output = new ByteArrayOutputStream();
        var stream = new PrintStream(output);
        int exit = ProductionRawMeasurementImportCli.run(args, env, stream, stream, fixture.connections());
        return new Run(exit, output.toString());
    }

    private String[] dryArgs(Path file) {
        return new String[]{"--file", file.toString(), "--site-id", "1", "--dry-run"};
    }

    private String[] writeArgs(Path file, String sha) {
        return new String[]{"--file", file.toString(), "--site-id", "1", "--write",
                "--confirm-environment", "production", "--confirm-source-sha", sha,
                "--confirm-empty-raw"};
    }

    @Test void parserIsFailClosedAndWriteRequiresEveryConfirmation() throws Exception {
        var fixture = new ProductionCliTestFixture();
        fixture.rawSchema();
        Path file = ProductionCliTestFixture.workbook(directory);
        String sha = new RawMeasurementParser().parse(file).sha256();
        var env = fixture.environment(sha, 2, 2, 2, 2, 1);

        for (String[] args : List.of(
                new String[]{},
                new String[]{"--file", file.toString(), "--site-id", "1"},
                new String[]{"--file", file.toString(), "--site-id", "1", "--dry-run", "--write"},
                new String[]{"--file", file.toString(), "--site-id", "0", "--dry-run"},
                new String[]{"--file", file.toString(), "--site-id", "1", "--dry-run", "--unknown"},
                new String[]{"--site-id", "1", "--dry-run"},
                new String[]{"--file", "relative.xlsx", "--site-id", "1", "--dry-run"},
                new String[]{"--file", file.toString(), "--site-id", "1", "--write"},
                new String[]{"--file", file.toString(), "--site-id", "1", "--write",
                        "--confirm-environment", "staging", "--confirm-source-sha", sha,
                        "--confirm-empty-raw"},
                new String[]{"--file", file.toString(), "--site-id", "1", "--write",
                        "--confirm-environment", "production", "--confirm-source-sha", "bad",
                        "--confirm-empty-raw"},
                new String[]{"--file", file.toString(), "--site-id", "1", "--write",
                        "--confirm-environment", "production", "--confirm-source-sha", sha})) {
            assertThat(run(fixture, file, env, args).exit()).isEqualTo(1);
        }
        assertThat(fixture.jdbc.queryForObject("select count(*) from healing_measurement_import_batch", Integer.class)).isZero();
    }

    @Test void dryRunValidatesEverythingWithoutWriting() throws Exception {
        var fixture = new ProductionCliTestFixture();
        fixture.rawSchema();
        Path file = ProductionCliTestFixture.workbook(directory);
        String sha = new RawMeasurementParser().parse(file).sha256();
        var result = run(fixture, file, fixture.environment(sha, 2, 2, 2, 2, 1), dryArgs(file));

        assertThat(result.exit()).isZero();
        assertThat(result.output()).contains("mode=DRY_RUN", "sourceSha256=" + sha,
                "rawExistingBatches=0", "status=DRY_RUN", "sessions=2", "measurements=2");
        for (String table : RawMeasurementDatabaseImporter.TABLES)
            assertThat(fixture.jdbc.queryForObject("select count(*) from " + table, Integer.class)).isZero();
    }

    @Test void rejectsWrongShaMissingEnvironmentSchemaAndUnexpectedRaw() throws Exception {
        Path file = ProductionCliTestFixture.workbook(directory);
        String sha = new RawMeasurementParser().parse(file).sha256();

        var noEnv = new ProductionCliTestFixture();
        noEnv.rawSchema();
        assertThat(run(noEnv, file, Map.of(), dryArgs(file)).exit()).isEqualTo(1);

        var wrongSha = new ProductionCliTestFixture();
        wrongSha.rawSchema();
        assertThat(run(wrongSha, file, wrongSha.environment("b".repeat(64), 2, 2, 2, 2, 1), dryArgs(file)).output())
                .contains("parsed source SHA");

        var noSchema = new ProductionCliTestFixture();
        assertThat(run(noSchema, file, noSchema.environment(sha, 2, 2, 2, 2, 1), dryArgs(file)).output())
                .contains("Raw measurement schema 준비 필요");

        var unexpected = new ProductionCliTestFixture();
        unexpected.rawSchema();
        unexpected.jdbc.update("insert into healing_measurement_import_batch values(1,?,'other.xlsx',1,current_timestamp,1,1)", "c".repeat(64));
        assertThat(run(unexpected, file, unexpected.environment(sha, 2, 2, 2, 2, 1), dryArgs(file)).output())
                .contains("empty 또는 동일 source");
    }

    @Test void firstWriteSucceedsAndSameSourceIsSafeNoOp() throws Exception {
        var fixture = new ProductionCliTestFixture();
        fixture.rawSchema();
        Path file = ProductionCliTestFixture.workbook(directory);
        String sha = new RawMeasurementParser().parse(file).sha256();
        var env = fixture.environment(sha, 2, 2, 2, 2, 1);

        var first = run(fixture, file, env, writeArgs(file, sha));
        assertThat(first.exit()).isZero();
        assertThat(first.output()).contains("mode=WRITE", "status=IMPORTED");
        assertThat(fixture.jdbc.queryForObject("select count(*) from healing_measurement_import_batch", Integer.class)).isEqualTo(1);
        assertThat(fixture.jdbc.queryForObject("select count(*) from healing_measurement_session", Integer.class)).isEqualTo(2);
        assertThat(fixture.jdbc.queryForObject("select count(*) from healing_spot_measurement", Integer.class)).isEqualTo(2);

        var second = run(fixture, file, env, writeArgs(file, sha));
        assertThat(second.exit()).isZero();
        assertThat(second.output()).contains("status=ALREADY_IMPORTED");
        assertThat(fixture.jdbc.queryForObject("select count(*) from healing_measurement_import_batch", Integer.class)).isEqualTo(1);
    }

    @Test void importerFailureRollsBackAndLogsNeverExposeSecretsOrJdbcUrl() throws Exception {
        var fixture = new ProductionCliTestFixture();
        fixture.rawSchema();
        fixture.jdbc.execute("alter table healing_spot_measurement add constraint synthetic_fail check(stress_post < 0)");
        Path file = ProductionCliTestFixture.workbook(directory);
        String sha = new RawMeasurementParser().parse(file).sha256();
        var env = new HashMap<>(fixture.environment(sha, 2, 2, 2, 2, 1));
        String password = env.get(ProductionDatabaseTargetGuard.DB_PASSWORD);
        String url = env.get(ProductionDatabaseTargetGuard.JDBC_URL);

        var result = run(fixture, file, env, writeArgs(file, sha));
        assertThat(result.exit()).isEqualTo(1);
        assertThat(result.output()).contains("DATABASE_FAILED", "rollback")
                .doesNotContain(password, url, env.get(ProductionDatabaseTargetGuard.DB_USER));
        for (String table : RawMeasurementDatabaseImporter.TABLES)
            assertThat(fixture.jdbc.queryForObject("select count(*) from " + table, Integer.class)).isZero();

        var output = new ByteArrayOutputStream();
        var stream = new PrintStream(output);
        int exit = ProductionRawMeasurementImportCli.run(dryArgs(file), env, stream, stream,
                config -> { throw new IllegalStateException(config.jdbcUrl() + " " + config.user()
                        + " " + config.password()); });
        assertThat(exit).isEqualTo(1);
        assertThat(output.toString()).contains("[REDACTED]")
                .doesNotContain(password, url, env.get(ProductionDatabaseTargetGuard.DB_USER));
    }
}
