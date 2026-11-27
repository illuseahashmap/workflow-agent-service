package io.github.illuseahashmap.workflow.agent;

import io.github.illuseahashmap.agent.provider.application.port.ModelProviderException;
import io.github.illuseahashmap.agent.provider.application.port.ModelProviderRequest;

import java.nio.file.Path;
import java.util.Map;

/** Local synthetic acceptance diagnostics. Never persist request credentials or headers. */
final class P17ProviderFailureEvidence {
    private P17ProviderFailureEvidence() {}

    static void write(Path path, ModelProviderRequest request, ModelProviderException failure) {
        Throwable cause = failure.getCause();
        String diagnostic = cause == null ? failure.getMessage() : cause.getMessage();
        if (diagnostic == null) {
            diagnostic = "";
        }
        if (request.credential() != null && !request.credential().isBlank()) {
            diagnostic = diagnostic.replace(request.credential(), "[REDACTED]");
        }
        diagnostic = diagnostic.replaceAll("(?i)Bearer\\s+[^\\s\"']+", "Bearer [REDACTED]");
        if (diagnostic.length() > 1024) {
            diagnostic = diagnostic.substring(0, 1024);
        }
        try {
            P17Pilot.JSON
                    .writerWithDefaultPrettyPrinter()
                    .writeValue(
                            path.toFile(),
                            Map.of(
                                    "errorCode",
                                    failure.errorCode(),
                                    "diagnostic",
                                    diagnostic,
                                    "causeType",
                                    cause == null
                                            ? failure.getClass().getSimpleName()
                                            : cause.getClass().getSimpleName()));
        } catch (java.io.IOException invalid) {
            throw new java.io.UncheckedIOException(invalid);
        }
    }
}
