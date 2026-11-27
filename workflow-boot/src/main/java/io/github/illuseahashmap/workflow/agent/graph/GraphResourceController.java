package io.github.illuseahashmap.workflow.agent.graph;

import io.github.illuseahashmap.agent.runtime.infrastructure.persistence.JdbcGraphResourceConfiguration;
import io.github.illuseahashmap.workflow.shared.context.TrustedDataAccessContext;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/agent-runtime/resources")
@PreAuthorize("hasRole('PLATFORM_ADMIN')")
@ConditionalOnProperty(name="workflow.agent.graph.enabled", havingValue="true")
public class GraphResourceController {
    private final JdbcGraphResourceConfiguration resources;
    public GraphResourceController(JdbcGraphResourceConfiguration resources) { this.resources = resources; }
    @GetMapping public JdbcGraphResourceConfiguration.Configuration read() {
        return TrustedDataAccessContext.runAsSystemWorker(resources::read);
    }
    @PutMapping public void configure(@RequestBody JdbcGraphResourceConfiguration.Configuration request,
                                      org.springframework.security.core.Authentication authentication) {
        String actor = authentication.getPrincipal() instanceof io.github.illuseahashmap.workflow.shared.context.CurrentPrincipal principal
                ? principal.principalId() : authentication.getName();
        try {
            TrustedDataAccessContext.runAsSystemWorker(() -> resources.configure(request, actor));
        } catch (io.github.illuseahashmap.agent.runtime.application.graph.GraphGatewayException failure) {
            throw new io.github.illuseahashmap.workflow.shared.exception.BusinessException(
                    failure.code() == io.github.illuseahashmap.agent.runtime.application.graph.GraphGatewayException.Code.RESOURCE_BUSY
                            ? io.github.illuseahashmap.workflow.shared.exception.ErrorCode.CONFLICT
                            : io.github.illuseahashmap.workflow.shared.exception.ErrorCode.BAD_REQUEST,
                    failure.code().name());
        }
    }
}
