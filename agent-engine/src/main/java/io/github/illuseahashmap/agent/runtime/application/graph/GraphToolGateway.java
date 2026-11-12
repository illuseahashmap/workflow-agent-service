package io.github.illuseahashmap.agent.runtime.application.graph;

import io.github.illuseahashmap.agent.runtime.application.AgentToolRegistry;
import io.github.illuseahashmap.agent.runtime.application.AgentExecutionException;
import io.github.illuseahashmap.agent.runtime.application.port.AgentTool;
import io.github.illuseahashmap.agent.runtime.application.port.GraphReferencePort;
import io.github.illuseahashmap.agent.runtime.application.port.GraphRunAuthorityPort;
import io.github.illuseahashmap.workflow.shared.context.ExecutionDeadline;
import java.time.Clock;
import java.time.Duration;
import java.util.Objects;
import java.util.UUID;
import java.util.function.LongSupplier;
import io.github.illuseahashmap.agent.runtime.application.graph.GraphGatewayContract.AuthorizationRequest;
import io.github.illuseahashmap.agent.runtime.application.graph.GraphGatewayContract.Authorized;
import io.github.illuseahashmap.agent.runtime.application.graph.GraphGatewayContract.Binding;
import io.github.illuseahashmap.agent.runtime.application.graph.GraphGatewayContract.InvokeRequest;
import io.github.illuseahashmap.agent.runtime.application.graph.GraphGatewayContract.Receipt;
import io.github.illuseahashmap.agent.runtime.application.graph.GraphGatewayContract.Reply;
import static io.github.illuseahashmap.agent.runtime.application.graph.GraphGatewayContract.reference;
import static io.github.illuseahashmap.agent.runtime.application.graph.GraphGatewayContract.require;
import static io.github.illuseahashmap.agent.runtime.application.graph.GraphGatewayException.Code.AUTHORITY_DENIED;
import static io.github.illuseahashmap.agent.runtime.application.graph.GraphGatewayException.Code.BINDING_MISMATCH;
import static io.github.illuseahashmap.agent.runtime.application.graph.GraphGatewayException.Code.DEADLINE_EXPIRED;
import static io.github.illuseahashmap.agent.runtime.application.graph.GraphGatewayException.Code.OPERATION_UNSUPPORTED;
import static io.github.illuseahashmap.agent.runtime.application.graph.GraphGatewayException.Code.REFERENCE_DENIED;
import static io.github.illuseahashmap.agent.runtime.application.graph.GraphGatewayException.Code.TOOL_IN_PROGRESS;
import static io.github.illuseahashmap.agent.runtime.application.graph.GraphGatewayException.Code.TOOL_OUTCOME_UNKNOWN;
import static io.github.illuseahashmap.agent.runtime.application.graph.GraphGatewayException.Code.TOOL_REJECTED;
import static io.github.illuseahashmap.agent.runtime.application.graph.GraphGatewayException.Code.TOOL_TRANSIENT;

/** Reuses platform tool governance. Not auto-registered until authoritative dispatch exists. */
public final class GraphToolGateway implements io.github.illuseahashmap.agent.runtime.application.port.GraphGatewayPort {
    private final GraphRunAuthorityPort authority;
    private final GraphReferencePort references;
    private final AgentToolRegistry registry;
    private final GraphGatewayCodec codec;
    private final Clock clock;
    private final LongSupplier ticker;

    public GraphToolGateway(GraphRunAuthorityPort authority, GraphReferencePort references,
                            AgentToolRegistry registry, GraphGatewayCodec codec) {
        this(authority, references, registry, codec, Clock.systemUTC(), System::nanoTime);
    }
    public GraphToolGateway(GraphRunAuthorityPort authority, GraphReferencePort references,
                            AgentToolRegistry registry, GraphGatewayCodec codec, Clock clock, LongSupplier ticker) {
        this.authority = Objects.requireNonNull(authority);
        this.references = Objects.requireNonNull(references);
        this.registry = Objects.requireNonNull(registry);
        this.codec = Objects.requireNonNull(codec);
        this.clock = Objects.requireNonNull(clock);
        this.ticker = Objects.requireNonNull(ticker);
    }

