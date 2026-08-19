# YAML tool packs

SQL tools shipped with ibmi-mcp-server-lite. Packs under this directory mirror the
Node reference server (`ibmi-mcp-server/tools/`), plus a lite-only discovery file.

## Loading

```bash
# Load every pack (requires YAML_ALLOW_DUPLICATE_SOURCES=true — see .env.example)
java -jar target/ibmi-mcp-server-lite-0.1.0.jar --tools tools

# Or via env:
# TOOLS_YAML_PATH=tools

# Subset by toolset:
java -jar target/ibmi-mcp-server-lite-0.1.0.jar --tools tools --toolsets performance,discovery

# Schema discovery builtins (optional; see README):
java -jar target/ibmi-mcp-server-lite-0.1.0.jar --tools tools --builtin-tools
```

Every pack redefines `sources.ibmi-system`. Directory merge therefore needs
`YAML_ALLOW_DUPLICATE_SOURCES=true` (last file wins). Keep
`YAML_ALLOW_DUPLICATE_TOOLS=false` so tool names stay unique.

**Overlap with built-ins:** `developer/text2sql.yaml` defines `list_tables_in_schema` and
`validate_query`. When `--builtin-tools` (or `IBMI_ENABLE_DEFAULT_TOOLS=true`) is on,
those YAML tools are **skipped** in favor of the Java builtins (warning on stderr).
`sample_rows` and `get_table_statistics` in the same pack are unaffected.

Connection fields use `${DB2i_HOST}`, `${DB2i_USER}`, `${DB2i_PASS}`, and
`${DB2i_PORT}` (defaults to Mapepire `8076` when unset). Those placeholders are
not secret-bearing; the packs may stay world-readable. See
[docs/running-on-ibmi.md](../docs/running-on-ibmi.md#secret-file-permissions).

## Packs

| Path | Tools (approx) | Toolsets |
|------|----------------|----------|
| `performance/performance.yaml` | 11 | `performance` |
| `sys-admin/sys-admin.yaml` | 12 | `sysadmin_discovery`, `sysadmin_browse`, `sysadmin_search` |
| `sys-admin/ptf_tools.yaml` | 6 | `ptf_management` |
| `developer/text2sql.yaml` | 4 | `text2sql` |
| `developer/object-statistics-dev.yaml` | 4 | `developer_tools` |
| `security/library-list-security.yaml` | 8 | `library_list_configuration`, `library_list_security`, `library_list_security_assessment` |
| `security/security-ops.yaml` | 17 | `security_vulnerability_assessment`, `security_audit`, `security_remediation` |
| `sample/employee-info.yaml` | 8 | `employee_information`, `project_management`, `salary_analysis` |
| `sample/fetch-rows-verification.yaml` | 4 | `fetch_rows_verification` |
| `sample/sample-tools.yaml` | 2 | `discovery` (lite-only) |

## Disabled by default

These tools stay in the YAML (and may appear in toolset lists) but are not
registered (`enabled: false` → omitted from `tools/list`):

| Tool | Pack | Reason |
|------|------|--------|
| `repopulate_special_authority_detail` | `security/security-ops.yaml` | Write: `REFRESH TABLE` |
| `execute_impersonation_lockdown` | `security/security-ops.yaml` | Write: `qcmdexc` / `GRTOBJAUT` |
| `describe_object` | `sys-admin/sys-admin.yaml` | `CALL`-based sys-admin helper; prefer always-on builtin `describe_sql_object` for DDL |

## Drift

Packs are a **manual** vendor copy from the Node reference. They are not
auto-synced on every upstream release — re-copy and re-apply the safety
curation (`enabled: false` on the write tools above) when updating.
