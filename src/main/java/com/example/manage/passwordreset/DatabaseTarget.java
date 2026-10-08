package com.example.manage.passwordreset;

import java.net.URI;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Locale;
import java.util.Map;

enum DatabaseTarget {
    LOCAL("PASSWORD_RESET_LOCAL"),
    PRODUCTION("PASSWORD_RESET_PRODUCTION");

    private final String environmentPrefix;

    DatabaseTarget(String environmentPrefix) {
        this.environmentPrefix = environmentPrefix;
    }

    DatabaseConfig config(Map<String, String> environment, boolean allowProductionTunnel) {
        String jdbcUrl = required(environment, "_JDBC_URL");
        String user = required(environment, "_DB_USER");
        String expectedDatabase = required(environment, "_EXPECTED_DATABASE");
        validateJdbcUrl(jdbcUrl, allowProductionTunnel);
        if (!expectedDatabase.matches("[A-Za-z0-9_]+")) {
            throw new IllegalArgumentException("EXPECTED_DATABASE 형식이 올바르지 않습니다.");
        }
        if (user.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("DB_USER 형식이 올바르지 않습니다.");
        }
        return new DatabaseConfig(this, jdbcUrl, user, expectedDatabase);
    }

    void validateActualDatabase(Connection connection, DatabaseConfig config) throws SQLException {
        try (var statement = connection.prepareStatement("select database()");
             ResultSet rows = statement.executeQuery()) {
            if (!rows.next() || !config.expectedDatabase().equalsIgnoreCase(rows.getString(1)) || rows.next()) {
                throw new IllegalArgumentException("연결된 database가 EXPECTED_DATABASE와 일치하지 않습니다.");
            }
        }
    }

    private String required(Map<String, String> environment, String suffix) {
        String key = environmentPrefix + suffix;
        String value = environment.get(key);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("필수 환경변수가 없습니다: " + key);
        }
        return value.strip();
    }

    private void validateJdbcUrl(String jdbcUrl, boolean allowProductionTunnel) {
        String lower = jdbcUrl.toLowerCase(Locale.ROOT);
        if (!lower.startsWith("jdbc:mysql://") || jdbcUrl.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("MySQL JDBC URL 형식이 올바르지 않습니다.");
        }
        if (lower.matches(".*[?&](user|password)=[^&]*.*") || lower.substring("jdbc:mysql://".length()).contains("@")) {
            throw new IllegalArgumentException("JDBC URL에 사용자명이나 비밀번호를 포함할 수 없습니다.");
        }
        try {
            URI uri = URI.create(jdbcUrl.substring("jdbc:".length()));
            String host = uri.getHost();
            if (host == null || host.isBlank()) throw new IllegalArgumentException();
            boolean loopback = host.equalsIgnoreCase("localhost") || host.equals("127.0.0.1")
                    || host.equals("::1") || host.equals("0:0:0:0:0:0:0:1");
            if (this == LOCAL && !loopback) {
                throw new IllegalArgumentException("LOCAL 대상은 loopback DB 주소만 허용합니다.");
            }
            if (this == PRODUCTION && loopback && !allowProductionTunnel) {
                throw new IllegalArgumentException("PRODUCTION loopback DB 주소는 "
                        + "--allow-production-tunnel로 Railway SSH 터널 사용을 명시적으로 승인해야 합니다.");
            }
            if (this == PRODUCTION && !loopback && allowProductionTunnel) {
                throw new IllegalArgumentException("--allow-production-tunnel은 PRODUCTION loopback DB 주소에서만 "
                        + "사용할 수 있습니다.");
            }
        } catch (IllegalArgumentException ex) {
            if (ex.getMessage() != null) throw ex;
            throw new IllegalArgumentException("JDBC URL의 host를 확인할 수 없습니다.");
        }
    }
}

record DatabaseConfig(DatabaseTarget target, String jdbcUrl, String user, String expectedDatabase) {
    @Override
    public String toString() {
        return "DatabaseConfig[target=" + target + ", connection=[REDACTED]]";
    }
}
