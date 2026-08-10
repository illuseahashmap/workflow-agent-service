# API contract governance

`openapi.yaml` is the browser-facing contract baseline for the platform API.

Every public endpoint change must update the contract in the same change. CI
validates the document with Redocly. Removing a field, changing a field type,
making an optional field required, or removing an endpoint is a breaking change
and requires an explicit API version or migration note before release.

The contract is intentionally introduced incrementally. New endpoints must be
added before they are exposed to the web client; the remaining legacy endpoints
are tracked in `docs/quality/known-issues.md` and will be added as they are
stabilized.

CI also scans all production `@RestController` mappings and fails when a route
is not present in the bundled OpenAPI document. This keeps the contract
coverage complete while allowing response schemas to be refined incrementally.
