package io.github.illuseahashmap.workflow.auth.infrastructure.token;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.illuseahashmap.workflow.auth.domain.AuthAuthorizationRepository;
import io.github.illuseahashmap.workflow.auth.domain.AuthMembershipRepository;
import io.github.illuseahashmap.workflow.auth.domain.AuthTenantRepository;
import io.github.illuseahashmap.workflow.auth.domain.AuthUser;
import io.github.illuseahashmap.workflow.auth.domain.AuthUserRepository;
import io.github.illuseahashmap.workflow.auth.interfaces.security.AuthSecurityFilter;
import io.github.illuseahashmap.workflow.shared.context.CurrentPrincipal;
import io.github.illuseahashmap.workflow.shared.context.CurrentPrincipalContext;
import io.github.illuseahashmap.workflow.shared.context.TenantContext;
import io.github.illuseahashmap.workflow.shared.exception.BusinessException;
import io.github.illuseahashmap.workflow.shared.exception.ErrorCode;
import io.github.illuseahashmap.workflow.shared.response.ApiResponse;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.stream.Stream;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
public class AuthTokenAuthenticationFilter extends OncePerRequestFilter implements AuthSecurityFilter {

    private static final String BEARER_PREFIX = "Bearer ";

    private final AuthTokenService tokenService;
    private final ObjectMapper objectMapper;
    private final AuthTenantRepository tenantRepository;
    private final AuthMembershipRepository membershipRepository;
    private final AuthUserRepository userRepository;
    private final AuthAuthorizationRepository authorizationRepository;

    public AuthTokenAuthenticationFilter(AuthTokenService tokenService,
                                         ObjectMapper objectMapper,
                                         AuthTenantRepository tenantRepository,
                                         AuthMembershipRepository membershipRepository,
                                         AuthUserRepository userRepository,
                                         AuthAuthorizationRepository authorizationRepository) {
        this.tokenService = tokenService;
        this.objectMapper = objectMapper;
        this.tenantRepository = tenantRepository;
        this.membershipRepository = membershipRepository;
        this.userRepository = userRepository;
        this.authorizationRepository = authorizationRepository;
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
            AuthTenantRepository.AuthTenant tenant = tenantRepository.findByTenantCode(payload.tenantCode())
                    .orElseThrow(() -> new BusinessException(ErrorCode.FORBIDDEN, "Tenant does not exist"));
            AuthMembershipRepository.TenantMembership membership = membershipRepository
                    .find(payload.userId(), payload.tenantCode())
                    .orElseThrow(() -> new BusinessException(ErrorCode.FORBIDDEN, "Tenant membership does not exist"));
            if (!tenant.enabled() || !membership.tenantEnabled() || !membership.membershipEnabled()) {
                throw new BusinessException(ErrorCode.FORBIDDEN, "Tenant access is disabled");
            }
            AuthUser user = userRepository.findByUserId(payload.userId())
                    .orElseThrow(() -> new BusinessException(ErrorCode.UNAUTHORIZED, "User does not exist"));
            if (!user.enabled()) {
                throw new BusinessException(ErrorCode.FORBIDDEN, "User is disabled");
            }
            var roles = authorizationRepository.findRoleCodes(payload.userId(), payload.tenantCode());
            var permissions = authorizationRepository.findPermissionCodes(payload.userId(), payload.tenantCode());
            CurrentPrincipal principal = new CurrentPrincipal(
                    "USER",
                    user.userId(),
                    user.username(),
                    user.displayName(),
                    payload.tenantCode(),
                    roles,
                    permissions
            );
            CurrentPrincipalContext.set(principal);
            TenantContext.set(new TenantContext.TenantInfo(tenant.tenantId(), tenant.tenantCode(), tenant.tenantName()));
            UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(
                    principal,
                    null,
                    Stream.concat(
                                    roles.stream().map(role -> new SimpleGrantedAuthority("ROLE_" + role)),
                                    permissions.stream().map(SimpleGrantedAuthority::new))
                            .toList()
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
        response.setStatus(exception.getErrorCode() == ErrorCode.FORBIDDEN
                ? HttpServletResponse.SC_FORBIDDEN
                : HttpServletResponse.SC_UNAUTHORIZED);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write(objectMapper.writeValueAsString(ApiResponse.fail(
                exception.getErrorCode().code(), exception.getMessage())));
    }
}