    public Authorized authorize(String capability, AuthorizationRequest submitted) {
        try (var budget = ExecutionDeadline.open(remaining(submitted.binding()), ticker)) {
            resolve(capability, submitted.binding(), submitted.generation());
            budget.remaining();
            return new Authorized(codec.fingerprint(submitted.binding()), submitted.generation());
        } catch (ExecutionDeadline.Exceeded failure) {
            throw new GraphGatewayException(DEADLINE_EXPIRED);
        }
    }

    public Reply invoke(String capability, InvokeRequest submitted) {
        try (var budget = ExecutionDeadline.open(remaining(submitted.binding()), ticker)) {
            var run = resolve(capability, submitted.binding(), submitted.generation());
            var request = submitted.request();
            if (!"TOOL".equals(request.kind())) { throw new GraphGatewayException(OPERATION_UNSUPPORTED); }
            require(request.inputRefs().isEmpty() && request.operationId().matches("tool:[1-9][0-9]{0,2}"));
            int ordinal = Integer.parseInt(request.operationId().substring(5));
            require(ordinal <= run.binding().maxToolCalls());
            var argument = verifiedArguments(run, request);
            String bindingDigest = codec.fingerprint(run.binding());
            // Parsing happens only after authoritative lookup; the runtime never submits raw arguments.
            var arguments = codec.arguments(argument.json());
            if (!run.equals(resolve(capability, submitted.binding(), submitted.generation()))) {
                throw new GraphGatewayException(BINDING_MISMATCH);
            }
            var traceId = UUID.randomUUID().toString();
            var result = registry.execute(run.binding().tenantCode(), run.versionToolSetJson(), run.nodeToolSetJson(),
                    request.tool().toolCode(), new AgentTool.Request(run.binding().tenantCode(), arguments,
                            budget.remaining(), traceId, run.processInstanceId(), run.binding().runId(),
                            request.operationId(), run.binding().agentVersionId(), run.principalId()));
            // A tool can finish after cancellation/revocation. Preserve its ledger, do not return success.
            var current = resolve(capability, submitted.binding(), submitted.generation());
            if (!run.equals(current)) { throw new GraphGatewayException(BINDING_MISMATCH); }
            registry.validateResultAccess(new AgentTool.ResultAccessContext(run.binding().tenantCode(),
                    run.binding().agentVersionId(), run.principalId(), run.versionToolSetJson(), run.nodeToolSetJson()),
                    request.tool().toolCode(), result.output());
            budget.remaining();
            String receiptRef = references.recordReceipt(run, request, result.idempotencyKey(),
                    GraphGatewayCodec.sha256(result.output()));
            reference(receiptRef);
            budget.remaining();
            if (!run.equals(resolve(capability, submitted.binding(), submitted.generation()))) {
                throw new GraphGatewayException(BINDING_MISMATCH);
            }
            String requestDigest = codec.fingerprint(request);
            var receipt = new Receipt(bindingDigest, request.operationId(), requestDigest, receiptRef, null, null);
            return new Reply(bindingDigest, request.operationId(), requestDigest, receipt, null);
        } catch (ExecutionDeadline.Exceeded failure) {
            throw new GraphGatewayException(DEADLINE_EXPIRED);
        } catch (AgentExecutionException failure) {
            throw toolFailure(failure);
        }
    }

