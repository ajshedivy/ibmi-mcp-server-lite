<!-- Research notes generated during initial development (June 2026). Facts were verified against the cited sources at that time; re-verify versions before relying on them. -->

I have everything needed. Below is the implementation contract.

---

# YAML Tool Configuration Contract — IBM i MCP Server (Node reference implementation)

Source root: `/Users/adamshedivy/Documents/IBM/sandbox/ai/mcp/ibmi-mcp-server/packages/server/src` (hereafter `src/`). All Zod schemas quoted verbatim.

## 1. Top-level YAML structure

Root schema: `SqlToolsConfigSchema` in `src/ibmi-mcp-server/schemas/config.ts` (lines 293–341):

```ts
export const SqlToolsConfigSchema = z
  .object({
    sources: z.record(z.string().min(1, "Source name cannot be empty"), SourceConfigSchema).optional(),
    tools: z.record(z.string().min(1, "Tool name cannot be empty"), SqlToolConfigSchema).optional(),
    toolsets: z.record(z.string().min(1, "Toolset name cannot be empty"), SqlToolsetConfigSchema).optional(),
    typescript_tools: z.record(z.string().min(1), TypeScriptToolConfigSchema).optional(),
    metadata: MetadataSchema.optional(),
  })
  .refine((data) => data.sources || data.tools || data.toolsets || data.typescript_tools,
    { message: "YAML file must contain at least one section: sources, tools, toolsets, or typescript_tools" })
```

All four sections are optional maps (name → object), but at least one section must exist. `MetadataSchema = z.record(z.unknown())` (`schemas/common.ts:20-22`).

### 1a. `sources.<name>` — `SourceConfigSchema` (`schemas/config.ts:75-129`)

| Field | Type | Required | Notes |
|---|---|---|---|
| `host` | string, min 1 | **required** | |
| `user` | string, min 1 | **required** | |
| `password` | string, min 1 | **required** | |
| `port` | int > 0 | optional | doc says "default: 8471 for IBM i"; passed through as-is to mapepire (logged as `sourceConfig.port \|\| 8471`, `services/sourceManager.ts:77`) |
| `ignore-unauthorized` | boolean | optional | maps to mapepire `ignoreUnauthorized` (`sourceManager.ts:105`) |
| `jdbc-options` | object, `.passthrough()` | optional | any mapepire JDBCOption forwarded unvalidated. `libraries` is special: `z.union([z.array(z.string().min(1)), z.string().transform(csv→string[])])` — accepts array **or** comma-separated string. Env var `DB2i_JDBC_OPTIONS` (semicolon-separated `key=value;...`, `libraries` comma-split — parser at `src/config/index.ts:542-574`) is shallow-merged **over** YAML per source (env wins; `sourceManager.ts:90-97`). |

Extra keys at the source level (outside `jdbc-options`) are stripped by Zod (plain `z.object`, not passthrough).

Cross-validation: every `tools.<n>.source` must name an existing key in `sources` — error `"Tool 'X' references unknown source 'Y'"` (`utils/config/configParser.ts:321-342`; re-checked post-merge in `toolConfigBuilder.ts:941-950` and `toolProcessor.ts:485-491`).

### 1b. `tools.<name>` — `SqlToolConfigSchema` (`schemas/config.ts:157-247`)

