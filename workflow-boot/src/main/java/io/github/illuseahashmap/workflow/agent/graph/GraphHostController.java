package io.github.illuseahashmap.workflow.agent.graph;

import io.github.illuseahashmap.agent.runtime.application.graph.GraphGatewayCodec;
import io.github.illuseahashmap.agent.runtime.application.graph.GraphGatewayContract.Binding;
import io.github.illuseahashmap.agent.runtime.application.graph.GraphGatewayException;
import io.github.illuseahashmap.agent.runtime.application.port.GraphDispatchPort.Claim;
import io.github.illuseahashmap.agent.runtime.application.port.GraphDispatchPort.Observation;
import io.github.illuseahashmap.agent.runtime.infrastructure.persistence.JdbcGraphExecutionStore;
import io.github.illuseahashmap.agent.runtime.interfaces.rest.GraphGatewayProtocol;
import io.github.illuseahashmap.workflow.shared.context.TrustedDataAccessContext;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import static io.github.illuseahashmap.agent.runtime.application.graph.GraphGatewayContract.require;
import static io.github.illuseahashmap.agent.runtime.application.graph.GraphGatewayContract.reference;

@RestController
@ConditionalOnProperty(name="workflow.agent.graph.enabled", havingValue="true")
public class GraphHostController {
    private final GraphHostConfiguration.GraphHostSettings settings;
    private final JdbcGraphExecutionStore executions;
    private final GraphGatewayProtocol gateway;
    private final GraphGatewayCodec codec = new GraphGatewayCodec();
    public GraphHostController(GraphHostConfiguration.GraphHostSettings settings, JdbcGraphExecutionStore executions, GraphGatewayProtocol gateway) {
        this.settings = settings;
        this.executions = executions;
        this.gateway = gateway;
    }
    public record Lease(String commandId, Binding binding, long generation, String capability, String workerId,
                        long leaseExpiresEpochMs, String checkpointId, String waitId) {
        public Lease {
            reference(commandId);
            reference(capability);
            reference(workerId);
            require(binding != null && generation > 0 && leaseExpiresEpochMs > 0 && (checkpointId == null) == (waitId == null));
            if (checkpointId != null) {
                reference(checkpointId);
                reference(waitId);
            }
        }
        Claim claim() { return new Claim(commandId, binding, generation, capability, workerId, Instant.ofEpochMilli(leaseExpiresEpochMs), checkpointId, waitId); }
        static Lease of(Claim c) { return new Lease(c.commandId(), c.binding(), c.generation(), c.capability(), c.workerId(),
                c.leaseExpiresAt().toEpochMilli(), c.checkpointId(), c.waitId()); }
        @Override public String toString() { return "GraphLease[capability=REDACTED]"; }
    }
    public record ClaimRequest(String artifactDigest) { }
    public record ClaimReply(Lease claim) { }
    public record LeaseRequest(Lease claim) { }
    public record RenewRequest(java.util.List<Lease> claims) { }
    public record WaitRequest(Lease claim, String checkpointId, String waitId, long notBeforeEpochMs) { }
    public record AckRequest(Lease claim, Observation observation) { }

    @PostMapping(value={"/internal/agent-runtime/v1/{action}", "/internal/agent-dispatch/v1/{action}"}, produces="application/json")
    public ResponseEntity<byte[]> exchange(@PathVariable String action, HttpServletRequest request) {
        try {
            boolean local = settings.allowLoopbackHttp() && ("127.0.0.1".equals(request.getRemoteAddr()) || "::1".equals(request.getRemoteAddr()));
            if (!request.isSecure() && !local) { return error(403, "AUTHORITY_DENIED"); }
            boolean dispatch = request.getRequestURI().startsWith("/internal/agent-dispatch/");
            String worker = dispatch ? authenticateWorker(request) : null;
            if (request.getContentLengthLong() > GraphGatewayCodec.MAX_WIRE_BYTES) { return error(413, "INVALID_REQUEST"); }
            byte[] body = request.getInputStream().readNBytes(GraphGatewayCodec.MAX_WIRE_BYTES + 1);
            if (body.length > GraphGatewayCodec.MAX_WIRE_BYTES) { return error(413, "INVALID_REQUEST"); }
            return TrustedDataAccessContext.runAsSystemWorker(() -> {
                if (!dispatch) {
                    var response = gateway.exchange("POST", request.getRequestURI(), request.getHeader("Authorization"), body);
                    return ResponseEntity.status(response.status()).body(response.body());
                }
                Object result = switch (action) {
                    case "claim" -> {
                        var submitted = codec.read(body, ClaimRequest.class);
                        require(settings.artifactDigest().equals(submitted.artifactDigest()));
                        yield new ClaimReply(executions.claimNext(worker, settings.leaseSeconds(), settings.artifactDigest()).map(Lease::of).orElse(null));
                    }
                    case "renew" -> Map.of("accepted", executions.renew(owned(codec.read(body, LeaseRequest.class).claim(), worker), settings.leaseSeconds()));
                    case "renew-batch" -> {
                        var submitted = codec.read(body, RenewRequest.class);
                        require(submitted.claims() != null && !submitted.claims().isEmpty() && submitted.claims().size() <= 32);
                        var claims = submitted.claims().stream().map(c -> owned(c, worker)).toList();
                        long until = System.nanoTime() + 1_000_000_000L;
                        var accepted = new java.util.ArrayList<Boolean>();
                        for (Claim claim : claims) { accepted.add(System.nanoTime() < until && executions.renew(claim, settings.leaseSeconds())); }
                        yield Map.of("accepted", accepted);
                    }
                    case "defer" -> {
                        var wait = codec.read(body, WaitRequest.class);
                        yield Map.of("accepted", executions.defer(owned(wait.claim(), worker), wait.checkpointId(), wait.waitId(), Instant.ofEpochMilli(wait.notBeforeEpochMs())));
                    }
                    case "acknowledge" -> {
                        var ack = codec.read(body, AckRequest.class);
                        yield Map.of("accepted", executions.acknowledge(owned(ack.claim(), worker), ack.observation()));
                    }
                    default -> throw new GraphGatewayException(GraphGatewayException.Code.OPERATION_UNSUPPORTED);
                };
                return ResponseEntity.ok(codec.write(result));
            });
        } catch (GraphGatewayException failure) {
            return error(failure.code().status(), failure.code().name());
        } catch (Exception failure) {
            return error(503, "INTERNAL_FAILURE");
        }
    }
    private String authenticateWorker(HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        String worker = request.getHeader("X-Graph-Worker");
        if (header == null || header.length() > 300 || worker == null || !worker.matches("[A-Za-z0-9_.:-]{1,128}")
                || !MessageDigest.isEqual(("Bearer " + settings.workerToken()).getBytes(StandardCharsets.UTF_8), header.getBytes(StandardCharsets.UTF_8))) {
            throw new GraphGatewayException(GraphGatewayException.Code.AUTHORITY_DENIED);
        }
        return worker;
    }
    private Claim owned(Lease lease, String worker) {
        require(lease != null && lease.binding() != null && worker.equals(lease.workerId())
                && settings.artifactDigest().equals(lease.binding().graphArtifactDigest()));
        return lease.claim();
    }
    private ResponseEntity<byte[]> error(int status, String code) { return ResponseEntity.status(status).body(codec.write(Map.of("error_code", code))); }
}
