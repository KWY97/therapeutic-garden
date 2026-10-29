package com.example.manage.healingeffect;

import java.io.PrintStream;
import java.nio.file.Path;
import java.sql.*;
import java.util.*;

/** No SpringApplication, web server, profile loading, DataInitializer or schema generation. */
public final class HealingEffectImportCli {
    public static void main(String[] args) {
        System.exit(run(args, System.getenv(), System.out, System.err));
    }

    static int run(String[] args, Map<String, String> environment, PrintStream out, PrintStream err) {
        try {
            var options = options(args);
            var source = new HealingEffectExcelParser().parse(Path.of(options.get("--file")));
            out.println("SHA-256=" + source.sha256());
            out.println("parsed overall=" + source.overall().size() + ", participant=" + source.participants().size());
            source.overall().stream().sorted(Comparator.comparing(HealingEffectSource.Overall::spot))
                    .forEach(row -> out.println(row.spot() + " stress="
                            + com.example.manage.dto.HealingEffectView.display(row.stressRate()) + " emotional="
                            + com.example.manage.dto.HealingEffectView.display(row.emotionalRate())));
            String url = required(environment, "HEALING_IMPORT_JDBC_URL");
            // This first version intentionally cannot target Railway or another remote DB.
            if (!url.matches("jdbc:mysql://(localhost|127\\.0\\.0\\.1)(:[0-9]{1,5})?/[A-Za-z0-9_]+"))
                throw new IllegalArgumentException("로컬 MySQL URL만 허용합니다. URL 옵션/원격 호스트는 허용하지 않습니다.");
            try (Connection connection = DriverManager.getConnection(url,
                    required(environment, "HEALING_IMPORT_DB_USER"), required(environment, "HEALING_IMPORT_DB_PASSWORD"))) {
                var result = new HealingEffectDatabaseImporter().execute(connection, source,
                        Long.parseLong(options.get("--site-id")), options.containsKey("--dry-run"));
                out.println("status=" + result.status() + ", validationErrors=0, schemaReady=" + result.schemaReady());
                out.println("planned/inserted overall=" + result.overallRows() + ", participant=" + result.participantRows());
                result.warnings().forEach(out::println);
                return 0;
            }
        } catch (EffectImportValidationException ex) {
            err.println("VALIDATION_FAILED validationErrors=" + ex.getErrors().size());
            ex.getErrors().forEach(err::println);
        } catch (SQLException ex) {
            // Do not print connection URLs, credentials or driver exception messages.
            err.println("DATABASE_FAILED SQLState=" + ex.getSQLState() + " code=" + ex.getErrorCode()
                    + "; transaction rolled back if started.");
        } catch (Exception ex) {
            err.println("FAILED: " + ex.getMessage());
        }
        return 1;
    }

    private static String required(Map<String, String> environment, String key) {
        String value = environment.get(key);
        if (value == null)
            throw new IllegalArgumentException("환경변수 필요: " + key);
        return value;
    }

    private static Map<String, String> options(String[] args) {
        var options = new HashMap<String, String>();
        for (int i = 0; i < args.length; i++) {
            String key = args[i];
            if (!List.of("--file", "--site-id", "--dry-run", "--write").contains(key) || options.containsKey(key))
                throw new IllegalArgumentException("알 수 없거나 중복된 argument: " + key);
            String value = "true";
            if (key.equals("--file") || key.equals("--site-id")) {
                if (++i >= args.length)
                    throw new IllegalArgumentException("argument 값 필요: " + key);
                value = args[i];
            }
            options.put(key, value);
        }
        if (!options.containsKey("--file") || !options.containsKey("--site-id")
                || options.containsKey("--dry-run") == options.containsKey("--write")
                || Long.parseLong(options.get("--site-id")) <= 0)
            throw new IllegalArgumentException("사용법: --file <external.xlsx> --site-id <id> (--dry-run | --write)");
        return options;
    }
}
