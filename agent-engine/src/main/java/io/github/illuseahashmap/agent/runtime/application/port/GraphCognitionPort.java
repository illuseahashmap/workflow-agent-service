package io.github.illuseahashmap.agent.runtime.application.port;

import io.github.illuseahashmap.agent.provider.application.port.ModelProviderResponse;
import io.github.illuseahashmap.agent.runtime.application.graph.GraphGatewayContract.Invocation;
import io.github.illuseahashmap.agent.runtime.application.graph.GraphGatewayContract.Receipt;
import io.github.illuseahashmap.agent.runtime.application.port.GraphRunAuthorityPort.AuthorizedRun;

import java.util.List;

/** Durable model-response/validation receipts, not a graph cursor or execution loop. */
public interface GraphCognitionPort {
    History history(AuthorizedRun run, Invocation request);

    boolean hasResponse(AuthorizedRun run, Invocation request);

    ModelClaim beginModel(AuthorizedRun run, Invocation request, String configurationDigest);

    void saveResponse(
            AuthorizedRun run, Invocation request, String ticket, ModelProviderResponse response);

    Receipt finishModel(AuthorizedRun run, Invocation request, String tool, String argumentsJson);

    default Receipt finishModels(
            AuthorizedRun run, Invocation request, List<ModelProviderResponse.ToolCall> tools) {
        io.github.illuseahashmap.agent.runtime.application.graph.GraphGatewayContract.require(
                tools.size() <= 1);
        return tools.isEmpty()
                ? finishModel(run, request, null, null)
                : finishModel(
                        run, request, tools.getFirst().name(), tools.getFirst().argumentsJson());
    }

    default Receipt finishSummary(AuthorizedRun run, Invocation request) {
        throw new UnsupportedOperationException();
    }

    default Receipt finishReadIntent(
            AuthorizedRun run,
            Invocation request,
            io.github.illuseahashmap.agent.runtime.application.graph.GraphMemory.ReadAction
                    action) {
        throw new UnsupportedOperationException();
    }

    default Receipt readResult(AuthorizedRun run, Invocation request) {
        throw new UnsupportedOperationException();
    }

    Receipt finishValidation(
            AuthorizedRun run, Invocation request, boolean accepted, String reasonCode);

    record ModelClaim(String ticket, ModelProviderResponse response, Receipt receipt) {}

    record History(
            String inputSnapshot,
            List<ToolFact> tools,
            ModelProviderResponse candidate,
            io.github.illuseahashmap.agent.runtime.application.graph.GraphTaskContract.Report
                    taskFacts,
            io.github.illuseahashmap.agent.runtime.application.graph.GraphPlan.Context plan,
            io.github.illuseahashmap.agent.runtime.application.graph.GraphMemory.Context memory,
            io.github.illuseahashmap.agent.runtime.application.graph.GraphFormatRepair.Context
                    repair) {
        public History(
                String input,
                List<ToolFact> tools,
                ModelProviderResponse candidate,
                io.github.illuseahashmap.agent.runtime.application.graph.GraphTaskContract.Report
                        facts,
                io.github.illuseahashmap.agent.runtime.application.graph.GraphPlan.Context plan,
                io.github.illuseahashmap.agent.runtime.application.graph.GraphMemory.Context
                        memory) {
            this(input, tools, candidate, facts, plan, memory, null);
        }

        public History(
                String input,
                List<ToolFact> tools,
                ModelProviderResponse candidate,
                io.github.illuseahashmap.agent.runtime.application.graph.GraphTaskContract.Report
                        facts,
                io.github.illuseahashmap.agent.runtime.application.graph.GraphPlan.Context plan) {
            this(input, tools, candidate, facts, plan, null);
        }

        public History(
                String inputSnapshot, List<ToolFact> tools, ModelProviderResponse candidate) {
            this(inputSnapshot, tools, candidate, null, null);
        }

        public History(
                String inputSnapshot,
                List<ToolFact> tools,
                ModelProviderResponse candidate,
                io.github.illuseahashmap.agent.runtime.application.graph.GraphTaskContract.Report
                        taskFacts) {
            this(inputSnapshot, tools, candidate, taskFacts, null);
        }

        public History {
            tools = List.copyOf(tools);
        }
    }

    record ToolFact(
            String receiptRef,
            String toolCode,
            String argumentsJson,
            String output,
            String outputDigest) {}
}
