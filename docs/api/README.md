# API contract governance

`openapi.yaml` is the browser-facing contract baseline for the platform API.

Every public endpoint change must update the contract in the same change. CI
validates the document with Redocly. Removing a field, changing a field type,
making an optional field required, or removing an endpoint is a breaking change
and requires an explicit API version or migration note before release.

The contract covers every public route. New endpoints must be added before they
are exposed to the web client, and their successful responses must declare the
concrete `data` model used for client generation.

CI also scans all production `@RestController` mappings and fails when a route
is not present in the bundled OpenAPI document. Successful JSON responses must
reference endpoint-specific response models; direct use of an untyped
`ApiResponse` is rejected so generated clients never degrade to `data: any`.
