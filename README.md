# workflow-agent-service

Backend for Workflow Agent, an open-source, Java-first enterprise Human-Agent Workflow platform built on Flowable OSS.

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
|-- workflow-engine  Workflow domain, use cases, and Flowable adapters
|-- rules-engine      Reusable condition and priority rule engine
|-- agent-engine      Agent definitions, reliable runtime, providers, retrieval, and guardrails
|-- platform-migrations Ordered platform schema and cross-context integrity migrations
`-- workflow-boot    Executable application and module composition root
```

Module dependency direction:

```text
workflow-boot --> auth-engine --> shared-kernel
workflow-boot --> workflow-engine --> shared-kernel
workflow-boot --> platform-migrations
workflow-engine --> rules-engine
agent-engine --> shared-kernel
```

`auth-engine`, `workflow-engine`, and `agent-engine` follow bounded-context-oriented DDD. The executable application,
HTTP security chain, global exception translation, and Flyway composition live only in `workflow-boot`.
All ordered schema changes live in `platform-migrations`; business modules do not coordinate global Flyway versions.

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
|-- security
|   |-- application.port
|   |-- domain
|   `-- infrastructure.persistence / infrastructure.token / infrastructure.web
`-- config
```

Flowable, JDBC, Redis, and servlet APIs remain in infrastructure adapters. Application contracts describe use cases, and domain packages contain workflow-independent concepts and repository ports.

`shared-kernel` contains dependency-light contracts that can be used by multiple engines, such as API responses, business exceptions, tenant context, and current principal models.

`auth-engine` owns browser/user authentication, tenant lifecycle, membership, roles, menus, and permissions.
The existing `workflow-engine.security.infrastructure.token` package remains the service-to-service token adapter until a dedicated gateway or service-auth module is introduced.

## Documentation

- [Documentation index](docs/README.md)
- [Product positioning and goals](https://github.com/illuseahashmap/workflow-agent/blob/main/docs/product-positioning-and-goals.zh-CN.md)
- [Project status and next goals](docs/status.md)
- [Agent collaboration architecture](docs/architecture/agent-collaboration-design.md)
- [Agent MVP implementation plan](docs/architecture/agent-mvp-implementation-plan.md)
- [Backend architecture governance and roadmap](docs/quality/architecture-governance-and-roadmap.md)
- [Current issues and next remediation](docs/quality/known-issues.md)
- [OpenAPI contract](docs/api/openapi.yaml)
- [Agent learning knowledge base](docs/learning/agent-development-01-runtime-ledger-and-state-machine.md)

## Capabilities

- process deployment, version activation, definition queries, diagrams, and deletion
- process start, status, task approval, rejection, transfer, and automatic completion
- process instance paging, detail, variables, termination, and highlighted diagrams
- user registration and login with tenant-aware bearer tokens
- tenant membership, tenant switching, tenant-scoped roles and permission checks
- tenant management and Flowable tenant isolation
- conditional node assignment rules, historical-version inheritance, and assignee resolution
- per-client encrypted service tokens with tenant/path authorization, request binding, expiration, and replay protection

## Security Contract

Built-in browser roles follow this access model:

| Role | Access |
| --- | --- |
| `USER` | Read process definitions and process instances |
| `TENANT_ADMIN` | Manage members, roles, process definitions, instances, and assignment rules in the current tenant |
| `PLATFORM_ADMIN` | Full workflow access plus member, role, and tenant management |

Roles and permissions are tenant-scoped, except `PLATFORM_ADMIN`, which is a global platform role. Switching tenants reissues the browser token for the selected membership.
Public self-registration always creates a `USER` membership in the default tenant. Administrative roles can only be assigned by a platform administrator.
Built-in tenant roles are immutable. Tenant administrators can create custom tenant roles from the available permission catalog when business duties need to be separated further.

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

Start the local profile with PostgreSQL on `localhost:5432/workflow_agent`:

```powershell
$env:SPRING_PROFILES_ACTIVE='local'
$env:WORKFLOW_AUTH_TOKEN_SECRET='<at least 32 random characters>'
mvn -pl workflow-boot -am spring-boot:run
```

Required secrets:

```text
WORKFLOW_MASTER_KEY_BASE64=<base64 encoded 256-bit key for database-encrypted client secrets>
WORKFLOW_AUTH_TOKEN_SECRET=<at least 32 random characters; no built-in default>
```

Optional database variables:

```text
WORKFLOW_DB_URL=jdbc:postgresql://localhost:5432/workflow_agent
WORKFLOW_DB_USERNAME=postgres
WORKFLOW_DB_PASSWORD=root
# Optional: use a schema-owner account only for Flyway while the application
# runs with a least-privilege account. Defaults to the three values above.
WORKFLOW_MIGRATION_DB_URL=jdbc:postgresql://localhost:5432/workflow_agent
WORKFLOW_MIGRATION_DB_USERNAME=postgres
WORKFLOW_MIGRATION_DB_PASSWORD=root
```

Redis defaults to `localhost:6379`. Production configuration has no database credential defaults, disables Flyway baselining,
and disables Flowable schema auto-update. Database migrations are managed by Flyway; only the explicit `local` profile lets
Flowable update its own engine tables.

Agent Runtime tuning keeps execution and lease renewal independently bounded:

```text
WORKFLOW_AGENT_WORKER_EXECUTION_POOL_SIZE=2
WORKFLOW_AGENT_WORKER_HEARTBEAT_POOL_SIZE=4
WORKFLOW_AGENT_WORKER_HEARTBEAT_MAXIMUM_TRACKED_RUNS=1024
WORKFLOW_AGENT_WORKER_LEASE_SECONDS=60
WORKFLOW_AGENT_CHECKPOINT_MAXIMUM_CONTEXT_CHARACTERS=40000
WORKFLOW_AGENT_CHECKPOINT_MAXIMUM_INLINE_TOOL_RESULT_CHARACTERS=2048
```

For local Prometheus and Alertmanager validation, write a short-lived platform-admin Bearer token to a file outside Git,
set `WORKFLOW_PROMETHEUS_TOKEN_FILE` to its absolute path, and run:

```powershell
docker compose -f deploy/docker-compose.observability.yml up -d
```

The default Alertmanager receiver intentionally sends no external notification. Production deployments must additionally
set `WORKFLOW_ALERTMANAGER_WEBHOOK_URL_FILE` to a managed secret file and start with both compose files:

```powershell
docker compose -f deploy/docker-compose.observability.yml -f deploy/docker-compose.observability.production.yml up -d
```

Never commit the metrics token or webhook URL.

## Build

```shell
mvn test
mvn verify
```

`verify` enforces the JDK/Maven baseline, Java conventions, SpotBugs high-priority findings, and a minimum tested-code
coverage floor. ArchUnit tests prevent domain-to-framework, application-to-infrastructure, direct REST-to-domain,
and cross-bounded-context infrastructure dependencies. Docker-enabled environments additionally run PostgreSQL,
Redis, Flyway, Flowable, and HTTP security integration tests through Testcontainers. The parent build pins the
docker-java API compatibility floor to `1.44`, so Docker Engine 29 does not fall back to the legacy `1.32` API.
