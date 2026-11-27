package io.github.illuseahashmap.agent.runtime.application.port;

import io.github.illuseahashmap.agent.runtime.application.graph.GraphGatewayContract.Binding;
import java.time.Instant;
import java.util.Optional;

/** Whole-graph delivery only. First delivery starts one business Attempt; resumed generations reuse it. */
public interface GraphDispatchPort {
    /** Trusted submission path only; atomically reserves an untouched QUEUED Run. Not a public grant API. */
    Binding reserve(String tenantCode, long runId, String artifactDigest, int maxModelCalls, int maxToolCalls);

    /** Caller has already acquired admission capacity; no unbounded batch prefetch. */
    Optional<Claim> claim(String tenantCode, String workerId, int leaseSeconds);

    /** Equal-share, least-recently-dispatched tenant; call only with a free local slot. */
    Optional<Claim> claimNext(String workerId, int leaseSeconds);

    boolean renew(Claim claim, int leaseSeconds);

    /** Only transports the LangGraph interrupt token. Does not choose a next graph node. */
    boolean defer(Claim claim, String checkpointId, String waitId, Instant availableAt);

    /** Persists a graph observation, NOT business success and NOT a Flowable completion. */
    boolean acknowledge(Claim claim, Observation observation);

    /** Internal reconciliation after a lost ACK; never returns capability material or graph node state. */
    Optional<DispatchView> inspect(String tenantCode, long runId);

    void revoke(String tenantCode, long runId);

    record Claim(String commandId, Binding binding, long generation, String capability,
                 String workerId, Instant leaseExpiresAt, String checkpointId, String waitId) {
        @Override public String toString() {
            return "GraphClaim[commandId=" + commandId + ", generation=" + generation + ", capability=REDACTED]";
        }
    }

    record Observation(String status, String checkpointId, String candidateRef, String validationReceiptRef) { }

    record DispatchView(String commandId, String status, String executionState, long generation,
                        Instant availableAt, Observation observation, String completionStatus, String completionReason,
                        Instant completedAt) { }
}
