package com.example.manage.healingeffect;

import java.sql.*;
import java.util.*;

/** Opt-in localhost-only count rebuild. No Spring startup, schema creation, or row deletion. */
public final class HealingImprovementRebuildCli {
    public static void main(String[] args) {
        try {
            var options = options(args);
            String url = System.getenv("HEALING_IMPORT_JDBC_URL");
            if (url == null || !url.matches("jdbc:mysql://(localhost|127\\.0\\.0\\.1)(:[0-9]{1,5})?/[A-Za-z0-9_]+"))
                throw new IllegalArgumentException("로컬 MySQL URL만 허용합니다.");
            try (var connection = DriverManager.getConnection(url,
                    required("HEALING_IMPORT_DB_USER"), required("HEALING_IMPORT_DB_PASSWORD"))) {
                var result = new HealingImprovementDatabaseRebuilder().execute(connection,
                        Long.parseLong(options.get("--site-id")), options.containsKey("--dry-run"));
                System.out.println("status=" + result.status() + ", schemaReady=" + result.schemaReady());
                System.out.println("overall=" + result.overallRows() + ", memberSpot=" + result.memberSpotRows()
                        + ", participantOverall=" + result.participantOverallRows());
                result.warnings().forEach(System.out::println);
            }
        } catch (SQLException ex) {
            System.err.println("DATABASE_FAILED SQLState=" + ex.getSQLState() + "; rollback");
            System.exit(1);
        } catch (Exception ex) {
            System.err.println("VALIDATION_FAILED: " + ex.getMessage());
            System.exit(1);
        }
    }

    private static String required(String key) {
        String value = System.getenv(key);
        if (value == null) throw new IllegalArgumentException("환경변수 필요: " + key);
        return value;
    }

    private static Map<String, String> options(String[] args) {
        var options = new HashMap<String, String>();
        for (int i = 0; i < args.length; i++) {
            String key = args[i];
            if (!List.of("--site-id", "--dry-run", "--write").contains(key) || options.containsKey(key))
                throw new IllegalArgumentException("잘못된 argument: " + key);
            options.put(key, key.equals("--site-id") ? args[++i] : "true");
        }
        if (!options.containsKey("--site-id") || options.containsKey("--dry-run") == options.containsKey("--write")
                || Long.parseLong(options.get("--site-id")) <= 0)
            throw new IllegalArgumentException("--site-id <id> (--dry-run | --write)");
        return options;
    }
}
