package io.github.illuseahashmap.workflow.agent;

import io.github.illuseahashmap.agent.definition.domain.AgentDefinitionVersion;
import io.github.illuseahashmap.agent.definition.domain.AgentDefinitionVersionRepository;
import io.github.illuseahashmap.workflow.process.application.port.AgentVersionCatalog;
import java.util.Optional;
import org.springframework.stereotype.Component;

/** Composition-root adapter that keeps workflow-engine independent from agent-engine. */
@Component
public class AgentVersionCatalogAdapter implements AgentVersionCatalog {

    private final AgentDefinitionVersionRepository repository;

    public AgentVersionCatalogAdapter(AgentDefinitionVersionRepository repository) {
        this.repository = repository;
    }

    @Override
    public Optional<PublishedAgentVersion> findPublished(String tenantCode, long versionId) {
        return repository.findByVersionId(tenantCode, versionId)
                .filter(AgentDefinitionVersion::published)
                .map(version -> new PublishedAgentVersion(
                        version.id(), version.executionMode().name(), version.timeoutSeconds(),
                        version.inputSchema(), version.outputSchema()));
    }
}
