package io.github.illuseahashmap.agent.runtime.application.graph;

import java.util.List;
import static io.github.illuseahashmap.agent.runtime.application.graph.GraphGatewayException.Code.INVALID_REQUEST;

/** Framework-independent wire contracts. IDs and references are not authorization. */
public final class GraphGatewayContract {
    private GraphGatewayContract() { }

    public record Binding(String tenantCode, long runId, long agentVersionId, String graphArtifactDigest,
                          int stateSchemaVersion, String inputRef, String subjectRef, long deadlineEpochMs,
                          int maxModelCalls, int maxToolCalls) {
        public Binding {
            reference(tenantCode);
            reference(inputRef);
            reference(subjectRef);
            digest(graphArtifactDigest);
            require(runId > 0 && agentVersionId > 0 && stateSchemaVersion == 1 && deadlineEpochMs > 0);
            require(maxModelCalls >= 1 && maxModelCalls <= 100 && maxToolCalls >= 0 && maxToolCalls <= 200);
        }
    }

    public record ToolIntent(String toolCode, String argumentsRef, String argumentsDigest) {
        public ToolIntent {
            reference(toolCode);
            reference(argumentsRef);
            digest(argumentsDigest);
        }
    }

    public record Invocation(String kind, String operationId, List<String> inputRefs, ToolIntent tool) {
        public Invocation {
            require(List.of("MODEL", "TOOL", "VALIDATE").contains(kind == null ? "" : kind));
            reference(operationId);
            require(inputRefs != null && inputRefs.size() <= 512);
            inputRefs.forEach(GraphGatewayContract::reference);
            inputRefs = List.copyOf(inputRefs);
            require("TOOL".equals(kind) == (tool != null));
        }
    }

    public record AuthorizationRequest(Binding binding, long generation) {
        public AuthorizationRequest { require(binding != null && generation > 0); }
    }
    public record InvokeRequest(Binding binding, long generation, Invocation request) {
        public InvokeRequest { require(binding != null && generation > 0 && request != null); }
    }
    public record Authorized(String bindingDigest, long generation) { }
    public record Decision(List<ToolIntent> tools, String candidateRef) {
        public Decision {
            require(tools != null && tools.size() <= 16);
            tools = List.copyOf(tools);
            require(!tools.isEmpty() != (candidateRef != null));
            if (candidateRef != null) { reference(candidateRef); }
        }
    }
    public record Deferred(String waitId, String reason, long notBeforeEpochMs) {
        public Deferred {
            reference(waitId);
            require("QUOTA".equals(reason) || "RETRY".equals(reason));
            require(notBeforeEpochMs >= 0);
        }
    }
    public record Receipt(String bindingDigest, String operationId, String requestDigest,
                          String receiptRef, Decision decision, Boolean accepted) { }
    public record Reply(String bindingDigest, String operationId, String requestDigest,
                        Receipt receipt, Deferred deferred) { }

    public static void require(boolean accepted) {
        if (!accepted) { throw new GraphGatewayException(INVALID_REQUEST); }
    }
    public static void reference(String value) {
        require(value != null && value.matches("[A-Za-z0-9_./:-]{1,256}"));
    }
    public static void digest(String value) {
        require(value != null && value.matches("[0-9a-f]{64}"));
    }
}
