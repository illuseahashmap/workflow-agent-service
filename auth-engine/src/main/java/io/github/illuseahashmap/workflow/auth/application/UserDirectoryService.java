package io.github.illuseahashmap.workflow.auth.application;

import io.github.illuseahashmap.workflow.auth.application.dto.DirectoryUserView;
import io.github.illuseahashmap.workflow.shared.response.PageResult;
import java.util.Collection;
import java.util.Set;

public interface UserDirectoryService {

    PageResult<DirectoryUserView> search(String keyword, Integer pageNum, Integer pageSize);

    Set<String> validateSelectableUsernames(Collection<String> usernames);

    void requireTransferableUsernames(Collection<String> usernames);
}
