package io.github.illuseahashmap.workflow.shared.response;

import java.util.List;

public record PageResult<T>(long total, int pageNum, int pageSize, List<T> records) {
}
