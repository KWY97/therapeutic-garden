package com.example.manage.healingeffect;

import java.nio.file.Path;
import java.sql.*;
import java.util.*;

/** Opt-in CLI, local MySQL only. No application context or schema mutation. */
public final class RawMeasurementImportCli {
    public static void main(String[] args) {
        try {
            Map<String, String> opts = new HashMap<>();
            for (int i = 0; i < args.length; i++) {
                String key = args[i];
                if (!List.of("--file", "--site-id", "--dry-run", "--write").contains(key) || opts.containsKey(key))
                    throw new IllegalArgumentException("잘못된 argument");
                opts.put(key, key.equals("--file") || key.equals("--site-id") ? args[++i] : "true");
            }
            if (!opts.containsKey("--file") || !opts.containsKey("--site-id") || opts.containsKey("--dry-run") == opts.containsKey("--write"))
                throw new IllegalArgumentException("--file <xlsx> --site-id <id> (--dry-run | --write)");
            long siteId = Long.parseLong(opts.get("--site-id"));
            if (siteId <= 0)
                throw new IllegalArgumentException("Site ID 오류");
            var source = new RawMeasurementParser().parse(Path.of(opts.get("--file")));
            String url = System.getenv("HEALING_IMPORT_JDBC_URL");
            if (url == null || !url.matches("jdbc:mysql://(localhost|127\\.0\\.0\\.1)(:[0-9]{1,5})?/[A-Za-z0-9_]+"))
                throw new IllegalArgumentException("로컬 MySQL URL만 허용");
            try (var c = DriverManager.getConnection(url, System.getenv("HEALING_IMPORT_DB_USER"), System.getenv("HEALING_IMPORT_DB_PASSWORD"))) {
                System.out.println("SHA-256=" + source.sha256());
                System.out.println(new RawMeasurementDatabaseImporter().execute(c, source, siteId, opts.containsKey("--dry-run")));
            }
        } catch (SQLException ex) {
            System.err.println("DATABASE_FAILED SQLState=" + ex.getSQLState() + "; rollback");
            System.exit(1);
        } catch (Exception ex) {
            System.err.println("VALIDATION_FAILED: " + ex.getMessage());
            System.exit(1);
        }
    }
}
