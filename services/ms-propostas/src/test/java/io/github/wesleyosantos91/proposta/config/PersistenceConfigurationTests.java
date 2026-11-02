package io.github.wesleyosantos91.proposta.config;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.env.MockEnvironment;
import static org.mockito.Mockito.*;

class PersistenceConfigurationTests {
    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void migrationGuardHonorsTheRuntimeSetting(boolean initialize) {
        var flyway = mock(Flyway.class);
        var environment = new MockEnvironment()
                .withProperty("insurance.persistence.initialize", Boolean.toString(initialize));
        new PersistenceConfiguration().migrationStrategy(environment).migrate(flyway);
        if (initialize) {
            verify(flyway).migrate();
        } else {
            verifyNoInteractions(flyway);
        }
    }

    @Test
    void migrationsRunByDefault() {
        var flyway = mock(Flyway.class);
        new PersistenceConfiguration().migrationStrategy(new MockEnvironment()).migrate(flyway);
        verify(flyway).migrate();
    }
}
