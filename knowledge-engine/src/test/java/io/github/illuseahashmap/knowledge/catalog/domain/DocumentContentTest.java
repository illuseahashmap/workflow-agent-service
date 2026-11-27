package io.github.illuseahashmap.knowledge.catalog.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DocumentContentTest {
    @Test
    void canonicalizesTransportDifferencesWithoutDiscardingMeaningfulWhitespace() {
        var canonical = DocumentContent.from("one\ntwo\n");
        assertThat(DocumentContent.from("\uFEFFone\r\ntwo\r\n")).isEqualTo(canonical);
        assertThat(DocumentContent.from("one\rtwo\r")).isEqualTo(canonical);
        assertThat(DocumentContent.from(" one\ntwo\n").sha256()).isNotEqualTo(canonical.sha256());
        assertThat(DocumentContent.from("one\ntwo").sha256()).isNotEqualTo(canonical.sha256());
    }

    @Test
    void computesKnownSha256AndRejectsEmptyContent() {
        assertThat(DocumentContent.from("abc").sha256())
                .isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
        assertThatThrownBy(() -> DocumentContent.from("\uFEFF\r\n"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
