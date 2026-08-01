# workflow-agent-service

Open workflow platform backend based on Spring Boot, Flowable, PostgreSQL, and Redis.

## Baseline

- JDK 25
- Maven 3.9.x
- Spring Boot 4.x
- Flowable 8.0.0
- PostgreSQL
- Redis
- Flyway

## Modules

```text
workflow-agent-service
|-- pom.xml
|-- shared-kernel    Shared response, exception, context, and principal contracts
|-- auth-engine      User login, registration, tenant membership, roles, menus, and permissions
|-- workflow-engine   Spring Boot application and Flowable adapter
|-- rules-engine      Reusable condition and priority rule engine
`-- agent-engine      Reserved for future agent and LLM capabilities
```

Module dependency direction:

```text
workflow-engine --> auth-engine --> shared-kernel
workflow-engine --> rules-engine
```

`workflow-engine` follows bounded-context-oriented lightweight DDD:

```text
io.github.illuseahashmap.workflow
|-- process
|   |-- interfaces.rest
|   |-- application
|   `-- infrastructure.flowable / infrastructure.lock
|-- assignment
|   |-- interfaces.rest
|   |-- application
|   |-- domain
|   `-- infrastructure.flowable / infrastructure.persistence
|-- tenant
|   |-- interfaces.rest
|   |-- application
|   |-- domain
|   `-- infrastructure.persistence
|-- security
|   |-- application.port
|   |-- domain
|   `-- infrastructure.persistence / infrastructure.token / infrastructure.web
`-- config
```

Flowable, JDBC, Redis, and servlet APIs remain in infrastructure adapters. Application contracts describe use cases, and domain packages contain workflow-independent concepts and repository ports.

`shared-kernel` contains dependency-light contracts that can be used by multiple engines, such as API responses, business exceptions, tenant context, and current principal models.

`auth-engine` is the long-term boundary for browser/user authentication and authorization: registration, login, tenant membership, roles, menus, and permission checks. The existing `workflow-engine.security.infrastructure.token` package remains the service-to-service token adapter until a dedicated gateway or service-auth module is introduced.

## Capabilities

- process deployment, version activation, definition queries, diagrams, and deletion
- process start, status, task approval, rejection, transfer, and automatic completion
- process instance paging, detail, variables, termination, and highlighted diagrams
- tenant management and Flowable tenant isolation
- conditional node assignment rules, historical-version inheritance, and assignee resolution
- per-client encrypted service tokens with tenant/path authorization, request binding, expiration, and replay protection

## Security Contract

Service APIs use one `X-Workflow-Token` header. The token envelope is:

```text
clientCode.base64Url(aesGcm(clientSecret, payloadJson))
```

The payload is:

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

Each client has an independent secret, allowed tenant codes, allowed paths, a secret version, and an optional expiration time. `X-Tenant-Code` is not accepted. BPMN XML is transported as a plain JSON string and is never Base64 encoded.

## Assignment expressions

Assignment rules are exposed to BPMN through the `assigneeService` bean:

- `${assigneeService.getAssignee(execution)}`
- `${assigneeService.getCandidates(execution)}`
- `${assigneeService.getCandidateGroups(execution)}`
- `${assigneeService.getAssigneeList(execution)}`

To apply `TO_ASSIGNEE`, `AUTO_COMPLETE`, or `AUTO_REJECT` when no handler is resolved, add a `create` task listener to the relevant user task with delegate expression `${assignmentFallbackTaskListener}`. Automatic completion and rejection run after the process transaction commits, preventing conflicts with timer boundary event persistence.

## Local Configuration

Required environment variables:

```text
WORKFLOW_LOCAL_DEV_SECRET=<local client secret>
WORKFLOW_MASTER_KEY_BASE64=<base64 encoded 256-bit key for database-encrypted client secrets>
```

Optional database variables:

```text
WORKFLOW_DB_URL=jdbc:postgresql://localhost:5432/workflow_agent
WORKFLOW_DB_USERNAME=workflow_agent
WORKFLOW_DB_PASSWORD=workflow_agent
```

Redis defaults to `localhost:6379`. Database migrations are managed by Flyway; Flowable manages its own engine tables.

## Build

```shell
mvn test
mvn package
```
