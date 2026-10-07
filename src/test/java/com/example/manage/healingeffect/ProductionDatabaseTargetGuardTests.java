package com.example.manage.healingeffect;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class ProductionDatabaseTargetGuardTests {
    private static final String SHA = "a".repeat(64);

    @Test void requiresSeparatedProductionEnvironmentAndRejectsLocalUrls() throws Exception {
        var fixture = new ProductionCliTestFixture();
        var guard = new ProductionDatabaseTargetGuard();
        assertThatThrownBy(() -> guard.config(Map.of()))
                .hasMessageContaining(ProductionDatabaseTargetGuard.JDBC_URL);

        var env = new HashMap<>(fixture.environment(SHA, 1, 1, 2, 2, 1));
        env.put(ProductionDatabaseTargetGuard.JDBC_URL, "jdbc:mysql://127.0.0.1:3306/manage");
        assertThatThrownBy(() -> guard.config(env)).hasMessageContaining("remote MySQL");
    }

    @Test void validatesActualDatabaseAndExactSiteName() throws Exception {
        var fixture = new ProductionCliTestFixture();
        var guard = new ProductionDatabaseTargetGuard();
        var correctEnv = new HashMap<>(fixture.environment(SHA, 1, 1, 2, 2, 1));
        try (var connection = fixture.dataSource.getConnection()) {
            guard.validateTarget(connection, guard.config(correctEnv), 1);
            var wrongDatabaseEnv = new HashMap<>(correctEnv);
            wrongDatabaseEnv.put(ProductionDatabaseTargetGuard.EXPECTED_DATABASE, "wrong_database");
            assertThatThrownBy(() -> guard.validateTarget(connection, guard.config(wrongDatabaseEnv), 1))
                    .hasMessageContaining("database");
        }
        var wrongSiteEnv = new HashMap<>(fixture.environment(SHA, 1, 1, 2, 2, 1));
        wrongSiteEnv.put(ProductionDatabaseTargetGuard.EXPECTED_SITE_NAME, "Wrong Site");
        try (var connection = fixture.dataSource.getConnection()) {
            var config = guard.config(wrongSiteEnv);
            assertThatThrownBy(() -> guard.validateTarget(connection, config, 1))
                    .hasMessageContaining("Site");
        }
    }

    @Test void distinguishesEmptySameCompleteAndUnexpectedRawStates() throws Exception {
        var fixture = new ProductionCliTestFixture();
        fixture.rawSchema();
        var guard = new ProductionDatabaseTargetGuard();
        var config = guard.config(fixture.environment(SHA, 1, 1, 2, 2, 1));
        try (var connection = fixture.dataSource.getConnection()) {
            var empty = guard.rawState(connection);
            assertThat(empty.empty()).isTrue();
            guard.validateInitialOrSameRaw(empty, config, 1, 1, 1);
        }
        fixture.seedRawForRebuild(SHA);
        try (var connection = fixture.dataSource.getConnection()) {
            var same = guard.rawState(connection);
            guard.validateInitialOrSameRaw(same, config, 1, 1, 1);
            guard.validateStoredRaw(same, config, 1, 1, 1);
            assertThatThrownBy(() -> guard.validateStoredRaw(same, config, 1, 2, 1))
                    .hasMessageContaining("Raw import");
        }
    }
}
