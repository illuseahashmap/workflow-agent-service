package io.github.illuseahashmap.workflow.auth.application;

import io.github.illuseahashmap.workflow.auth.domain.AuthAuthorizationRepository;
import io.github.illuseahashmap.workflow.auth.domain.AuthMembershipRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuthTenantProvisioningService {

    private final AuthMembershipRepository membershipRepository;
    private final AuthAuthorizationRepository authorizationRepository;

    public AuthTenantProvisioningService(AuthMembershipRepository membershipRepository,
                                         AuthAuthorizationRepository authorizationRepository) {
        this.membershipRepository = membershipRepository;
        this.authorizationRepository = authorizationRepository;
    }

    @Transactional
    public void provision(String tenantCode, String administratorUserId) {
        authorizationRepository.ensureTenantDefaults(tenantCode);
        membershipRepository.add(administratorUserId, tenantCode);
        authorizationRepository.grantRole(administratorUserId, tenantCode, "TENANT_ADMIN");
    }
}
