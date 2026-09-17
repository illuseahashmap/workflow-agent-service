package io.github.illuseahashmap.workflow.shared.context;

@FunctionalInterface
public interface TenantProvider {

    TenantContext.TenantInfo current();
}