| Field | Type | Required | Default |
|---|---|---|---|
| `enabled` | boolean | optional | `true` (`.default(true)`) |
| `source` | string min 1 | **required** | — |
| `description` | string min 1 | **required** | — |
| `statement` | string min 1 | optional in schema, **but enforced required** post-parse: `validateToolRequirements` errors `"Tool 'X' must have a non-empty statement field"` (`configParser.ts:350-366`) | — |
| `parameters` | array of `SqlToolParameterSchema` | optional | — |
| `domain` | string | optional | — |
| `category` | string | optional | — |
| `metadata` | record(unknown) | optional | merged into `annotations.customMetadata` |
| `responseFormat` | `enum ["json","markdown"]` (`schemas/common.ts:13-15`) | optional | json |
| `annotations` | `ToolAnnotationsSchema` | optional | — |
| `security` | `SqlToolSecurityConfigSchema` | optional | — |
| `tableFormat` | `enum ["markdown","ascii","grid","compact"]` | optional | `"markdown"` |
| `maxDisplayRows` | int 1..1000 | optional | `100` |
| `rowsToFetch` | int ≥ 1 | optional | mapepire default (100); takes precedence over `fetchAllRows` |
| `fetchAllRows` | boolean | optional | paginated fetch-all (safety cap ~30k rows); ignored if `rowsToFetch` set |
| `readOnlyHint`, `destructiveHint`, `idempotentHint`, `openWorldHint` | boolean | optional, **deprecated** legacy top-level hints | folded into annotations (see §7) |

### 1c. `toolsets.<name>` — `SqlToolsetConfigSchema` (`schemas/config.ts:253-263`)

```ts
z.object({
  title: z.string().optional(),
  description: z.string().optional(),
  tools: z.array(z.string().min(1, "Tool name cannot be empty"))
         .min(1, "Toolset must contain at least one tool"),
  metadata: MetadataSchema.optional(),
})
```

### 1d. `typescript_tools.<name>` — `TypeScriptToolConfigSchema` (`schemas/config.ts:269-287`)

`domain` (string, required), `category` (string, required), `description?`, `toolsets?: string[]`, `global?: boolean`. Used only to assign built-in TypeScript tools to toolsets; a YAML loader for SQL tools may parse-and-ignore.

### 1e. Multi-file merge

Loader accepts a file, directory (glob `**/*.{yaml,yml}`), or glob (`toolConfigBuilder.ts:706-750`; path-type detection in `toolProcessor.ts:418-444`). Files are merged (`toolConfigBuilder.ts:792-930`) with options from env (`src/config/index.ts:277-297`): `YAML_MERGE_ARRAYS` (default true → duplicate toolset names concatenate their `tools` arrays; false → replace), `YAML_ALLOW_DUPLICATE_TOOLS` (default false → duplicate tool name across files is a hard error), `YAML_ALLOW_DUPLICATE_SOURCES` (default false → hard error), `YAML_VALIDATE_MERGED` (default true → post-merge referential checks: tool→source and toolset→tool existence).

## 2. Environment-variable interpolation

`ConfigParser.interpolateEnvironmentVariables` (`configParser.ts:231-257`): applied to the **raw file text before YAML parsing**, once at load time, using server-side `process.env`:

```ts
return content.replace(/\$\{([^}]+)\}/g, (match, varName) => {
  const envValue = process.env[varName];
  if (envValue === undefined) { /* debug log */ return match; }  // placeholder kept verbatim
  return envValue;
});
```

- Syntax: `${VAR_NAME}` — anything except `}` between braces; no default-value syntax (`${VAR:-x}` would look up the literal key `VAR:-x`).
- **Missing variable: NOT an error** — the literal `${VAR}` string is left in place (debug-logged). For a `sources` field this then typically fails connection, not parsing.
- Because substitution is textual pre-parse, values containing YAML-significant characters are spliced raw into the document.

## 3. Parameter definitions → JSON Schema

`SqlToolParameterSchema` (`schemas/config.ts:19-69`), verbatim:

```ts
export const SqlToolParameterSchema = z.object({
  name: z.string().min(1, "Parameter name cannot be empty"),
  type: z.enum(["string", "boolean", "integer", "float", "array"]),
  description: z.string().optional(),
  default: z.union([z.string(), z.number(), z.boolean(), z.array(z.unknown()), z.null()]).optional(),
  required: z.boolean().optional(),
  itemType: z.enum(["string", "boolean", "integer", "float"]).optional(), // arrays only
  min: z.number().optional(),       // numeric types
  max: z.number().optional(),       // numeric types
  minLength: z.number().optional(), // string length OR array item count
  maxLength: z.number().optional(),
  enum: z.array(z.union([z.string(), z.number(), z.boolean()])).optional(),
  pattern: z.string().optional(),   // regex, strings only
})
```

