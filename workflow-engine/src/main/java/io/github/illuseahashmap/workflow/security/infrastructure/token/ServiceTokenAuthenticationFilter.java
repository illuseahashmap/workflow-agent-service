package io.github.illuseahashmap.workflow.security.infrastructure.token;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.illuseahashmap.workflow.security.domain.ServiceClient;
import io.github.illuseahashmap.workflow.security.domain.ServiceTokenContext;
import io.github.illuseahashmap.workflow.shared.context.CurrentPrincipal;
import io.github.illuseahashmap.workflow.shared.context.CurrentPrincipalContext;
import io.github.illuseahashmap.workflow.shared.exception.BusinessException;
import io.github.illuseahashmap.workflow.shared.response.ApiResponse;
import io.github.illuseahashmap.workflow.shared.context.TenantContext;
import io.github.illuseahashmap.workflow.security.infrastructure.web.CachedBodyHttpServletRequest;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Set;
import org.springframework.stereotype.Component;
import org.springframework.util.AntPathMatcher;
import org.springframework.security.core.Authentication;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
public class ServiceTokenAuthenticationFilter extends OncePerRequestFilter {

    public static final String TOKEN_HEADER = "X-Workflow-Token";

    private final WorkflowSecurityProperties properties;
    private final ServiceTokenCryptoService cryptoService;
    private final ServiceTokenValidationService validationService;
    private final ObjectMapper objectMapper;
    private final AntPathMatcher pathMatcher = new AntPathMatcher();

    public ServiceTokenAuthenticationFilter(WorkflowSecurityProperties properties,
                                            ServiceTokenCryptoService cryptoService,
                                            ServiceTokenValidationService validationService,
                                            ObjectMapper objectMapper) {
        this.properties = properties;
        this.cryptoService = cryptoService;
        this.validationService = validationService;
        this.objectMapper = objectMapper;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        if (!properties.isEnabled() || "OPTIONS".equalsIgnoreCase(request.getMethod())) {
            return true;
        }
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.isAuthenticated()) {
            return true;
        }
        String path = request.getRequestURI();
        return properties.getPublicPaths().stream().anyMatch(pattern -> pathMatcher.match(pattern, path));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        CachedBodyHttpServletRequest wrappedRequest = new CachedBodyHttpServletRequest(request);
        try {
            String bodySha256 = sha256Hex(wrappedRequest.getCachedBody());
            ServiceTokenCryptoService.ServiceTokenEnvelope envelope =
                    cryptoService.parse(request.getHeader(TOKEN_HEADER));
            ServiceClient client = validationService.loadClient(envelope.clientCode());
            ServiceTokenPayload payload = cryptoService.decrypt(envelope, client);
            ServiceTokenValidationService.ClientValidationResult result = validationService.validate(
                    payload, client, request.getMethod(), request.getRequestURI(), bodySha256);
            TenantContext.set(result.tenantInfo());
            ServiceTokenContext.set(new ServiceTokenContext.ServiceTokenPrincipal(result.clientCode(), result.tokenVersion()));
            CurrentPrincipal principal = new CurrentPrincipal(
                    "SERVICE",
                    result.clientCode(),
                    result.clientCode(),
                    result.clientCode(),
                    result.tenantInfo().tenantCode(),
                    Set.of(),
                    Set.of()
            );
            CurrentPrincipalContext.set(principal);
            SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                    principal, null, java.util.List.of(new SimpleGrantedAuthority("ROLE_SERVICE"))));
            filterChain.doFilter(wrappedRequest, response);
        } catch (BusinessException exception) {
            writeError(response, exception);
        } finally {
            TenantContext.clear();
            ServiceTokenContext.clear();
            CurrentPrincipalContext.clear();
            SecurityContextHolder.clearContext();
        }
    }

    private void writeError(HttpServletResponse response, BusinessException exception) throws IOException {
        if (response.isCommitted()) {
            return;
        }
        int status = switch (exception.getErrorCode()) {
            case FORBIDDEN -> HttpServletResponse.SC_FORBIDDEN;
            case INTERNAL_ERROR -> HttpServletResponse.SC_INTERNAL_SERVER_ERROR;
            default -> HttpServletResponse.SC_UNAUTHORIZED;
        };
        response.setStatus(status);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write(objectMapper.writeValueAsString(ApiResponse.fail(
                exception.getErrorCode().code(), exception.getMessage())));
    }

    private String sha256Hex(byte[] body) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(body == null ? new byte[0] : body);
            StringBuilder builder = new StringBuilder(hash.length * 2);
            for (byte value : hash) {
                builder.append(String.format("%02x", value));
            }
            return builder.toString();
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is not available", exception);
        }
    }
}
