package io.github.illuseahashmap.workflow.security.infrastructure.serviceauth;

import io.github.illuseahashmap.workflow.security.domain.ServiceTokenContext;
import io.github.illuseahashmap.workflow.tenant.domain.TenantContext;
import io.github.illuseahashmap.workflow.security.infrastructure.web.CachedBodyHttpServletRequest;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import org.springframework.stereotype.Component;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
public class ServiceTokenAuthenticationFilter extends OncePerRequestFilter {

    private static final String TOKEN_HEADER = "X-Workflow-Token";

    private final WorkflowSecurityProperties properties;
    private final ServiceTokenCryptoService cryptoService;
    private final ServiceTokenValidationService validationService;
    private final AntPathMatcher pathMatcher = new AntPathMatcher();

    public ServiceTokenAuthenticationFilter(WorkflowSecurityProperties properties,
                                            ServiceTokenCryptoService cryptoService,
                                            ServiceTokenValidationService validationService) {
        this.properties = properties;
        this.cryptoService = cryptoService;
        this.validationService = validationService;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        if (!properties.isEnabled() || "OPTIONS".equalsIgnoreCase(request.getMethod())) {
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
            ServiceTokenPayload payload = cryptoService.decrypt(request.getHeader(TOKEN_HEADER));
            ServiceTokenValidationService.ClientValidationResult result = validationService.validate(
                    payload, request.getMethod(), request.getRequestURI(), bodySha256);
            TenantContext.set(result.tenantInfo());
            ServiceTokenContext.set(new ServiceTokenContext.ServiceTokenPrincipal(result.clientCode(), result.tokenVersion()));
            filterChain.doFilter(wrappedRequest, response);
        } finally {
            TenantContext.clear();
            ServiceTokenContext.clear();
        }
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
