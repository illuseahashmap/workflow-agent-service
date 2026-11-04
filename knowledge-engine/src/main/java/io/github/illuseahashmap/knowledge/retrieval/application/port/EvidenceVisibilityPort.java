package io.github.illuseahashmap.knowledge.retrieval.application.port;

import io.github.illuseahashmap.knowledge.retrieval.domain.EvidenceReference;
import java.util.List;

/** Current visibility of immutable evidence; old retrieval authorization is never a bearer grant. */
public interface EvidenceVisibilityPort {
    boolean visible(String tenantCode, List<String> authorizedScopes, List<EvidenceReference> references,
                    List<Long> indexVersionIds);
}
