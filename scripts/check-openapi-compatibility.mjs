import fs from 'node:fs';

const [basePath, currentPath] = process.argv.slice(2);
if (!basePath || !currentPath) {
  console.error('Usage: node scripts/check-openapi-compatibility.mjs <base.json> <current.json>');
  process.exit(2);
}

const base = JSON.parse(fs.readFileSync(basePath, 'utf8'));
const current = JSON.parse(fs.readFileSync(currentPath, 'utf8'));
const failures = [];

const methods = new Set(['get', 'post', 'put', 'patch', 'delete', 'options', 'head', 'trace']);
const basePaths = base.paths ?? {};
const currentPaths = current.paths ?? {};

for (const path of Object.keys(basePaths)) {
  if (!(path in currentPaths)) {
    failures.push(`removed path: ${path}`);
    continue;
  }
  for (const method of Object.keys(basePaths[path])) {
    if (!methods.has(method)) {
      continue;
    }
    if (!(method in currentPaths[path])) {
      failures.push(`removed operation: ${method.toUpperCase()} ${path}`);
      continue;
    }
    compareOperation(basePaths[path][method], currentPaths[path][method], `${method.toUpperCase()} ${path}`);
  }
}

const baseSchemas = base.components?.schemas ?? {};
const currentSchemas = current.components?.schemas ?? {};
for (const [name, schema] of Object.entries(baseSchemas)) {
  if (!(name in currentSchemas)) {
    failures.push(`removed schema: ${name}`);
    continue;
  }
  compareSchema(schema, currentSchemas[name], `schema ${name}`);
}

if (failures.length > 0) {
  console.error('OpenAPI compatibility check failed:');
  for (const failure of failures) {
    console.error(`- ${failure}`);
  }
  process.exit(1);
}
console.log('OpenAPI compatibility check passed.');

function compareOperation(baseOperation, currentOperation, location) {
  const baseParameters = new Map((baseOperation.parameters ?? [])
    .map(parameter => [`${parameter.in}:${parameter.name}`, parameter]));
  const currentParameters = new Map((currentOperation.parameters ?? [])
    .map(parameter => [`${parameter.in}:${parameter.name}`, parameter]));
  for (const [key, parameter] of baseParameters) {
    if (!currentParameters.has(key)) {
      failures.push(`removed parameter: ${location} ${key}`);
      continue;
    }
    if (parameter.required !== true && currentParameters.get(key).required === true) {
      failures.push(`parameter became required: ${location} ${key}`);
    }
  }
  const baseBody = baseOperation.requestBody;
  const currentBody = currentOperation.requestBody;
  if (baseBody && !currentBody) {
    failures.push(`removed request body: ${location}`);
  } else if (baseBody && currentBody) {
    if (baseBody.required !== true && currentBody.required === true) {
      failures.push(`request body became required: ${location}`);
    }
    compareContent(baseBody.content, currentBody.content, `${location} request body`);
  }
  const baseResponses = baseOperation.responses ?? {};
  const currentResponses = currentOperation.responses ?? {};
  for (const [status, response] of Object.entries(baseResponses)) {
    if (!(status in currentResponses)) {
      failures.push(`removed response: ${location} ${status}`);
      continue;
    }
    compareContent(response.content, currentResponses[status].content, `${location} response ${status}`);
  }
}

function compareContent(baseContent, currentContent, location) {
  if (!baseContent) {
    return;
  }
  if (!currentContent) {
    failures.push(`removed response content: ${location}`);
    return;
  }
  for (const [mediaType, media] of Object.entries(baseContent)) {
    if (!(mediaType in currentContent)) {
      failures.push(`removed media type: ${location} ${mediaType}`);
      continue;
    }
    if (media.schema && currentContent[mediaType].schema) {
      compareSchema(media.schema, currentContent[mediaType].schema, `${location} ${mediaType}`);
    }
  }
}

function compareSchema(baseSchema, currentSchema, location) {
  if (!baseSchema || !currentSchema || baseSchema.$ref || currentSchema.$ref) {
    return;
  }
  if (baseSchema.type && currentSchema.type && baseSchema.type !== currentSchema.type) {
    failures.push(`schema type changed: ${location}`);
  }
  const baseRequired = new Set(baseSchema.required ?? []);
  const currentRequired = new Set(currentSchema.required ?? []);
  for (const name of currentRequired) {
    if (!baseRequired.has(name)) {
      failures.push(`field became required: ${location}.${name}`);
    }
  }
  for (const name of Object.keys(baseSchema.properties ?? {})) {
    if (!(name in (currentSchema.properties ?? {}))) {
      failures.push(`removed field: ${location}.${name}`);
      continue;
    }
    compareSchema(baseSchema.properties[name], currentSchema.properties[name], `${location}.${name}`);
  }
  if (baseSchema.enum && currentSchema.enum) {
    for (const value of baseSchema.enum) {
      if (!currentSchema.enum.includes(value)) {
        failures.push(`removed enum value: ${location}=${value}`);
      }
    }
  }
}
