package io.github.illuseahashmap.workflow.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

class PlatformMigrationNamingTest {

    private static final Pattern VERSIONED_MIGRATION =
            Pattern.compile("^V([0-9]+(?:\\.[0-9]+)*)__.+\\.sql$");

    @Test
    void platformMigrationVersionsAreUnique() throws IOException {
        List<String> migrationNames = Arrays.stream(new PathMatchingResourcePatternResolver()
                        .getResources("classpath*:db/migration/platform/V*__*.sql"))
                .map(resource -> Objects.requireNonNull(resource.getFilename()))
                .distinct()
                .sorted()
                .toList();

        assertThat(migrationNames).isNotEmpty();
        assertThat(migrationNames).allMatch(name -> VERSIONED_MIGRATION.matcher(name).matches());

        Map<String, List<String>> migrationsByVersion = migrationNames.stream()
                .collect(Collectors.groupingBy(this::versionOf));
        Map<String, List<String>> duplicates = migrationsByVersion.entrySet().stream()
                .filter(entry -> entry.getValue().size() > 1)
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));

        assertThat(duplicates)
                .as("Flyway platform migration versions must be unique: %s", duplicates)
                .isEmpty();
    }

    private String versionOf(String migrationName) {
        Matcher matcher = VERSIONED_MIGRATION.matcher(migrationName);
        if (!matcher.matches()) {
            throw new IllegalArgumentException("Invalid Flyway migration name: " + migrationName);
        }
        return matcher.group(1);
    }
}
