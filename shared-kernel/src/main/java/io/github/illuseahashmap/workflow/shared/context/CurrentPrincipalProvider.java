package io.github.illuseahashmap.workflow.shared.context;

@FunctionalInterface
public interface CurrentPrincipalProvider {

    CurrentPrincipal current();
}
