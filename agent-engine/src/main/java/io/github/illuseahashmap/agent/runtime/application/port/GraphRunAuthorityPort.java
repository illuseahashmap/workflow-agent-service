package io.github.illuseahashmap.agent.runtime.application.port;

import io.github.illuseahashmap.agent.runtime.application.graph.GraphGatewayContract.Binding;

/**
 * Resolve an opaque execution capability against the CURRENT platform authority.
 * Implementations must check audience, expiry, generation, cancellation and the
 * trusted Run/AgentVersion/input/subject binding; a browser/service principal alone
 * is insufficient. No fallback implementation and no public grant API exist.
 * Persistent adapters exist; production registration still requires artifact admission,
 * governed MODEL/VALIDATE, protected ingress and the dispatch ownership gate.
 */
public interface GraphRunAuthorityPort {
    AuthorizedRun authorize(String capability, Binding submittedBinding, long generation);

    record AuthorizedRun(Binding binding, long generation, String principalId,
                         String processInstanceId, String versionToolSetJson, String nodeToolSetJson) { }
}