MCP `inputSchema` generation: `ToolConfigBuilder.generateZodSchema` (`toolConfigBuilder.ts:79-272`). The resulting object is `z.object(shape).strict()` → JSON Schema `additionalProperties: false`. Per type:

- `string` → `{type:"string"}` + `minLength`/`maxLength`/`pattern` (Zod `.min/.max/.regex`).
- `integer` → `z.number().int()` → `{type:"integer"}` + `minimum`(`min`)/`maximum`(`max`).
- `float` → `z.number()` → `{type:"number"}` + `minimum`/`maximum`.
- `boolean` → `{type:"boolean"}` (enum ignored for booleans).
- `array` → `{type:"array", items: <itemType>}`; `itemType` default when absent: `z.unknown()` in the schema (runtime binding defaults items to `"string"`, `parameterProcessor.ts:627`); `minLength`/`maxLength` → `minItems`/`maxItems`.
- `enum` (non-boolean): 1 value → `z.literal` (`const`); all-strings → `z.enum` (`enum: [...]`); mixed/numeric → union of literals (`anyOf` of `const`s). The base type's other constraints are **replaced** by the enum (lines 196-223).
- Description: `param.description`, with `" Must be one of: 'a', 'b'"` appended when enum present (lines 226-251); strings quoted, terminal punctuation added.
- `default` → `.default(value)` (applied last → JSON Schema `default`; the field becomes non-required and is auto-filled on omission).
- Optionality rule (lines 263-266): field is **optional only if `required === false` AND no `default`**. Therefore omitting `required` makes the parameter required in the input schema (unless it has a `default`).

Runtime re-validation/coercion (second layer, `utils/sql/parameterProcessor.ts:288-678`): string accepts number/boolean with warning; integer accepts numeric strings (`parseInt`), floors floats, boolean→1/0; float via `parseFloat`; boolean accepts `"true"/"1"/"yes"/"on"` / `"false"/"0"/"no"/"off"` (case-insensitive) and numbers (`!==0`), **and is converted to `1`/`0` for DB2 binding** (line 590); arrays validate each item against `itemType` (default `"string"`); enum checked after conversion (array values element-wise). Missing value: `required && default===undefined` → ValidationError; `default` present → default validated/used; otherwise **bound as empty string `""`** (lines 300-317).

## 4. SQL parameter binding (`:param`)

`ParameterProcessor` (`utils/sql/parameterProcessor.ts`) + `SQLToolFactory.executeStatementWithParameters` (`utils/config/toolFactory.ts:59-256`):

1. **Mode detection** (`detectParameterMode`, lines 685-703): `:(\w+)` → named; `?` → positional; both → hybrid (named processed first); `{{param}}` → **error** ("Template mode … is deprecated"). Note the naive regex matches `:word` anywhere, including inside string literals.
2. **Syntax validation** (`validateSqlSyntax`, lines 1039-1069): `:(\d)` (named param starting with digit) → error; unmatched single or double quote counts → error.
3. **Named → positional conversion** (`processNamedParameters`, lines 713-829): every occurrence of `:name` is replaced in-order by `?`, and the converted value pushed onto the `parameters` array (values may repeat if `:name` appears multiple times). It is a **parameterized query**: the processed SQL plus ordered `parameters: BindingValue[]` (`string | number | (string|number)[]`) is handed to mapepire via `pool.query(sql, { parameters: params })` then `queryObj.execute(rowsToFetch?)` (`services/baseConnectionPool.ts:512-522`). **No client-side literal substitution** (except the special case below).
4. **Array binding for `IN (:list)`** (lines 768-797): an array value expands `:param` into `?, ?, ?` (comma-space-joined, one `?` per element) and pushes each element individually. The YAML statement must supply its own parentheses, e.g. `IN (:names)`.
5. **Missing named param**: placeholder is recorded in `missingParameters`, warned, and **left as literal `:name` in the SQL** (will fail at the database). Defaults/optional `""` from §3 normally prevent this for declared params.
6. **Positional mode** (lines 839-913): values taken by zero-based stringified index keys (`"0"`,`"1"`,…) if present, else by `Object.keys` insertion order; null/undefined values are skipped (counted missing).
7. **Booleans → 1/0**; null/undefined → ValidationError under strict typing, `""` otherwise (`convertToBindingValue`, lines 975-1032).
8. **Direct-substitution special case** (`toolFactory.ts:103-134`): if a tool has exactly one parameter definition and the trimmed statement is exactly `:<thatParamName>`, the (string) parameter value **becomes the entire SQL text** (used by `execute_sql`); binding array is empty.
9. If a tool has **zero** parameter definitions, the statement is sent as-is, no processing (lines 171-182).
10. Runtime security validation of the *processed* SQL happens before execution **only when the tool has a `security` block** (`toolFactory.ts:188-194`); load-time validation always runs (§6).
11. Result metadata `parameterMetadata.mode` is `"parameters"` when definitions exist, else `"none"` (`toolFactory.ts:244`).

