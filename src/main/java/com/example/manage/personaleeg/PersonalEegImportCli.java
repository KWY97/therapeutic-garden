package com.example.manage.personaleeg;

import java.nio.file.Path;
import java.sql.*;
import java.util.*;

/** Offline dry-run or explicit localhost-only draft import. No activation command. */
public final class PersonalEegImportCli {
    public static void main(String[] args) {
        try {
            var opts = new HashMap<String, String>();
            for (int i = 0; i < args.length; i++) {
                String arg = args[i];
                if (!Set.of("--file", "--site-id", "--version", "--dry-run", "--write", "--confirm-source-sha").contains(arg)
                        || opts.containsKey(arg))
                    throw new IllegalArgumentException("argument 오류");
                boolean flag = arg.equals("--dry-run") || arg.equals("--write");
                if (!flag && ++i >= args.length)
                    throw new IllegalArgumentException("argument 값 누락: " + arg);
                opts.put(arg, flag ? "true" : args[i]);
            }
            if (!opts.containsKey("--file") || !opts.containsKey("--site-id") || !opts.containsKey("--version")
                    || opts.containsKey("--dry-run") == opts.containsKey("--write"))
                throw new IllegalArgumentException("--file --site-id --version 및 --dry-run/--write 필요");
            long siteId = Long.parseLong(opts.get("--site-id"));
            if (siteId <= 0)
                throw new IllegalArgumentException("Site ID 오류");
            var source = new PersonalEegExcelParser().parse(Path.of(opts.get("--file")));
            if (opts.containsKey("--dry-run")) {
                if (opts.containsKey("--confirm-source-sha"))
                    throw new IllegalArgumentException("dry-run에 write 확인값 사용 불가");
                System.out.println("DRY_RUN sha256=" + source.sha256() + " version=" + opts.get("--version")
                        + " sessions=" + source.sessions().size() + " spots=" + source.resultCount()
                        + " complete=" + source.completeCount() + " postMissing=" + source.postMissingCount()
                        + " correctedDates=" + source.correctedDateCount());
                return;
            }
            if (!source.sha256().equals(opts.get("--confirm-source-sha")))
                throw new IllegalArgumentException("write의 확인 SHA 불일치");
            String url = System.getenv("PERSONAL_EEG_JDBC_URL");
            if (url == null || !url.matches("jdbc:mysql://(localhost|127\\.0\\.0\\.1)(:[0-9]{1,5})?/[A-Za-z0-9_]+"))
                throw new IllegalArgumentException("로컬 MySQL URL만 허용");
            try (var connection = DriverManager.getConnection(url, System.getenv("PERSONAL_EEG_DB_USER"),
                    System.getenv("PERSONAL_EEG_DB_PASSWORD"))) {
                System.out.println(new PersonalEegDatabaseImporter().execute(connection, source, siteId, opts.get("--version"), false));
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