    @Override public java.util.Optional<Reply> replay(String capability, InvokeRequest submitted) {
        try (var budget = ExecutionDeadline.open(remaining(submitted.binding()), ticker)) {
            var run = resolve(capability, submitted.binding(), submitted.generation());
            var request = submitted.request();
            require("TOOL".equals(request.kind()) && request.inputRefs().isEmpty()
                    && request.operationId().matches("tool:[1-9][0-9]{0,2}"));
            int ordinal = Integer.parseInt(request.operationId().substring(5));
            require(ordinal <= run.binding().maxToolCalls());
            var argument = verifiedArguments(run, request);
            var access = new AgentTool.ResultAccessContext(run.binding().tenantCode(), run.binding().agentVersionId(), run.principalId(),
                    run.versionToolSetJson(), run.nodeToolSetJson());
            var history = new AgentToolRegistry.RunHistory(access, run.binding().runId());
            registry.validateRunResultAccess(history);
            String key = run.binding().runId() + ":" + request.operationId() + ":" + request.tool().toolCode();
            if (!history.contains(key)) { return java.util.Optional.empty(); }
            String output = registry.restoreStep(history, ordinal, request.tool().toolCode(), key, codec.arguments(argument.json()));
            String ref = references.recordReceipt(run, request, key, GraphGatewayCodec.sha256(output));
            budget.remaining();
            resolve(capability, submitted.binding(), submitted.generation());
            String binding = codec.fingerprint(run.binding());
            String digest = codec.fingerprint(request);
            return java.util.Optional.of(new Reply(binding, request.operationId(), digest,
                    new Receipt(binding, request.operationId(), digest, ref, null, null), null));
        } catch (ExecutionDeadline.Exceeded failure) {
            throw new GraphGatewayException(DEADLINE_EXPIRED);
        } catch (AgentExecutionException failure) {
            throw toolFailure(failure);
        }
    }

    private GraphReferencePort.Arguments verifiedArguments(GraphRunAuthorityPort.AuthorizedRun run, GraphGatewayContract.Invocation request) {
        var argument = references.arguments(run, request);
        if (argument == null || !codec.fingerprint(run.binding()).equals(argument.bindingDigest())
                || !request.operationId().equals(argument.operationId())
                || !request.tool().toolCode().equals(argument.toolCode())
                || !request.tool().argumentsDigest().equals(argument.argumentsDigest())
                || argument.json() == null
                || !argument.argumentsDigest().equals(GraphGatewayCodec.sha256(argument.json()))) {
            throw new GraphGatewayException(REFERENCE_DENIED);
        }
        return argument;
    }
    private static GraphGatewayException toolFailure(AgentExecutionException failure) {
        return new GraphGatewayException(switch (failure.failure().errorCode()) {
            case "AGENT_TOOL_EXECUTION_IN_PROGRESS" -> TOOL_IN_PROGRESS;
            case "AGENT_TOOL_EXECUTION_UNKNOWN" -> TOOL_OUTCOME_UNKNOWN;
            default -> failure.failure().retryable() ? TOOL_TRANSIENT : TOOL_REJECTED;
        });
    }

    private GraphRunAuthorityPort.AuthorizedRun resolve(String capability, Binding binding, long generation) {
        if (capability == null || !capability.matches("[A-Za-z0-9_.:-]{1,256}")) {
            throw new GraphGatewayException(AUTHORITY_DENIED);
        }
        remaining(binding);
        var run = authority.authorize(capability, binding, generation);
        if (run == null || !binding.equals(run.binding()) || generation != run.generation()
                || run.principalId() == null || run.principalId().isBlank()
                || run.versionToolSetJson() == null || run.versionToolSetJson().isBlank()) {
            throw new GraphGatewayException(BINDING_MISMATCH);
        }
        return run;
    }
    private Duration remaining(Binding binding) {
        long now = clock.millis();
        if (binding.deadlineEpochMs() <= now) { throw new GraphGatewayException(DEADLINE_EXPIRED); }
        // Bound one RPC independently of the business deadline; authorization is included.
        return Duration.ofMillis(Math.min(binding.deadlineEpochMs() - now, 120_000));
    }
}
