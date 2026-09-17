package io.github.illuseahashmap.workflow.auth.application.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.github.illuseahashmap.workflow.auth.domain.AuthMembershipRepository;
import io.github.illuseahashmap.workflow.shared.context.CurrentPrincipal;
import io.github.illuseahashmap.workflow.shared.context.CurrentPrincipalProvider;
import io.github.illuseahashmap.workflow.shared.exception.BusinessException;
import io.github.illuseahashmap.workflow.shared.model.PageSlice;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class UserDirectoryServiceImplTest {

    private final AuthMembershipRepository membershipRepository = mock(AuthMembershipRepository.class);
    private final CurrentPrincipalProvider principalProvider = () -> new CurrentPrincipal(
            "USER", "user-1", "operator", "Operator", "tenant-a",
            Set.of("TENANT_ADMIN"), Set.of("workflow:instance:operate"));
    private final UserDirectoryServiceImpl service = new UserDirectoryServiceImpl(
            membershipRepository, principalProvider);

    @Test
    void searchesEnabledTenantMembersWithBoundedPagination() {
        AuthMembershipRepository.TenantMember member = new AuthMembershipRepository.TenantMember(
                "user-2", "alice", "Alice", true, true, Set.of("USER"), Set.of(), null);
        when(membershipRepository.pageEnabledMembers("tenant-a", "ali", 1, 20))
                .thenReturn(new PageSlice<>(1, 1, 20, List.of(member)));

        var result = service.search(" ali ", 1, 20);

        assertThat(result.total()).isEqualTo(1);
        assertThat(result.records()).singleElement()
                .satisfies(user -> assertThat(user.username()).isEqualTo("alice"));
    }

    @Test
    void rejectsUsersOutsideTheCurrentTenant() {
        when(membershipRepository.findEnabledUsernames(
                "tenant-a", Set.of("alice", "outside-user")))
                .thenReturn(Set.of("alice"));

        assertThatThrownBy(() -> service.validateSelectableUsernames(
                List.of("Alice", "outside-user")))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("outside-user");
    }

    @Test
    void distinguishesUnknownTransferUser() {
        when(membershipRepository.findUserAvailability("tenant-a", Set.of("missing")))
                .thenReturn(List.of());

        assertThatThrownBy(() -> service.requireUsableUsernames(List.of("missing")))
                .isInstanceOf(BusinessException.class)
                .hasMessage("Users do not exist: missing");
    }

    @Test
    void distinguishesUserOutsideCurrentTenant() {
        when(membershipRepository.findUserAvailability("tenant-a", Set.of("outsider")))
                .thenReturn(List.of(new AuthMembershipRepository.UserAvailability(
                        "outsider", true, false, false)));

        assertThatThrownBy(() -> service.requireUsableUsernames(List.of("outsider")))
                .isInstanceOf(BusinessException.class)
                .hasMessage("Users do not belong to the current tenant: outsider");
    }

    @Test
    void distinguishesDisabledTenantMember() {
        when(membershipRepository.findUserAvailability("tenant-a", Set.of("disabled")))
                .thenReturn(List.of(new AuthMembershipRepository.UserAvailability(
                        "disabled", true, true, false)));

        assertThatThrownBy(() -> service.requireUsableUsernames(List.of("disabled")))
                .isInstanceOf(BusinessException.class)
                .hasMessage("Users are disabled in the current tenant: disabled");
    }
}
