package io.github.illuseahashmap.workflow.config;

import java.util.List;
import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.MigrationState;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class FlywayMigrationConfigTest {

    @Test
    void usesPlatformMigrationsByDefault() {
        assertThat(FlywayMigrationConfig.migrationLocations(new MockEnvironment()))
                .containsExactly("classpath:db/migration/platform");
    }

    @Test
    void includesOptionalVectorLocationWhenConfiguredAsYamlList() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("spring.flyway.locations[0]", "classpath:db/migration/platform")
                .withProperty("spring.flyway.locations[1]", "classpath:db/migration/vector");

        assertThat(FlywayMigrationConfig.migrationLocations(environment))
                .isEqualTo(List.of("classpath:db/migration/platform", "classpath:db/migration/vector"));
    }

    @Test
    void permitsOnlyTheKnownOptionalRepeatableWhenVectorLocationIsDisabled() {
        MigrationInfo vector = mock(MigrationInfo.class);
        when(vector.getState()).thenReturn(MigrationState.MISSING_SUCCESS);
        when(vector.getDescription()).thenReturn("knowledge chunk embeddings");
        MigrationInfo unknown = mock(MigrationInfo.class);
        when(unknown.getState()).thenReturn(MigrationState.MISSING_SUCCESS);
        when(unknown.getDescription()).thenReturn("unexpected migration");

        assertThatCode(() -> FlywayMigrationConfig.rejectUnknownMissingMigrations(
                new MigrationInfo[]{vector})).doesNotThrowAnyException();
        assertThatThrownBy(() -> FlywayMigrationConfig.rejectUnknownMissingMigrations(
                new MigrationInfo[]{vector, unknown}))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("unexpected migration");
    }
}
