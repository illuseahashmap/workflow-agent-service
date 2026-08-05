package io.github.illuseahashmap.workflow.shared.context;

import io.github.illuseahashmap.workflow.shared.exception.BusinessException;
import io.github.illuseahashmap.workflow.shared.exception.ErrorCode;

public final class CurrentPrincipalContext {

    private static final ThreadLocal<CurrentPrincipal> PRINCIPAL = new ThreadLocal<>();

    private CurrentPrincipalContext() {
    }

    public static void set(CurrentPrincipal principal) {
        PRINCIPAL.set(principal);
    }

    public static CurrentPrincipal current() {
        CurrentPrincipal principal = PRINCIPAL.get();
        if (principal == null) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED, "Missing current principal");
        }
        return principal;
    }

    public static CurrentPrincipal currentOrNull() {
        return PRINCIPAL.get();
    }

    public static void clear() {
        PRINCIPAL.remove();
    }
}
