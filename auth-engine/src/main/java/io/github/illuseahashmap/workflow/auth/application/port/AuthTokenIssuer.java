package io.github.illuseahashmap.workflow.auth.application.port;

import io.github.illuseahashmap.workflow.auth.application.dto.AuthTokenResponse;
import io.github.illuseahashmap.workflow.auth.domain.AuthUser;
import java.util.Set;

public interface AuthTokenIssuer {

    AuthTokenResponse issue(AuthUser user, String tenantCode, Set<String> roles, Set<String> permissions);
}
