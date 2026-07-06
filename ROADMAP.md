# Roadmap

This roadmap sequences the 18 scoped intern issues into six milestones that form a deliberate delivery-and-learning arc. The path starts where interns can iterate fastest — schema and output parity that ports cleanly from the Node.js reference and is largely testable without a live IBM i — then moves outward through runtime robustness, configuration lifecycle, SQL security hardening, HTTP transport and on-box deployment, and finally upstream contributions plus end-to-end test infrastructure. Milestones are ordered by dependency and value: each one leans on muscle built in the previous, and every issue lands in exactly one milestone. The reference Node implementation remains the behavioral contract throughout — when a milestone says "matches the reference," that parity (verified against `docs/research/yaml-schema-contract.md`) is the definition of done.

Work is organized into **milestones**, each a focused theme that builds on the last. Every item below links to a GitHub issue with full implementation detail — current state in this codebase, concrete pointers into the [official Node.js reference server](https://github.com/IBM/ibmi-mcp-server), a build plan, and acceptance criteria. The [GitHub milestones](https://github.com/ajshedivy/ibmi-mcp-server-lite/milestones) and [issues](https://github.com/ajshedivy/ibmi-mcp-server-lite/issues) are the live tracker; this file is the map.

**Difficulty:** 🟢 good first issue · 🟡 intermediate · 🔴 advanced

**Jump in:** [good first issues](https://github.com/ajshedivy/ibmi-mcp-server-lite/issues?q=is%3Aopen+label%3A%22good+first+issue%22) · [all open issues](https://github.com/ajshedivy/ibmi-mcp-server-lite/issues) · [milestones](https://github.com/ajshedivy/ibmi-mcp-server-lite/milestones) · [CONTRIBUTING](CONTRIBUTING.md)

## Milestones at a glance

| Milestone | Theme | Issues |
|---|---|---|
| [M1](https://github.com/ajshedivy/ibmi-mcp-server-lite/milestone/1) | Schema & Output Parity | 5 |
| [M2](https://github.com/ajshedivy/ibmi-mcp-server-lite/milestone/2) | Runtime Robustness | 4 |
| [M3](https://github.com/ajshedivy/ibmi-mcp-server-lite/milestone/3) | Configuration Lifecycle | 3 |
| [M4](https://github.com/ajshedivy/ibmi-mcp-server-lite/milestone/4) | SQL Security Hardening | 2 |
| [M5](https://github.com/ajshedivy/ibmi-mcp-server-lite/milestone/5) | HTTP Transport & Deployment | 2 |
| [M6](https://github.com/ajshedivy/ibmi-mcp-server-lite/milestone/6) | Upstream & Test Infrastructure | 2 |

## M1 — Schema & Output Parity

[📂 Milestone M1 on GitHub](https://github.com/ajshedivy/ibmi-mcp-server-lite/milestone/1)

Close the gap between the lite server's JSON Schema / output handling and the reference server's, plus enforce the constraints the Java SDK won't auto-validate. Done means a tool's inputSchema, outputSchema, enum shapes, markdown rendering, and call-time argument validation are byte-for-behavior aligned with the Node reference, all provable in unit tests without a live IBM i.

> **Why these together:** These are the starter/schema-area issues (markdown-response-format, enum-schema-parity, output-schema-registration are all 'starter'; list-tools-cli-flag is a 'starter' CLI warm-up) that touch the schema/ and server/ packages and need little or no live Db2 to test — the ideal first-issue onramp. server-side-input-validation is 'intermediate' but belongs here because it operates directly on the inputSchema constraints the other schema issues produce, so grouping it consolidates all schema/validation learning into one milestone. No issue here has a dependsOn, and none blocks the others, so interns can pick them up in parallel.

**Implemented:**
- ✅ [#1](https://github.com/ajshedivy/ibmi-mcp-server-lite/issues/1) — `--list-tools` CLI flag with tool/toolset/parameter formatting
- ✅ [#2](https://github.com/ajshedivy/ibmi-mcp-server-lite/issues/2) — Enum JSON Schema parity: emit const / anyOf-of-const and the "Must be one of:" description suffix
- ✅ [#3](https://github.com/ajshedivy/ibmi-mcp-server-lite/issues/3) — Register StandardSqlToolOutput JSON Schema as each tool's MCP outputSchema
- ✅ [#4](https://github.com/ajshedivy/ibmi-mcp-server-lite/issues/4) — Implement `responseFormat: markdown` rendering (tableFormat / maxDisplayRows)
- ✅ [#5](https://github.com/ajshedivy/ibmi-mcp-server-lite/issues/5) — Enforce parameter constraints (pattern / enum / min / max / length) server-side at call time

## M2 — Runtime Robustness

[📂 Milestone M2 on GitHub](https://github.com/ajshedivy/ibmi-mcp-server-lite/milestone/2)

Make the execution and connection layers production-grade: a real connection pool, full-result pagination with a safety cap, JDBC option passthrough, and clean lifecycle shutdown. Done means concurrent tool calls run in parallel against a pooled source, fetchAllRows tools page to completion (with a truncated flag), per-source jdbc-options reach the SqlJob, and the process exits cleanly when stdin closes — all matching the reference server's runtime behavior.

> **Why these together:** This milestone hardens the mapepire/ and sql-execution path that M1's correct outputs now flow through. connection-pooling is the natural anchor (it reshapes SourceManager); jdbc-options-passthrough and graceful-shutdown both operate on the same SqlJob/source lifecycle, so co-locating them avoids merge churn across the connection layer; pagination-fetch-all-rows extends the execution loop. These are the 'intermediate' connection/execution/lifecycle issues that require a live Mapepire to fully validate — a reasonable step up once interns have shipped M1. No dependsOn constraints, but ordering pooling first reduces rework for the others.

**Implemented:**
- ✅ [#6](https://github.com/ajshedivy/ibmi-mcp-server-lite/issues/6) — Connection pooling: replace the single SqlJob per source with mapepire Pool
- ✅ [#8](https://github.com/ajshedivy/ibmi-mcp-server-lite/issues/8) — Implement fetchAllRows pagination with a row-count safety cap and a `truncated` flag
- ✅ [#7](https://github.com/ajshedivy/ibmi-mcp-server-lite/issues/7) — Pass YAML `jdbc-options` (and `DB2i_JDBC_OPTIONS`) through to mapepire `SqlJob`
- ✅ [#9](https://github.com/ajshedivy/ibmi-mcp-server-lite/issues/9) — Graceful shutdown: exit when stdin closes and clean up in-flight queries + pools

| # | Issue | Difficulty | Estimate |
|---|---|---|---|

## M3 — Configuration Lifecycle

[📂 Milestone M3 on GitHub](https://github.com/ajshedivy/ibmi-mcp-server-lite/milestone/3)

Grow the config loader from one static file into a multi-file, live-reloading configuration system with correlatable per-call logging. Done means --tools accepts a directory or glob and merges files under the reference duplicate/merge rules, the server hot-reloads on YAML change behind a YAML_AUTO_RELOAD flag (re-registering tools via notifyToolsListChanged), and every tools/call emits correlated structured log lines on stderr.

> **Why these together:** These are the config-area lifecycle issues that build on a stable runtime (M2): hot-reload's addTool/removeTool churn is far safer to implement once pooling and graceful shutdown exist, so it follows multi-file-yaml-merge, which establishes the merge/validation foundation hot-reload re-runs on every change. structured-request-logging is the observability companion that makes reload and concurrent-call behavior debuggable, so it rounds out the milestone. All three are 'intermediate' and have no dependsOn, but the internal order (merge → reload → logging) reflects their natural build dependency.

**Implemented:**
- ✅ [#10](https://github.com/ajshedivy/ibmi-mcp-server-lite/issues/10) — Multi-file YAML: accept a directory or glob for `--tools` and merge with reference duplicate rules
- ✅ [#11](https://github.com/ajshedivy/ibmi-mcp-server-lite/issues/11) — Hot-reload tools YAML on change (`WatchService` + `notifyToolsListChanged`, `YAML_AUTO_RELOAD`)
- ✅ [#12](https://github.com/ajshedivy/ibmi-mcp-server-lite/issues/12) - Structured per-call request logging (RequestContext)

| # | Issue | Difficulty | Estimate |
|---|---|---|---|

## M4 — SQL Security Hardening

[📂 Milestone M4 on GitHub](https://github.com/ajshedivy/ibmi-mcp-server-lite/milestone/4)

Replace the brittle keyword-regex read-only check with a real Db2 for i statement-type classifier, then build the ad-hoc execute_sql tool safely on top of it. Done means SqlSecurityValidator classifies statements by token/statement-type (only SELECT/WITH pass) and the env-gated execute_sql built-in tool reuses that validation for direct-substitution SELECTs, matching the reference server.

> **Why these together:** Security hardening is sequenced after the runtime and config layers are trustworthy so the new validator is exercised against real, varied tool loads. The two issues are tightly coupled: execute_sql is the riskiest surface in the server, so it must land on the hardened classifier from real-sql-security-validation rather than the regex it replaces — hence both live in this milestone with the classifier ordered first. Both are 'advanced' sql-area work, a deliberate difficulty step-up that rewards interns who have completed earlier milestones.

**Implemented:**
- ✅ [#13](https://github.com/ajshedivy/ibmi-mcp-server-lite/issues/13) — Tokenizer-based SQL security validation (`SqlTokenizer` + statement-type classifier, regex fallback)

| # | Issue | Difficulty | Estimate |
|---|---|---|---|
| [#14](https://github.com/ajshedivy/ibmi-mcp-server-lite/issues/14) | [Add the built-in execute_sql tool gated by IBMI_ENABLE_EXECUTE_SQL](https://github.com/ajshedivy/ibmi-mcp-server-lite/issues/14) | 🔴 advanced | M (3-5 days) |

## M5 — HTTP Transport & Deployment

[📂 Milestone M5 on GitHub](https://github.com/ajshedivy/ibmi-mcp-server-lite/milestone/5)

Take the server off per-client stdio and onto a long-lived deployment footprint: a Streamable HTTP transport and a working IBM i RPM pipeline. Done means --transport http serves the MCP server over HttpServletStreamableServerTransportProvider inside embedded Jetty, and the RPM skeleton becomes a real pipeline (pinned Java 17, fixed spec, enabled SSH-rpmbuild workflow) that installs the daemon on a build partition.

> **Why these together:** Both 'advanced' issues are about how the server runs in production rather than what it computes, so they belong after the feature core (M1–M4) is complete. They are complementary halves of the same deployment story: the HTTP transport gives the server a daemon-friendly long-lived mode, and finish-rpm-packaging is what actually installs and runs that daemon on IBM i via Service Commander. Sequencing transport before packaging means the RPM ships a server that already supports the daemon transport it will be configured to use.

**Implemented:**
- ✅ [#15](https://github.com/ajshedivy/ibmi-mcp-server-lite/issues/15) — Streamable HTTP transport (`--transport http`) via embedded Jetty

| # | Issue | Difficulty | Estimate |
|---|---|---|---|
| [#16](https://github.com/ajshedivy/ibmi-mcp-server-lite/issues/16) | [Finish the IBM i RPM packaging pipeline (resolve Java 17, complete spec, enable workflow)](https://github.com/ajshedivy/ibmi-mcp-server-lite/issues/16) | 🔴 advanced | L (1-2 weeks) |

## M6 — Upstream & Test Infrastructure

[📂 Milestone M6 on GitHub](https://github.com/ajshedivy/ibmi-mcp-server-lite/milestone/6)

Invest in the surrounding ecosystem: fix the TLS hostname-verification gap upstream in mapepire-java and stand up a real gated integration-test harness. Done means a merged (or submitted) mapepire-java change that skips SAN verification when rejectUnauthorized=false, and a Maven Failsafe integration profile that exercises the full pipeline against live Mapepire/Db2 for i while staying skipped by default.

> **Why these together:** This capstone milestone comes last because both issues are most valuable once the full feature set exists to validate and deploy. The integration-test-harness should cover the complete pipeline built across M1–M5, so it lands after those features rather than chasing a moving target. The mapepire-tls-hostname-upstream fix is the upstream contribution that retires a documented constraint (the TLS SAN workaround in CLAUDE.md / docs/running-on-ibmi.md) the integration harness would otherwise have to work around — pairing them here keeps the connection-reliability work together and gives interns an external-contribution experience to finish on.

| # | Issue | Difficulty | Estimate |
|---|---|---|---|
| [#17](https://github.com/ajshedivy/ibmi-mcp-server-lite/issues/17) | [Upstream mapepire-java: skip TLS hostname verification when rejectUnauthorized=false](https://github.com/ajshedivy/ibmi-mcp-server-lite/issues/17) | 🔴 advanced | M (3-5 days) |
| [#18](https://github.com/ajshedivy/ibmi-mcp-server-lite/issues/18) | [Add a JUnit integration-test profile that runs against a live Mapepire, gated by env vars](https://github.com/ajshedivy/ibmi-mcp-server-lite/issues/18) | 🟡 intermediate | M (3-5 days) |

---

_This roadmap is generated from the project's GitHub issues and milestones. When you finish an issue, close it (and check it off its milestone); the milestone completes when all its issues are closed. Proposing a new item? Open an issue and tag the milestone it belongs to._
