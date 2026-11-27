package io.github.illuseahashmap.workflow.shared.model;

import java.util.List;

public record PageSlice<T>(long total, int pageNumber, int pageSize, List<T> items) {

    public PageSlice {
        items = items == null ? List.of() : List.copyOf(items);
    }
}
