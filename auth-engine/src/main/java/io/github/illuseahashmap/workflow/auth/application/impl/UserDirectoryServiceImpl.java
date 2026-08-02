package io.github.illuseahashmap.workflow.auth.application.impl;

import io.github.illuseahashmap.workflow.auth.application.UserDirectoryService;
import io.github.illuseahashmap.workflow.auth.application.dto.DirectoryUserView;
import io.github.illuseahashmap.workflow.auth.domain.AuthMembershipRepository;
import io.github.illuseahashmap.workflow.shared.context.CurrentPrincipalProvider;
import io.github.illuseahashmap.workflow.shared.exception.BusinessException;
import io.github.illuseahashmap.workflow.shared.exception.ErrorCode;
import io.github.illuseahashmap.workflow.shared.model.PageSlice;
import io.github.illuseahashmap.workflow.shared.response.PageResult;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class UserDirectoryServiceImpl implements UserDirectoryService {

    private static final int DEFAULT_PAGE_SIZE = 20;
    private static final int MAX_PAGE_SIZE = 100;

    private final AuthMembershipRepository membershipRepository;
    private final CurrentPrincipalProvider principalProvider;

    public UserDirectoryServiceImpl(AuthMembershipRepository membershipRepository,
                                    CurrentPrincipalProvider principalProvider) {
        this.membershipRepository = membershipRepository;
        this.principalProvider = principalProvider;
    }

    @Override
    public PageResult<DirectoryUserView> search(String keyword, Integer pageNum, Integer pageSize) {
        int normalizedPageNum = pageNum == null || pageNum < 1 ? 1 : pageNum;
        int normalizedPageSize = pageSize == null || pageSize < 1
                ? DEFAULT_PAGE_SIZE : Math.min(pageSize, MAX_PAGE_SIZE);
        PageSlice<AuthMembershipRepository.TenantMember> page = membershipRepository.pageEnabledMembers(
                principalProvider.current().tenantCode(), normalize(keyword), normalizedPageNum, normalizedPageSize);
        return new PageResult<>(page.total(), page.pageNumber(), page.pageSize(), page.items().stream()
                .map(member -> new DirectoryUserView(
                        member.userId(), member.username(), member.displayName()))
                .toList());
    }

    @Override
    public Set<String> validateSelectableUsernames(Collection<String> usernames) {
        Set<String> normalized = normalizeUsernames(usernames);
        Set<String> found = membershipRepository.findEnabledUsernames(
                principalProvider.current().tenantCode(), normalized);
        if (found.size() != normalized.size()) {
            Set<String> unavailable = new LinkedHashSet<>(normalized);
            unavailable.removeAll(found);
            throw new BusinessException(ErrorCode.BAD_REQUEST,
                    "Users are unavailable in the current tenant: " + String.join(", ", unavailable));
        }
        return Set.copyOf(found);
    }

    private Set<String> normalizeUsernames(Collection<String> usernames) {
        if (usernames == null) {
            return Set.of();
        }
        Set<String> normalized = new LinkedHashSet<>();
        for (String username : usernames) {
            if (StringUtils.hasText(username)) {
                normalized.add(username.trim().toLowerCase(Locale.ROOT));
            }
        }
        return Set.copyOf(normalized);
    }

    private String normalize(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }
}