## 5. Toolset semantics

- Toolsets are pure groupings: `toolsets.<name>.tools: [tool_name, ...]`. Membership is computed by scanning all toolsets per tool (`toolProcessor.ts:493-503`); a tool may be in many toolsets, and membership is injected into `annotations.toolsets` (any user-supplied `annotations.toolsets` is ignored with a warning — `toolConfigBuilder.ts:502-517`).
- Each toolset implicitly also contains `GLOBAL_TOOLS = [generateSqlTool.name]` (TypeScript tool, `utils/config/toolsetManager.ts:24-26,125`).
- Toolset → tool references are validated post-merge: unknown tool name → error (`toolsetManager.ts:139-155`; also `toolConfigBuilder.ts:953-966`).
- **Selection**: `--toolsets a,b` CLI flag (alias `-ts`, comma-split/trimmed — `utils/cli/argumentParser.ts:69-83`) or env `SELECTED_TOOLSETS` (comma-split — `src/config/index.ts:336,708-709`; CLI wins, `src/config/resolver.ts:42,75-77`). When non-empty, `ToolConfigBuilder.filterToolsByToolsets` (`toolConfigBuilder.ts:280-295`) keeps only tools whose membership intersects the selected set — tools in **no** toolset are dropped when a filter is active. No filter → all tools registered.
- Tools path: `--tools <path>` (file | directory | glob) or env `TOOLS_YAML_PATH` (CLI wins). `--list-toolsets` prints toolsets and exits. Built-in TS tools gated by `--builtin-tools`/`IBMI_ENABLE_DEFAULT_TOOLS` and `--execute-sql`/`IBMI_ENABLE_EXECUTE_SQL` (separate from YAML tools).
- `YAML_AUTO_RELOAD` (default true) watches resolved YAML files and rebuilds/re-registers on change (`toolProcessor.ts:608-649`, `ibmi-mcp-server/index.ts:133-203`).

## 6. `security` block & SqlSecurityValidator

`SqlToolSecurityConfigSchema` (`schemas/config.ts:134-151`):

```ts
z.object({
  readOnly: z.boolean().optional(),          // default true
  maxQueryLength: z.number().optional(),     // default 10000 chars
  forbiddenKeywords: z.array(z.string()).optional(), // additive
})
```

