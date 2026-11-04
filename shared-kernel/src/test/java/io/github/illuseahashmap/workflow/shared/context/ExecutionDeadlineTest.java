package io.github.illuseahashmap.workflow.shared.context;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ExecutionDeadlineTest {
    @Test
    void deadlineScopePreservesTenantPrincipalAndNestedTrustedBoundaries() {
        var tenant = new TenantContext.TenantInfo("id", "tenant", "Tenant");
        var principal = new CurrentPrincipal("USER", "u", "user", "User", "tenant", java.util.Set.of(), java.util.Set.of());
        TenantContext.set(tenant);
        CurrentPrincipalContext.set(principal);
        try {
            TrustedDataAccessContext.runAsSystemWorker(() -> TrustedDataAccessContext.runAsSystemWorker(() -> {
                try (var budget = ExecutionDeadline.open(Duration.ofSeconds(1), System::nanoTime)) {
                    assertThat(TenantContext.current()).isSameAs(tenant);
                    assertThat(CurrentPrincipalContext.current()).isSameAs(principal);
                    assertThat(CurrentPrincipalContext.currentOrNull()).isSameAs(principal);
                    assertThat(TrustedDataAccessContext.isSystemWorker()).isTrue();
                    TrustedDataAccessContext.runAsAuthentication(() -> TrustedDataAccessContext.runAsAuthentication(() ->
                            assertThat(TrustedDataAccessContext.isAuthentication()).isTrue()));
                    assertThat(TrustedDataAccessContext.isAuthentication()).isFalse();
                }
            }));
            assertThat(TrustedDataAccessContext.isSystemWorker()).isFalse();
        } finally {
            TenantContext.clear();
            CurrentPrincipalContext.clear();
        }
        assertThat(CurrentPrincipalContext.currentOrNull()).isNull();
        assertThatThrownBy(CurrentPrincipalContext::current)
                .isInstanceOf(io.github.illuseahashmap.workflow.shared.exception.BusinessException.class);
        assertThatThrownBy(TenantContext::current).isInstanceOf(IllegalStateException.class);
        assertThat(ExecutionDeadline.remainingOrNull()).isNull();
    }

    @Test
    void nestedScopesCannotExtendTheParentBudgetAndRestoreContextOnFailure() {
        var clock = new AtomicLong();
        try (var outer = ExecutionDeadline.open(Duration.ofSeconds(1), clock::get)) {
            clock.set(Duration.ofMillis(100).toNanos());
            try (var inner = ExecutionDeadline.open(Duration.ofSeconds(5), clock::get)) {
                assertThat(inner.remaining()).isEqualTo(Duration.ofMillis(900));
                clock.set(Duration.ofSeconds(1).toNanos());
                assertThatThrownBy(inner::remaining).isInstanceOf(ExecutionDeadline.Exceeded.class);
            }
            assertThat(ExecutionDeadline.expired()).isTrue();
            assertThatThrownBy(outer::remaining).isInstanceOf(ExecutionDeadline.Exceeded.class);
        }
        assertThat(ExecutionDeadline.remainingOrNull()).isNull();
        assertThatThrownBy(() -> ExecutionDeadline.open(Duration.ZERO, clock::get)).isInstanceOf(ExecutionDeadline.Exceeded.class);
        assertThat(ExecutionDeadline.remainingOrNull()).isNull();
    }
}
