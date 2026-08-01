package io.github.illuseahashmap.workflow.auth.application;

import io.github.illuseahashmap.workflow.auth.application.dto.AuthTokenResponse;
import io.github.illuseahashmap.workflow.auth.application.dto.CurrentUserResponse;
import io.github.illuseahashmap.workflow.auth.application.dto.LoginRequest;
import io.github.illuseahashmap.workflow.auth.application.dto.RegisterRequest;
import io.github.illuseahashmap.workflow.auth.application.dto.SwitchTenantRequest;
import io.github.illuseahashmap.workflow.auth.application.dto.TenantOptionResponse;
import java.util.List;

public interface AuthService {

    AuthTokenResponse register(RegisterRequest request);

    AuthTokenResponse login(LoginRequest request);

    CurrentUserResponse currentUser();

    List<TenantOptionResponse> tenants();

    AuthTokenResponse switchTenant(SwitchTenantRequest request);
}
