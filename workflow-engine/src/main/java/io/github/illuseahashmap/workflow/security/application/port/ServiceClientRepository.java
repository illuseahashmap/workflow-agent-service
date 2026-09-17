package io.github.illuseahashmap.workflow.security.application.port;

import io.github.illuseahashmap.workflow.security.domain.ServiceClient;
import java.util.Optional;

public interface ServiceClientRepository {

    Optional<ServiceClient> findByClientCode(String clientCode);
}
