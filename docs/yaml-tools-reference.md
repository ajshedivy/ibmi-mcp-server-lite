# YAML tools reference

This server implements a subset of the
[IBM i MCP Server](https://github.com/IBM/ibmi-mcp-server) YAML tool schema. A YAML file
has up to three sections; at least one must be present. Fields marked *(parsed, ignored)*
are accepted for compatibility but not yet implemented — see
[roadmap.md](roadmap.md).

## Environment interpolation

`${VAR}` anywhere in the file is replaced from the environment **before** YAML parsing.
A `.env` file in the working directory (or `--env-file`) is read first; real process
environment variables override it. Unknown variables are left verbatim (`${NOPE}` stays
`${NOPE}`) — they fail at connection time, not parse time, matching the reference server.

## `sources` — Mapepire connections

```yaml
sources:
  ibmi-system:
    host: ${DB2i_HOST}        # required
    user: ${DB2i_USER}        # required
    password: ${DB2i_PASS}    # required
    port: 8076                # optional, default 8076
    ignore-unauthorized: true # optional, default false — skip TLS *chain* validation
    max-size: 10              # optional, default 10 — max SqlJobs in the connection pool
    starting-size: 2          # optional, default 2 — jobs created at pool init
    jdbc-options:             # optional — forwarded to mapepire JDBC driver
      libraries: [MYLIB, DEVDATA]  # array or comma-separated string
      naming: system               # any other JDBC option passes through
```

> `DB2i_JDBC_OPTIONS` env var (semicolon-separated `key=value;...`) shallow-merges over
> `jdbc-options` per source — env wins on key collisions. See `.env.example`.

> mapepire-java still verifies the TLS **hostname** against the server certificate's SAN
> even with `ignore-unauthorized: true`. Use a `host` that appears in the certificate.

## `tools` — parameterized SQL

```yaml
tools:
  get_employee:
    source: ibmi-system            # required, must name a sources entry
    description: "LLM-facing text" # required
    statement: |                   # required for enabled tools
      SELECT * FROM SAMPLE.EMPLOYEE WHERE EMPNO = :employee_id
    enabled: true                  # default true; false = don't register
    rowsToFetch: 100               # max rows returned (default 100)
    responseFormat: json           # json | markdown (default json)
    tableFormat: markdown          # markdown | ascii | grid | compact (default markdown)
    maxDisplayRows: 100            # rows shown in markdown tables (default 100)
    parameters: [...]              # see below
    security: {...}                # see below
    annotations: {...}             # see below
```

*(parsed, ignored)*: `domain`, `category`.

### `responseFormat`, `tableFormat`, `maxDisplayRows`

Controls how the tool's **text content block** is rendered. The MCP
`structuredContent` payload (`{success, data, metadata}`) is always the same JSON
shape regardless of format.

| Field | Values | Default | Effect |
|---|---|---|---|
| `responseFormat` | `json`, `markdown` | `json` | `json` — pretty-printed JSON text block (reference default). `markdown` — human-readable document with tool header, SQL, parameters, results table, and summary. |
| `tableFormat` | `markdown`, `ascii`, `grid`, `compact` | `markdown` | Table style when `responseFormat: markdown`. Only affects the text block. |
| `maxDisplayRows` | integer | `100` | Max rows included in the markdown results table. When fewer rows are shown than were fetched, a truncation note is appended. Does not change how many rows are fetched — use `rowsToFetch` for that. |

Markdown output includes: an H2 tool name, success alert, row-count paragraph, SQL
statement (truncated at 500 characters), bound parameters (when present), a typed
results table (column headers show `NAME (TYPE)` with precision stripped; NULL cells
render as `-`; cell values truncate at 50 characters), and a summary list (row count,
columns, execution time, NULL counts, affected rows, parameter count when applicable).
Numeric DB2 types are right-aligned in the table; text and temporal types are
left-aligned.

### `parameters`

```yaml
parameters:
  - name: employee_id      # required
    type: string           # required: string | integer | float | boolean | array
    description: "..."     # optional, shown to the LLM
    required: true         # see optionality rule below
    default: "000010"      # makes the parameter optional; used when omitted
    enum: ["A00", "B01"]   # allowed values
    pattern: "^[0-9]{6}$"  # strings: regex (emitted into the JSON Schema)
    min: 1                 # integer/float: minimum
    max: 100               # integer/float: maximum
    minLength: 2           # strings: length; arrays: minItems
    maxLength: 10          # strings: length; arrays: maxItems
    itemType: string       # arrays: element type (default string)
```

**Enum → JSON Schema** (non-boolean parameters only; `enum` replaces other type
constraints such as `pattern`, `min`/`max`, and length limits):

| `enum` values | Emitted schema |
|---|---|
| one value | `{"const": value}` |
| all strings | `{"type":"string","enum":[...]}` |
| numbers and/or mixed types | `{"anyOf":[{"const":v1},{"const":v2},...]}` |

When `enum` is present, the parameter `description` sent to the LLM includes a
`Must be one of: ...` suffix (string values single-quoted, others bare). If a
description already exists, a period is added when it does not already end in
`.`, `?`, or `!`.

**Optionality rule** (matches the reference server): a parameter is *required* unless
`required: false` is set **or** a `default` is provided. An optional parameter with no
default binds as an empty string when omitted.

**Binding semantics:**

- `:name` placeholders are converted to `?` markers and executed as a Mapepire
  parameterized query — values are never concatenated into SQL text.
- A `:name` used twice binds its value once per occurrence.
- An `array` value expands to `?, ?, ?` (one marker per element): write `IN (:ids)`
  with the parentheses in your statement.
- `boolean` values bind as `1`/`0` (Db2 convention). Numeric strings are coerced for
  `integer`/`float` parameters.
- A placeholder with no matching parameter definition is left verbatim and will fail at
  the database — declare every placeholder.

### `security`

```yaml
security:
  readOnly: true             # default true even when this block is absent
  maxQueryLength: 10000      # default 10000 characters
  forbiddenKeywords: [DROP]  # extra keywords rejected (word-boundary, literals ignored)
```

With `readOnly` in effect (the default), only statements starting with `SELECT` or
`WITH` pass, and a dangerous-keyword scan (INSERT/UPDATE/DELETE/DROP/CALL/…) runs with
string literals stripped. Validation happens at startup for every registered tool, and
again at call time (on the processed SQL) for tools that declare a `security` block.
Set `security.readOnly: false` explicitly to allow data-changing statements.

### `annotations`

```yaml
annotations:
  title: "Get Employee"   # default: derived from the tool name (get_employee → Get Employee)
  readOnlyHint: true      # default: security.readOnly, else true
  idempotentHint: true
  destructiveHint: false
  openWorldHint: false
```

These map to MCP tool annotations. Custom keys (`domain`, `category`, …) are accepted in
the YAML but not forwarded — the Java SDK's `ToolAnnotations` is a closed record.

## `toolsets` — groupings

```yaml
toolsets:
  performance:
    title: "Performance"        # optional
    description: "..."          # optional
    tools: [system_status, ...] # required, non-empty; names must exist in tools
```

A tool may belong to any number of toolsets. With `--toolsets a,b` (or
`SELECTED_TOOLSETS=a,b`) only tools belonging to a selected toolset register — tools in
no toolset are dropped while a selection is active. Without a selection, everything
registers.

## Multi-file loading

`--tools` accepts a single YAML file, a directory, or a glob pattern (env:
`TOOLS_YAML_PATH`). Every matching `*.yaml` / `*.yml` file is loaded and merged into one
config. Files are de-duplicated and merged in deterministic order (sorted absolute paths).

| Env var | Default | Effect |
|---|---|---|
| `YAML_MERGE_ARRAYS` | `true` | When two files define the same toolset name, concatenate their `tools` arrays; `false` replaces the whole toolset |
| `YAML_ALLOW_DUPLICATE_TOOLS` | `false` | Duplicate tool names across files are a hard error; `true` lets the later file override (stderr warning) |
| `YAML_ALLOW_DUPLICATE_SOURCES` | `false` | Duplicate source names across files are a hard error; `true` lets the later file override (stderr warning) |
| `YAML_VALIDATE_MERGED` | `true` | After merge, validate tool→source and toolset→tool references |

Only the literal string `true` enables a flag; any other value (including `false`) is
treated as false. Per-file referential checks are deferred during merge so a tool in one
file may reference a source or toolset member defined in another; post-merge validation
(`YAML_VALIDATE_MERGED`) is the gate.

Directory walks are recursive (`**/*.{yaml,yml}`). Glob patterns use Java NIO `PathMatcher`
semantics; `{yaml,yml}` brace alternation is expanded before matching (as in the reference
server). Because Java treats `**` as one-or-more directories, patterns containing `/**/` also
try a flattened variant (`/**/*.yaml` also matches `/*.yaml` at the walk root).

## Differences from the reference server (by design, for now)

- Simplified read-only validation (regex strategy only; the reference's primary path is
  a full SQL tokenizer/parser).
- No `typescript_tools` section, no built-in tools (`execute_sql`, `generate_sql`).
