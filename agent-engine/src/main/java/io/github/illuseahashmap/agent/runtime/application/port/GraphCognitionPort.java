package io.github.illuseahashmap.agent.runtime.application.port;

import io.github.illuseahashmap.agent.provider.application.port.ModelProviderResponse;
import io.github.illuseahashmap.agent.runtime.application.graph.GraphGatewayContract.Invocation;
import io.github.illuseahashmap.agent.runtime.application.graph.GraphGatewayContract.Receipt;
import io.github.illuseahashmap.agent.runtime.application.port.GraphRunAuthorityPort.AuthorizedRun;
import java.util.List;

/** Durable model-response/validation receipts, not a graph cursor or execution loop. */
public interface GraphCognitionPort {
    History history(AuthorizedRun run, Invocation request);
    ModelClaim beginModel(AuthorizedRun run, Invocation request, String configurationDigest);
    void saveResponse(AuthorizedRun run, Invocation request, String ticket, ModelProviderResponse response);
    Receipt finishModel(AuthorizedRun run, Invocation request, String tool, String argumentsJson);
    Receipt finishValidation(AuthorizedRun run, Invocation request, boolean accepted, String reasonCode);

    record ModelClaim(String ticket, ModelProviderResponse response, Receipt receipt) { }
    record History(String inputSnapshot, List<ToolFact> tools, ModelProviderResponse candidate) {
        public History { tools = List.copyOf(tools); }
    }
    record ToolFact(String receiptRef, String toolCode, String argumentsJson, String output, String outputDigest) { }
}
