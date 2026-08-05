package io.github.illuseahashmap.workflow.auth.interfaces.rest;

import io.github.illuseahashmap.workflow.auth.application.AuthService;
import io.github.illuseahashmap.workflow.auth.application.dto.AuthTokenResponse;
import io.github.illuseahashmap.workflow.auth.application.dto.CurrentUserResponse;
import io.github.illuseahashmap.workflow.auth.application.dto.LoginRequest;
import io.github.illuseahashmap.workflow.auth.application.dto.RegisterRequest;
import io.github.illuseahashmap.workflow.auth.application.dto.SwitchTenantRequest;
import io.github.illuseahashmap.workflow.auth.application.dto.TenantOptionResponse;
import io.github.illuseahashmap.workflow.auth.infrastructure.token.AuthTokenProperties;
import io.github.illuseahashmap.workflow.shared.response.ApiResponse;
import jakarta.validation.Valid;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.List;
import org.springframework.http.ResponseCookie;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/auth")
public class AuthController {

    private final AuthService authService;
    private final AuthTokenProperties tokenProperties;

    public AuthController(AuthService authService,
                          AuthTokenProperties tokenProperties) {
        this.authService = authService;
        this.tokenProperties = tokenProperties;
    }

    @PostMapping("/register")
    public ApiResponse<AuthTokenResponse> register(@Valid @RequestBody RegisterRequest request,
                                                   HttpServletRequest servletRequest,
                                                   HttpServletResponse httpResponse) {
        var tokenResponse = authService.register(request, servletRequest.getRemoteAddr());
        writeTokenCookie(httpResponse, tokenResponse.accessToken(), tokenResponse.expiresIn());
        return ApiResponse.ok(tokenResponse);
    }

    @PostMapping("/login")
    public ApiResponse<AuthTokenResponse> login(@Valid @RequestBody LoginRequest request,
                                                HttpServletRequest servletRequest,
                                                HttpServletResponse httpResponse) {
        var tokenResponse = authService.login(request, servletRequest.getRemoteAddr());
        writeTokenCookie(httpResponse, tokenResponse.accessToken(), tokenResponse.expiresIn());
        return ApiResponse.ok(tokenResponse);
    }

    @GetMapping("/me")
    public ApiResponse<CurrentUserResponse> currentUser() {
        return ApiResponse.ok(authService.currentUser());
    }

    @GetMapping("/tenants")
    public ApiResponse<List<TenantOptionResponse>> tenants() {
        return ApiResponse.ok(authService.tenants());
    }

    @PostMapping("/switch-tenant")
    public ApiResponse<AuthTokenResponse> switchTenant(@Valid @RequestBody SwitchTenantRequest request,
                                                       HttpServletResponse httpResponse) {
        var response = authService.switchTenant(request);
        writeTokenCookie(httpResponse, response.accessToken(), response.expiresIn());
        return ApiResponse.ok(response);
    }

    @PostMapping("/logout")
    public ApiResponse<Void> logout(HttpServletResponse response) {
        response.addHeader("Set-Cookie", ResponseCookie.from(tokenProperties.getCookieName(), "")
                .httpOnly(true)
                .secure(tokenProperties.isCookieSecure())
                .sameSite(tokenProperties.getCookieSameSite())
                .path("/")
                .maxAge(0)
                .build()
                .toString());
        return ApiResponse.ok(null);
    }

    private void writeTokenCookie(HttpServletResponse response,
                                  String token, long expiresIn) {
        response.addHeader("Set-Cookie", ResponseCookie.from(tokenProperties.getCookieName(), token)
                .httpOnly(true)
                .secure(tokenProperties.isCookieSecure())
                .sameSite(tokenProperties.getCookieSameSite())
                .path("/")
                .maxAge(expiresIn)
                .build()
                .toString());
    }
}
