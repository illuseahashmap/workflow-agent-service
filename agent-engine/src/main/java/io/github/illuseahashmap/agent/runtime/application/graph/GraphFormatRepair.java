package io.github.illuseahashmap.agent.runtime.application.graph;

/** Frozen opt-in policy and non-authoritative source for a single final-answer format repair. */
public final class GraphFormatRepair {
    private GraphFormatRepair() {}

    public record Policy(int version) {
        public Policy {
            GraphGatewayContract.require(version == 1);
        }
    }

    public record Context(String candidateRef, String original, String reason) {}
}
