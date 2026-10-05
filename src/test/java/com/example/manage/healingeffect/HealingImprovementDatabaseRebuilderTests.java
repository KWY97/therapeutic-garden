package com.example.manage.healingeffect;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import java.sql.Connection;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;

class HealingImprovementDatabaseRebuilderTests {
    private DriverManagerDataSource database() throws Exception {
        var dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        var jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("create table site(site_id bigint primary key)");
        jdbc.execute("create table member(member_id bigint primary key, participant_no int)");
        jdbc.execute("create table healing_course(course_id bigint primary key, site_id bigint, code varchar(10))");
        jdbc.execute("create table healing_spot(spot_id bigint primary key, course_id bigint, code varchar(10), name varchar(100))");
        jdbc.update("insert into site values(1)");
        jdbc.update("insert into member values(4,4)");
        jdbc.update("insert into healing_course values(10,1,'HC-A')");
        jdbc.update("insert into healing_spot values(1,10,'HS1','호스타 정원'),(2,10,'HS2','곶자왈원')");
        for (String sql : java.nio.file.Files.readString(java.nio.file.Path.of("docs/healing-measurement-schema.sql")).split(";"))
            if (!sql.isBlank()) jdbc.execute(sql);
        jdbc.execute("""
                create table healing_spot_effect_summary(
                  id bigint auto_increment primary key, spot_id bigint not null unique,
                  stress_valid_session_count int not null, emotional_valid_session_count int not null,
                  participant_count int null, total_experience_count int null,
                  stress_improved_count int null, emotional_improved_count int null)
                """);
        jdbc.execute("""
                create table member_healing_spot_effect_summary(
                  id bigint auto_increment primary key, member_id bigint not null, spot_id bigint not null,
                  stress_valid_session_count int not null, emotional_valid_session_count int not null,
                  total_experience_count int null, stress_improved_count int null, emotional_improved_count int null,
                  unique(member_id,spot_id))
                """);
        jdbc.update("insert into healing_measurement_import_batch values(1,'sha','raw.xlsx',1,current_timestamp,1,1)");
        jdbc.update("insert into healing_measurement_session values(1,1,2,4,10,'2026-08-14','HS1 호스타 정원',10,10)");
        jdbc.update("insert into healing_spot_measurement values(1,1,1,9,11)");
        jdbc.update("insert into healing_spot_effect_summary(spot_id,stress_valid_session_count,emotional_valid_session_count) values(1,1,1),(2,0,0)");
        jdbc.update("insert into member_healing_spot_effect_summary(member_id,spot_id,stress_valid_session_count,emotional_valid_session_count) values(4,1,1,1),(4,2,0,0)");
        return dataSource;
    }

    @Test void dryRunDoesNotWriteAndRepeatedRebuildSetsCountsWithoutAccumulating() throws Exception {
        var dataSource = database();
        var jdbc = new JdbcTemplate(dataSource);
        var rebuilder = new HealingImprovementDatabaseRebuilder();
        try (Connection connection = dataSource.getConnection()) {
            var result = rebuilder.execute(connection, 1, true);
            assertThat(result.status()).isEqualTo("DRY_RUN");
            assertThat(result.overallRows()).isEqualTo(2);
            assertThat(result.memberSpotRows()).isEqualTo(2);
            assertThat(result.participantOverallRows()).isEqualTo(1);
        }
        assertThat(jdbc.queryForObject("select total_experience_count from healing_spot_effect_summary where spot_id=1", Integer.class)).isNull();

        for (int i = 0; i < 2; i++) try (Connection connection = dataSource.getConnection()) {
            assertThat(rebuilder.execute(connection, 1, false).status()).isEqualTo("REBUILT");
        }
        assertThat(jdbc.queryForMap("select * from healing_spot_effect_summary where spot_id=1"))
                .containsEntry("PARTICIPANT_COUNT", 1).containsEntry("TOTAL_EXPERIENCE_COUNT", 1)
                .containsEntry("STRESS_IMPROVED_COUNT", 1).containsEntry("EMOTIONAL_IMPROVED_COUNT", 1);
        assertThat(jdbc.queryForMap("select * from healing_spot_effect_summary where spot_id=2"))
                .containsEntry("PARTICIPANT_COUNT", 1).containsEntry("TOTAL_EXPERIENCE_COUNT", 1)
                .containsEntry("STRESS_IMPROVED_COUNT", 0).containsEntry("EMOTIONAL_IMPROVED_COUNT", 0);
    }
}