Validation (`utils/security/sqlSecurityValidator.ts:178-210`), in order:
1. **Length**: `query.length > (maxQueryLength ?? 10000)` → ValidationError.
2. **forbiddenKeywords** (always, regardless of readOnly): token-based check using the vscode-db2i tokenizer (string literals skipped; case-insensitive exact token match, lines 142-169); on tokenizer failure, regex fallback `\b<kw>\b` case-insensitive after stripping `'...'` literals (`sqlSecurityValidatorFallback.ts:122-141`).
3. **Read-only** (when `readOnly !== false`, i.e. default on): primary path parses with the vscode-db2i `Document` parser; **only `StatementType.Select` and `StatementType.With` are read-only** — every other statement type (Insert, Update, Delete, Create, Drop, Alter, Call, Set, Declare, Merge, …; full enum at `utils/language/types.ts:6-41`) is a violation (`utils/security/ibmiSqlParser.ts:24,121-126`). If parsing fails, regex fallback scans (literals stripped) for `DANGEROUS_OPERATIONS` word-boundary matches — full list (`sqlSecurityValidator.ts:31-78`): INSERT, UPDATE, DELETE, MERGE, TRUNCATE, DROP, CREATE, ALTER, RENAME, CALL, EXEC, EXECUTE, SET, DECLARE, GRANT, REVOKE, DENY, LOAD, IMPORT, EXPORT, BULK, SHUTDOWN, RESTART, KILL, STOP, START, BACKUP, RESTORE, DUMP, LOCK, UNLOCK, COMMIT, ROLLBACK, SAVEPOINT, QCMDEXC, SQL_EXECUTE_IMMEDIATE — plus `DANGEROUS_PATTERNS`: `/;\s*(DROP|DELETE|INSERT|UPDATE|CREATE|ALTER)/i`, `/\bUNION\s+(ALL\s+)?\s*\(\s*(DROP|DELETE|INSERT|UPDATE)/i`, `/\bREPLACE\s+INTO\b/i`.

When applied:
- **Load time (fail startup)**: every enabled tool's statement is validated with effective config `{readOnly: tool.security?.readOnly ?? true, maxQueryLength: tool.security?.maxQueryLength ?? 10000, forbiddenKeywords: tool.security?.forbiddenKeywords}` (`configParser.ts:376-431`). So readOnly defaults to **true even without a security block**. Disabled tools are skipped.
- **Execution time**: validated again on the *processed* SQL only if the tool's YAML has a `security` block (`toolFactory.ts:188-194`), and once more inside the pool when securityConfig is passed (`baseConnectionPool.ts:503-510`).

All violations throw `McpError(JsonRpcErrorCode.ValidationError, ...)` with `{violations, query: truncated@100}` details.

## 7. `annotations` block

`ToolAnnotationsSchema` (`schemas/common.ts:27-99`) — `z.object({ title?, readOnlyHint?, openWorldHint?, idempotentHint?, destructiveHint?, domain?, category?, toolsets?, customMetadata? }).catchall(z.unknown())` — i.e. **arbitrary extra keys are preserved** (catchall) and passed straight through to MCP tool registration (`toolProcessor.ts:255-263` passes `annotations: config.annotations`).

Resolution (`ToolConfigBuilder.buildAnnotations`, `toolConfigBuilder.ts:488-545`):
- `title`: `annotations.title ?? formatToolTitle(name)` (name split on `[_-]`, words capitalized, space-joined — line 569-574).
- `readOnlyHint`: `annotations.readOnlyHint ?? legacy top-level readOnlyHint ?? security.readOnly ?? true`.
- `openWorldHint`/`idempotentHint`/`destructiveHint`: `annotations.X ?? legacy top-level X` (no default).
- `domain`/`category`: `annotations.X ?? tool-level X` — these are **custom (non-MCP-spec) keys kept in the annotations object** for client-side filtering.
- `toolsets`: always overwritten with derived membership (user value ignored + warning).
- `customMetadata`: shallow merge of `annotations.customMetadata` then tool-level `metadata` (tool metadata wins; lines 547-561).

MCP-spec annotation keys are `title`, `readOnlyHint`, `destructiveHint`, `idempotentHint`, `openWorldHint`; `domain`, `category`, `toolsets`, `customMetadata` ride along as extra annotation properties.

## 8. `responseFormat` & result shape

