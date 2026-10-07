package com.example.manage.healingeffect;

import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import java.nio.file.*;
import java.sql.*;
import java.util.*;

final class ProductionCliTestFixture {
    static final String SITE_NAME = "Production Site";
    final DriverManagerDataSource dataSource;
    final JdbcTemplate jdbc;

    ProductionCliTestFixture() {
        dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:prod_cli_" + UUID.randomUUID().toString().replace("-", "")
                        + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("create table site(site_id bigint primary key,name varchar(100) not null)");
        jdbc.execute("create table member(member_id bigint primary key,participant_no int)");
        jdbc.execute("create table healing_course(course_id bigint primary key,site_id bigint,code varchar(10))");
        jdbc.execute("create table healing_spot(spot_id bigint primary key,course_id bigint,code varchar(10),name varchar(100))");
        jdbc.update("insert into site values(1,?)", SITE_NAME);
        jdbc.update("insert into member values(4,1)");
        jdbc.update("insert into healing_course values(10,1,'HC-A')");
        jdbc.update("insert into healing_spot values(1,10,'HS1','호스타 정원'),(2,10,'HS2','곶자왈원')");
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
        jdbc.update("insert into healing_spot_effect_summary(spot_id,stress_valid_session_count,emotional_valid_session_count) values(1,1,1),(2,0,0)");
        jdbc.update("insert into member_healing_spot_effect_summary(member_id,spot_id,stress_valid_session_count,emotional_valid_session_count) values(4,1,1,1),(4,2,0,0)");
    }

    void rawSchema() throws Exception {
        for (String sql : Files.readString(Path.of("docs/healing-measurement-schema.sql")).split(";"))
            if (!sql.isBlank()) jdbc.execute(sql);
    }

    void seedRawForRebuild(String sha) {
        jdbc.update("insert into healing_measurement_import_batch values(1,?,'raw.xlsx',1,current_timestamp,1,1)", sha);
        jdbc.update("insert into healing_measurement_session values(1,1,2,4,10,'2026-08-14','HS1 호스타 정원',10,10)");
        jdbc.update("insert into healing_spot_measurement values(1,1,1,9,11)");
    }

    Map<String, String> environment(String sha, int sessions, int measurements,
            int overall, int memberSpots, int participantOverall) throws Exception {
        String database;
        try (var connection = dataSource.getConnection();
             var statement = connection.prepareStatement("select database()");
             var rows = statement.executeQuery()) {
            rows.next();
            database = rows.getString(1);
        }
        var env = new HashMap<String, String>();
        env.put(ProductionDatabaseTargetGuard.JDBC_URL, "jdbc:mysql://production-mysql.internal:3306/manage");
        env.put(ProductionDatabaseTargetGuard.DB_USER, "test-user");
        env.put(ProductionDatabaseTargetGuard.DB_PASSWORD, "test-password-secret");
        env.put(ProductionDatabaseTargetGuard.EXPECTED_DATABASE, database);
        env.put(ProductionDatabaseTargetGuard.EXPECTED_SITE_NAME, SITE_NAME);
        env.put(ProductionDatabaseTargetGuard.EXPECTED_SOURCE_SHA256, sha);
        env.put(ProductionDatabaseTargetGuard.TARGET_LABEL, "railway-production-test");
        env.put(ProductionDatabaseTargetGuard.EXPECTED_RAW_SESSION_COUNT, Integer.toString(sessions));
        env.put(ProductionDatabaseTargetGuard.EXPECTED_RAW_MEASUREMENT_COUNT, Integer.toString(measurements));
        env.put(ProductionDatabaseTargetGuard.EXPECTED_OVERALL_COUNT, Integer.toString(overall));
        env.put(ProductionDatabaseTargetGuard.EXPECTED_MEMBER_SPOT_COUNT, Integer.toString(memberSpots));
        env.put(ProductionDatabaseTargetGuard.EXPECTED_PARTICIPANT_OVERALL_COUNT,
                Integer.toString(participantOverall));
        return env;
    }

    ProductionDatabaseTargetGuard.ConnectionFactory connections() {
        return ignored -> dataSource.getConnection();
    }

    static Path workbook(Path directory) throws Exception {
        Path path = directory.resolve("synthetic-raw.xlsx").toAbsolutePath();
        try (var workbook = new XSSFWorkbook()) {
            var sheet = workbook.createSheet(RawMeasurementParser.SHEET);
            var header = sheet.createRow(0);
            for (int i = 0; i < RawMeasurementParser.HEADERS.size(); i++)
                header.createCell(i).setCellValue(RawMeasurementParser.HEADERS.get(i));
            for (int i = 1; i <= 2; i++) {
                var row = sheet.createRow(i);
                row.createCell(0).setCellValue("P001");
                row.createCell(1).setCellValue("2026-08-" + (13 + i));
                row.createCell(2).setCellValue("HC-A (A 코스)");
                row.createCell(3).setCellValue("HS1 호스타 정원");
                row.createCell(4).setCellValue(20);
                row.createCell(5).setCellValue(10);
                row.createCell(6).setCellValue(15);
                row.createCell(7).setCellValue(12);
            }
            try (var output = Files.newOutputStream(path)) { workbook.write(output); }
        }
        return path;
    }
}
