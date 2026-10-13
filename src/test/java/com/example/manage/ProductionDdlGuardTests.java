package com.example.manage;

import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProductionDdlGuardTests {
    private final ProductionDdlGuard guard = new ProductionDdlGuard();
    private final SpringApplication application = new SpringApplication(ManageApplication.class);

    @Test void developmentKeepsExistingUpdateSetting() {
        var environment = new MockEnvironment().withProperty("spring.jpa.hibernate.ddl-auto", "update");
        assertThatCode(() -> guard.postProcessEnvironment(environment, application)).doesNotThrowAnyException();
    }

    @Test void railwayWithoutProdProfileFailsBeforeJpa() {
        var environment = new MockEnvironment()
                .withProperty("RAILWAY_ENVIRONMENT_NAME", "production")
                .withProperty("spring.jpa.hibernate.ddl-auto", "update");
        assertThatThrownBy(() -> guard.postProcessEnvironment(environment, application))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test void remoteMysqlWithoutRailwayMarkerAlsoFails() {
        var environment = new MockEnvironment()
                .withProperty("spring.datasource.url", "jdbc:mysql://remote.example:3306/manage")
                .withProperty("spring.jpa.hibernate.ddl-auto", "update");
        assertThatThrownBy(() -> guard.postProcessEnvironment(environment, application))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test void prodWithUpdateOrMissingDdlSettingFails() {
        var environment = new MockEnvironment().withProperty("spring.jpa.hibernate.ddl-auto", "update");
        environment.setActiveProfiles("prod");
        assertThatThrownBy(() -> guard.postProcessEnvironment(environment, application))
                .isInstanceOf(IllegalStateException.class);
        var missing = new MockEnvironment();
        missing.setActiveProfiles("prod");
        assertThatThrownBy(() -> guard.postProcessEnvironment(missing, application))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test void prodWithValidatePasses() {
        var environment = new MockEnvironment()
                .withProperty("RAILWAY_ENVIRONMENT_NAME", "production")
                .withProperty("spring.jpa.hibernate.ddl-auto", "validate");
        environment.setActiveProfiles("prod");
        assertThatCode(() -> guard.postProcessEnvironment(environment, application)).doesNotThrowAnyException();
    }

    @Test void registeredGuardRejectsRailwayStartupWithTestProfile() {
        var startup = new SpringApplication(ManageApplication.class);
        startup.setWebApplicationType(WebApplicationType.NONE);
        assertThatThrownBy(() -> startup.run(
                "--spring.profiles.active=test",
                "--RAILWAY_ENVIRONMENT_NAME=production"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("requires the prod profile");
    }
}
