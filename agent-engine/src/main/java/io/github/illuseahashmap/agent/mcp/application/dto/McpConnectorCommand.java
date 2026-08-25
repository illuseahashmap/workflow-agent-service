package io.github.illuseahashmap.agent.mcp.application.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record McpConnectorCommand(
        @NotBlank @Size(max = 128) String connectorCode,
        @NotBlank @Size(max = 255) String connectorName,
        @NotBlank @Pattern(regexp = "https://.*", message = "MCP endpoint must use HTTPS")
        @Size(max = 2048) String endpointUrl,
        @Size(max = 64) String protocolVersion,
        @Size(max = 255) String credentialRef,
        @Min(1) @Max(300) Integer timeoutSeconds
) {
}