`ResponseFormatSchema = z.enum(["json","markdown"])` (`schemas/common.ts:13`). Selection in `getResponseFormatter` (`toolConfigBuilder.ts:472-486`): `"markdown"` → `sqlResponseFormatter` (with `tableFormat`, `maxDisplayRows`); anything else/absent → `defaultResponseFormatter` = single text block `JSON.stringify(output, null, 2)` (`toolDefinitions.ts:331-333`).

Success result (`createHandlerFromDefinition`, `toolDefinitions.ts:340-374`):

```ts
{ content: ContentBlock[],         // formatter output (text blocks)
  structuredContent: output }      // validated StandardSqlToolOutput
```

`StandardSqlToolOutputSchema` (`schemas/tools.ts:15-86`, `.strict()`):

```ts
{ success: boolean,
  data: Array<Record<string, any>>,            // rows
  error?: string, errorCode?: string|number, errorDetails?: unknown,
  metadata?: {
    executionTime?: number,                    // ms (wall-clock, toolFactory.ts:206)
    rowCount?: number, affectedRows?: number,
    columns?: Array<{name: string, type?: string, label?: string}>,
    parameterMode?: string,                    // "parameters" | "none"
    parameterCount?: number, processedParameters?: string[],
    toolName?: string, sqlStatement?: string,  // the raw YAML statement
    parameters?: Record<string, unknown> } }   // validated input params
```

This schema is also registered as the MCP `outputSchema` (`toolProcessor.ts:261`). Markdown formatter output (`toolDefinitions.ts:128-325`): H2 tool name, success alert, row count, SQL code block (truncated @500), parameters list, results table (style per `tableFormat`, rows capped at `maxDisplayRows` with truncation note, NULL→`-`, cell maxWidth 50), and a Summary section (total rows, columns, execution time, NULL counts, affected rows, parameter count).

Error result (handler catch, `toolDefinitions.ts:395-410`): `{ isError: true, content: [{type:"text", text: "Error executing '<name>': <msg>"}], structuredContent: { success: false, data: [], error, errorCode, errorDetails } }`.

## 9. Naming & `enabled`

- Tool/source/toolset names are the YAML map keys; only constraint is non-empty (`min(1)`). Convention (root `CLAUDE.md`): snake_case tool names. Title auto-derived as in §7. Duplicate names across merged files: error unless `YAML_ALLOW_DUPLICATE_TOOLS`/`_SOURCES` (then last-loaded wins with warning).
- `enabled` defaults to `true`. `enabled: false` semantics in the reference implementation: tool is excluded from `ConfigParser.processTools` output and from **load-time security validation** (`configParser.ts:387-391,461-472`) and counted in `disabledToolCount` stats. **Caveat (observed bug in Node impl)**: the live registration path `ToolProcessor.processYamlTools` (`toolProcessor.ts:473-521`) iterates the merged config without re-checking `enabled`, so disabled tools currently still get registered by the server. A compatible Java implementation should honor the documented intent — `enabled: false` ⇒ skip security validation **and do not register** — matching `ConfigParser` semantics.

### Supplementary runtime facts a Java port must mirror
- Statements execute via mapepire (`@ibm/mapepire-js`) parameterized queries: `pool.query(sql, {parameters})`, `execute(rowsToFetch?)`; pagination path uses `execute()` + repeated `fetchMore(fetchSize)` capped ~30k rows (`baseConnectionPool.ts:579-674`).
- `BindingValue = string | number | (string|number)[]`; binding values that are not string/number (post-conversion) are warned at the pool layer (`baseConnectionPool.ts:476-501`).
- Key env vars: `TOOLS_YAML_PATH`, `SELECTED_TOOLSETS`, `YAML_MERGE_ARRAYS`(true), `YAML_ALLOW_DUPLICATE_TOOLS`(false), `YAML_ALLOW_DUPLICATE_SOURCES`(false), `YAML_VALIDATE_MERGED`(true), `YAML_AUTO_RELOAD`(true), `DB2i_JDBC_OPTIONS` (`src/config/index.ts:264-336`).