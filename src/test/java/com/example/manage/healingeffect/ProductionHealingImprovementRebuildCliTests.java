package com.example.manage.healingeffect;

import org.junit.jupiter.api.Test;
import java.io.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class ProductionHealingImprovementRebuildCliTests {
    private static final String SHA = "d".repeat(64);
    private record Run(int exit, String output) {}

    private Run run(ProductionCliTestFixture fixture, Map<String, String> env, String... args) {
        var output = new ByteArrayOutputStream();
        var stream = new PrintStream(output);
        int exit = ProductionHealingImprovementRebuildCli.run(
                args, env, stream, stream, fixture.connections());
        return new Run(exit, output.toString());
    }

    private Map<String, String> environment(ProductionCliTestFixture fixture) throws Exception {
        return fixture.environment(SHA, 1, 1, 2, 2, 1);
    }

    private String[] writeArgs() {
        return new String[]{"--site-id", "1", "--write", "--confirm-environment", "production",
                "--confirm-source-sha", SHA};
    }

    private ProductionCliTestFixture readyFixture() throws Exception {
        var fixture = new ProductionCliTestFixture();
        fixture.rawSchema();
        fixture.seedRawForRebuild(SHA);
        return fixture;
    }

    @Test void parserIsFailClosedAndWriteRequiresConfirmations() throws Exception {
        var fixture = readyFixture();
        var env = environment(fixture);
        for (String[] args : List.of(
                new String[]{},
                new String[]{"--site-id", "1"},
                new String[]{"--site-id", "1", "--dry-run", "--write"},
                new String[]{"--site-id", "0", "--dry-run"},
                new String[]{"--site-id", "1", "--dry-run", "--unknown"},
                new String[]{"--site-id", "1", "--write"},
                new String[]{"--site-id", "1", "--write", "--confirm-environment", "staging",
                        "--confirm-source-sha", SHA},
                new String[]{"--site-id", "1", "--write", "--confirm-environment", "production",
                        "--confirm-source-sha", "bad"})) {
            assertThat(run(fixture, env, args).exit()).isEqualTo(1);
        }
        assertThat(fixture.jdbc.queryForObject(
                "select total_experience_count from healing_spot_effect_summary where spot_id=1", Integer.class)).isNull();
    }

    @Test void dryRunValidatesStoredRawCoverageAndExpectedCountsWithoutWriting() throws Exception {
        var fixture = readyFixture();
        var result = run(fixture, environment(fixture), "--site-id", "1", "--dry-run");

        assertThat(result.exit()).isZero();
        assertThat(result.output()).contains("mode=DRY_RUN", "rawBatches=1", "rawSessions=1",
                "rawMeasurements=1", "status=DRY_RUN", "overall=2", "memberSpot=2",
                "participantOverall=1");
        assertThat(fixture.jdbc.queryForObject(
                "select total_experience_count from healing_spot_effect_summary where spot_id=1", Integer.class)).isNull();
    }

    @Test void rejectsMissingEnvironmentRawShaCoverageValidCountAndExpectedResultMismatch() throws Exception {
        var noEnv = readyFixture();
        assertThat(run(noEnv, Map.of(), "--site-id", "1", "--dry-run").exit()).isEqualTo(1);

        var wrongSha = readyFixture();
        var wrongShaEnv = new HashMap<>(environment(wrongSha));
        wrongShaEnv.put(ProductionDatabaseTargetGuard.EXPECTED_SOURCE_SHA256, "e".repeat(64));
        assertThat(run(wrongSha, wrongShaEnv, "--site-id", "1", "--dry-run").output())
                .contains("Raw import batch/count/SHA");

        var coverage = readyFixture();
        coverage.jdbc.update("delete from member_healing_spot_effect_summary where spot_id=2");
        assertThat(run(coverage, environment(coverage), "--site-id", "1", "--dry-run").output())
                .contains("coverage/valid count 불일치");

        var valid = readyFixture();
        valid.jdbc.update("update healing_spot_effect_summary set stress_valid_session_count=0 where spot_id=1");
        assertThat(run(valid, environment(valid), "--site-id", "1", "--dry-run").output())
                .contains("coverage/valid count 불일치");

        var expected = readyFixture();
        var expectedEnv = new HashMap<>(environment(expected));
        expectedEnv.put(ProductionDatabaseTargetGuard.EXPECTED_PARTICIPANT_OVERALL_COUNT, "2");
        assertThat(run(expected, expectedEnv, "--site-id", "1", "--dry-run").output())
                .contains("expected rollout count");
    }

    @Test void writeFailureRollsBackAndDoesNotExposeSecrets() throws Exception {
        var fixture = readyFixture();
        fixture.jdbc.execute("alter table healing_spot_effect_summary add constraint synthetic_fail check(stress_improved_count < 0)");
        var env = environment(fixture);
        var result = run(fixture, env, writeArgs());

        assertThat(result.exit()).isEqualTo(1);
        assertThat(result.output()).contains("DATABASE_FAILED", "rollback")
                .doesNotContain(env.get(ProductionDatabaseTargetGuard.DB_PASSWORD),
                        env.get(ProductionDatabaseTargetGuard.DB_USER),
                        env.get(ProductionDatabaseTargetGuard.JDBC_URL));
        assertThat(fixture.jdbc.queryForObject(
                "select total_experience_count from healing_spot_effect_summary where spot_id=1", Integer.class)).isNull();
        assertThat(fixture.jdbc.queryForObject(
                "select total_experience_count from member_healing_spot_effect_summary where spot_id=1", Integer.class)).isNull();
    }

    @Test void repeatedWriteIsIdempotent() throws Exception {
        var fixture = readyFixture();
        var env = environment(fixture);
        for (int i = 0; i < 2; i++) {
            var result = run(fixture, env, writeArgs());
            assertThat(result.exit()).isZero();
            assertThat(result.output()).contains("status=REBUILT", "overall=2", "memberSpot=2");
        }
        assertThat(fixture.jdbc.queryForMap("select * from healing_spot_effect_summary where spot_id=1"))
                .containsEntry("PARTICIPANT_COUNT", 1).containsEntry("TOTAL_EXPERIENCE_COUNT", 1)
                .containsEntry("STRESS_IMPROVED_COUNT", 1).containsEntry("EMOTIONAL_IMPROVED_COUNT", 1);
    }
}
