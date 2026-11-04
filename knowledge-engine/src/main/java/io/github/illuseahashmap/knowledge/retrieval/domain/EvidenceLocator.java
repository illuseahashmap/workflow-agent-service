package io.github.illuseahashmap.knowledge.retrieval.domain;

import java.util.List;
import java.util.Objects;

/** Parser-neutral source location. Identity is bound to immutable artifacts, never temporary file IDs. */
public record EvidenceLocator(int schemaVersion, Kind kind, String sectionId, String artifactHash,
                              String elementId, List<Region> pageRegions, List<String> rowIds,
                              List<String> columnIds, String assetHash) {
    public enum Kind { TEXT_SECTION, PDF_REGION, TABLE_CELLS, FIGURE_REGION }

    public EvidenceLocator {
        if (schemaVersion != 1) { throw new IllegalArgumentException("Unsupported evidence locator version"); }
        Objects.requireNonNull(kind, "kind");
        pageRegions = List.copyOf(Objects.requireNonNullElse(pageRegions, List.of()));
        rowIds = List.copyOf(Objects.requireNonNullElse(rowIds, List.of()));
        columnIds = List.copyOf(Objects.requireNonNullElse(columnIds, List.of()));
        if (kind == Kind.TEXT_SECTION) {
            if (sectionId == null || sectionId.isBlank() || artifactHash != null || elementId != null || !pageRegions.isEmpty()) {
                throw new IllegalArgumentException("Text locator requires only a section");
            }
        } else if (sectionId != null || !hash(artifactHash) || elementId == null || elementId.isBlank()
                || pageRegions.isEmpty() || pageRegions.size() > 100
                || pageRegions.stream().distinct().count() != pageRegions.size()) {
            throw new IllegalArgumentException("Structured locator requires immutable artifact and valid regions");
        }
        if (kind == Kind.TABLE_CELLS) {
            if (!ids(rowIds) || !ids(columnIds)) { throw new IllegalArgumentException("Table cells require unique rows and columns"); }
        } else if (!rowIds.isEmpty() || !columnIds.isEmpty()) {
            throw new IllegalArgumentException("Only table locators have cell selections");
        }
        if (kind == Kind.FIGURE_REGION ? !hash(assetHash) : assetHash != null) {
            throw new IllegalArgumentException("Figure locator requires an immutable asset hash");
        }
    }

    /** Retrieval coverage permits an enclosing region; precise citation additionally requires matching selections. */
    public boolean covers(EvidenceLocator expected) {
        if (kind != expected.kind || !Objects.equals(sectionId, expected.sectionId)
                || !Objects.equals(artifactHash, expected.artifactHash) || !Objects.equals(elementId, expected.elementId)
                || !Objects.equals(assetHash, expected.assetHash)) { return false; }
        if (kind == Kind.TEXT_SECTION) { return true; }
        return expected.pageRegions.stream().allMatch(required -> pageRegions.stream().anyMatch(actual -> actual.covers(required)))
                && rowIds.containsAll(expected.rowIds) && columnIds.containsAll(expected.columnIds);
    }

    public boolean matchesCitation(EvidenceLocator expected) {
        return covers(expected) && expected.covers(this)
                && new java.util.HashSet<>(pageRegions).equals(new java.util.HashSet<>(expected.pageRegions));
    }

    private static boolean hash(String value) { return value != null && value.matches("[a-f0-9]{64}"); }
    private static boolean ids(List<String> values) {
        return !values.isEmpty() && values.size() <= 1000 && values.stream().allMatch(v -> !v.isBlank())
                && values.stream().distinct().count() == values.size();
    }

    public record Region(int pageIndex, double x0, double y0, double x1, double y1) {
        public Region {
            if (pageIndex < 0 || !Double.isFinite(x0 + y0 + x1 + y1)
                    || x0 < 0 || y0 < 0 || x1 > 1 || y1 > 1 || x0 >= x1 || y0 >= y1) {
                throw new IllegalArgumentException("Invalid normalized page region");
            }
        }
        boolean covers(Region other) {
            return pageIndex == other.pageIndex && x0 <= other.x0 && y0 <= other.y0 && x1 >= other.x1 && y1 >= other.y1;
        }
    }
}
