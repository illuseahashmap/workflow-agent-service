package io.github.illuseahashmap.workflow.auth.interfaces.rest;

import io.github.illuseahashmap.workflow.auth.application.AuthService;
import io.github.illuseahashmap.workflow.auth.application.dto.BrowserAuthResponse;
import io.github.illuseahashmap.workflow.auth.application.dto.ChangePasswordRequest;
import io.github.illuseahashmap.workflow.auth.application.dto.CurrentUserResponse;
import io.github.illuseahashmap.workflow.auth.application.dto.LoginRequest;
import io.github.illuseahashmap.workflow.auth.application.dto.RegisterRequest;
import io.github.illuseahashmap.workflow.auth.application.dto.SwitchTenantRequest;
import io.github.illuseahashmap.workflow.auth.application.dto.TenantOptionResponse;
import io.github.illuseahashmap.workflow.auth.application.dto.UpdateProfileRequest;
import io.github.illuseahashmap.workflow.auth.infrastructure.token.AuthTokenProperties;
import io.github.illuseahashmap.workflow.shared.response.ApiResponse;
import jakarta.validation.Valid;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.List;
import org.springframework.http.ResponseCookie;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PatchMapping;
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
    public ApiResponse<BrowserAuthResponse> register(@Valid @RequestBody RegisterRequest request,
                                                   HttpServletRequest servletRequest,
                                                   HttpServletResponse httpResponse) {
        var tokenResponse = authService.register(request, servletRequest.getRemoteAddr());
        writeTokenCookie(httpResponse, tokenResponse.accessToken(), tokenResponse.expiresIn());
        return ApiResponse.ok(BrowserAuthResponse.from(tokenResponse));
    }

    @PostMapping("/login")
    public ApiResponse<BrowserAuthResponse> login(@Valid @RequestBody LoginRequest request,
                                                HttpServletRequest servletRequest,
                                                HttpServletResponse httpResponse) {
        var tokenResponse = authService.login(request, servletRequest.getRemoteAddr());
        writeTokenCookie(httpResponse, tokenResponse.accessToken(), tokenResponse.expiresIn());
        return ApiResponse.ok(BrowserAuthResponse.from(tokenResponse));
    }

    @GetMapping("/me")
    public ApiResponse<CurrentUserResponse> currentUser() {
        return ApiResponse.ok(authService.currentUser());
    }

    @PatchMapping("/me")
    public ApiResponse<CurrentUserResponse> updateProfile(@Valid @RequestBody UpdateProfileRequest request) {
        return ApiResponse.ok(authService.updateProfile(request));
    }

    @PostMapping("/me/password")
    public ApiResponse<Void> changePassword(@Valid @RequestBody ChangePasswordRequest request) {
        authService.changePassword(request);
        return ApiResponse.ok();
    }

    @GetMapping("/tenants")
    public ApiResponse<List<TenantOptionResponse>> tenants() {
        return ApiResponse.ok(authService.tenants());
    }

    @PostMapping("/switch-tenant")
    public ApiResponse<BrowserAuthResponse> switchTenant(@Valid @RequestBody SwitchTenantRequest request,
                                                       HttpServletResponse httpResponse) {
        var response = authService.switchTenant(request);
        writeTokenCookie(httpResponse, response.accessToken(), response.expiresIn());
        return ApiResponse.ok(BrowserAuthResponse.from(response));
    }

    @GetMapping("/csrf")
    public ApiResponse<String> csrf(CsrfToken token) {
        return ApiResponse.ok(token.getToken());
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
