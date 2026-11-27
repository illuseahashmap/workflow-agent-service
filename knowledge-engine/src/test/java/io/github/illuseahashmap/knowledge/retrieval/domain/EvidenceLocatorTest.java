package io.github.illuseahashmap.knowledge.retrieval.domain;

import java.util.List;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EvidenceLocatorTest {
    @Test
    void supportsCellsAcrossPagesButRejectsOtherArtifactsOrMissingRows() {
        var first = new EvidenceLocator.Region(1, 0, 0, 1, 1);
        var second = new EvidenceLocator.Region(2, 0, 0, 1, 1);
        var table = table("a".repeat(64), List.of(first, second), List.of("r1", "r2"));
        assertThat(table.covers(table("a".repeat(64), List.of(second), List.of("r2")))).isTrue();
        assertThat(table.covers(table("b".repeat(64), List.of(second), List.of("r2")))).isFalse();
        assertThat(table.covers(table("a".repeat(64), List.of(second), List.of("r3")))).isFalse();
    }

    @Test
    void rejectsInvalidCoordinatesVersionsAndMixedLocationKinds() {
        assertThatThrownBy(() -> new EvidenceLocator.Region(0, 0.8, 0, 0.2, 1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new EvidenceLocator.Region(-1, 0, 0, 1, 1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new EvidenceLocator(2, EvidenceLocator.Kind.TEXT_SECTION, "3.1", null, null,
                List.of(), List.of(), List.of(), null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> table("a".repeat(64), List.of(new EvidenceLocator.Region(0, 0, 0, 1, 1)), List.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private EvidenceLocator table(String hash, List<EvidenceLocator.Region> regions, List<String> rows) {
        return new EvidenceLocator(1, EvidenceLocator.Kind.TABLE_CELLS, null, hash, "table-1", regions, rows, List.of("c1"), null);
    }
}
