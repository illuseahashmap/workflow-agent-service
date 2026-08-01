package io.github.illuseahashmap.workflow.auth.infrastructure.token;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.illuseahashmap.workflow.shared.context.CurrentPrincipal;
import io.github.illuseahashmap.workflow.shared.context.CurrentPrincipalContext;
import io.github.illuseahashmap.workflow.shared.context.TenantContext;
import io.github.illuseahashmap.workflow.shared.exception.BusinessException;
import io.github.illuseahashmap.workflow.shared.response.ApiResponse;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
public class AuthTokenAuthenticationFilter extends OncePerRequestFilter {

    private static final String BEARER_PREFIX = "Bearer ";

    private final AuthTokenService tokenService;
    private final ObjectMapper objectMapper;

    public AuthTokenAuthenticationFilter(AuthTokenService tokenService, ObjectMapper objectMapper) {
        this.tokenService = tokenService;
        this.objectMapper = objectMapper;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return "OPTIONS".equalsIgnoreCase(request.getMethod())
                || request.getHeader(HttpHeaders.AUTHORIZATION) == null;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        try {
            String authorization = request.getHeader(HttpHeaders.AUTHORIZATION);
            if (!authorization.startsWith(BEARER_PREFIX)) {
                filterChain.doFilter(request, response);
                return;
            }

            AuthTokenService.AuthTokenPayload payload = tokenService.verify(authorization.substring(BEARER_PREFIX.length()));
            CurrentPrincipal principal = new CurrentPrincipal(
                    "USER",
                    payload.userId(),
                    payload.username(),
                    payload.displayName(),
                    payload.tenantCode(),
                    payload.roles(),
                    payload.permissions()
            );
            CurrentPrincipalContext.set(principal);
            TenantContext.set(new TenantContext.TenantInfo(payload.tenantCode(), payload.tenantCode(), payload.tenantCode()));
            UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(
                    principal,
                    null,
                    payload.roles().stream().map(role -> new SimpleGrantedAuthority("ROLE_" + role)).toList()
            );
            SecurityContextHolder.getContext().setAuthentication(authentication);
            filterChain.doFilter(request, response);
        } catch (BusinessException exception) {
            writeError(response, exception);
        } finally {
            CurrentPrincipalContext.clear();
            TenantContext.clear();
            SecurityContextHolder.clearContext();
        }
    }

    private void writeError(HttpServletResponse response, BusinessException exception) throws IOException {
        if (response.isCommitted()) {
            return;
        }
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write(objectMapper.writeValueAsString(ApiResponse.fail(
                exception.getErrorCode().code(), exception.getMessage())));
    }
}
