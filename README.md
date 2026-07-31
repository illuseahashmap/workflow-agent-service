# workflow-agent-service

Open workflow platform backend based on Spring Boot, Flowable and PostgreSQL.

## Baseline

- JDK 25
- Maven 3.9.x
- Spring Boot 4.x
- Flowable 8.0.0
- PostgreSQL

## Architecture

The project uses a lightweight DDD package structure.

```text
workflow-agent-service
├── pom.xml
├── workflow-engine     # Spring Boot application and Flowable integration
├── rules-engine        # reusable rule engine library
└── agent-engine        # reserved for future agent and LLM capabilities
```

`workflow-engine` uses lightweight DDD packages:

```text
io.github.illuseahashmap.workflow
├── shared
├── process
│   ├── interfaces
│   ├── application
│   └── infrastructure
├── tenant
├── security
└── config
```

Flowable is treated as infrastructure, not as the domain model. Application services expose workflow use cases and keep Flowable APIs behind the service boundary. Rules that should be reused by workflow and future agent capabilities belong in `rules-engine`.

## Security Contract

Service APIs use `X-Workflow-Token`. The token is the only tenant source for service calls.

The decrypted token payload must contain:

```json
{
  "clientCode": "local-dev",
  "tenantCode": "default",
  "timestamp": 1785484800,
  "nonce": "unique-request-nonce",
  "method": "POST",
  "path": "/workflow/process/start",
  "bodySha256": "sha256-hex-of-request-body",
  "tokenVersion": 1
}
```

Validation rules:

- decrypt token with AES-GCM using `workflow.security.master-key-base64`
- reject expired timestamps
- reject reused `clientCode + nonce`
- reject method, path or body hash mismatch
- reject disabled service clients
- resolve tenant only from `tenantCode` in token

`X-Tenant-Code` is intentionally not used by service APIs.

## BPMN XML Contract

BPMN XML is sent and returned as a plain JSON string. No Base64 transport field is used.

```json
{
  "processDefinitionKey": "leave_approval",
  "processDefinitionName": "Leave Approval",
  "bpmnXml": "<?xml version=\"1.0\" encoding=\"UTF-8\"?>..."
}
```
