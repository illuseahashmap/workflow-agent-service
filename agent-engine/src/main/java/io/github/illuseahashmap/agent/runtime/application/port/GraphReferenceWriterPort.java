package io.github.illuseahashmap.agent.runtime.application.port;

/** Internal model-result committer only; deliberately has no HTTP write route. */
public interface GraphReferenceWriterPort {
    String recordArguments(GraphRunAuthorityPort.AuthorizedRun run, String operationId, String toolCode, String json);
}
