package com.example.manage;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;

import java.net.URI;

/** Fails before JPA starts if a Railway deployment could inherit the development DDL setting. */
public final class ProductionDdlGuard implements EnvironmentPostProcessor, Ordered {
    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE;
    }

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        boolean railway = environment.getProperty("RAILWAY_ENVIRONMENT_NAME") != null
                && !environment.getProperty("RAILWAY_ENVIRONMENT_NAME").isBlank();
        boolean remoteMysql = isRemoteMysql(environment.getProperty("spring.datasource.url"));
        boolean prod = java.util.Arrays.asList(environment.getActiveProfiles()).contains("prod");
        if ((railway || remoteMysql || prod) && (!prod
                || !"validate".equalsIgnoreCase(environment.getProperty("spring.jpa.hibernate.ddl-auto")))) {
            throw new IllegalStateException("Railway/remote MySQL/prod requires the prod profile and Hibernate ddl-auto=validate.");
        }
    }

    private boolean isRemoteMysql(String jdbcUrl) {
        if (jdbcUrl == null || !jdbcUrl.startsWith("jdbc:mysql:")) return false;
        if (!jdbcUrl.startsWith("jdbc:mysql://")) return true;
        try {
            String host = URI.create(jdbcUrl.substring("jdbc:".length())).getHost();
            return host == null || !(host.equalsIgnoreCase("localhost") || host.equals("127.0.0.1")
                    || host.equals("::1") || host.equals("0:0:0:0:0:0:0:1"));
        } catch (IllegalArgumentException exception) {
            return true;
        }
    }
}
