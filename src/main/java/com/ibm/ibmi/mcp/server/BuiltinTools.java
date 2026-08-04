package com.ibm.ibmi.mcp.server;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.ibm.ibmi.mcp.config.ParameterConfig;
import com.ibm.ibmi.mcp.config.SecurityConfig;
import com.ibm.ibmi.mcp.config.SqlToolConfig;

/** Programmatically defined MCP tools (not loaded from YAML). */
public final class BuiltinTools {

  public static final String DESCRIBE_SQL_OBJECT_NAME = "describe_sql_object";
  public static final String LIST_SCHEMAS_NAME = "list_schemas";
  public static final String LIST_TABLES_IN_SCHEMA_NAME = "list_tables_in_schema";
  public static final String GET_TABLE_COLUMNS_NAME = "get_table_columns";
  public static final String GET_RELATED_OBJECTS_NAME = "get_related_objects";
  public static final String VALIDATE_QUERY_NAME = "validate_query";
  public static final String EXECUTE_SQL_NAME = "execute_sql";

  /** Object types accepted by {@code QSYS2.GENERATE_SQL}. */
  private static final List<Object> GENERATE_SQL_OBJECT_TYPES = List.of(
      "ALIAS", "CONSTRAINT", "FUNCTION", "INDEX", "MASK", "PERMISSION", "PROCEDURE",
      "SCHEMA", "SEQUENCE", "TABLE", "TRIGGER", "TYPE", "VARIABLE", "VIEW", "XSR");

  /**
   * Dependent object types from {@code SYSTOOLS.RELATED_OBJECTS}, plus {@code *ALL}
   * for the optional filter default.
   */
  private static final List<Object> RELATED_OBJECT_TYPE_FILTERS = List.of(
      "*ALL",
      "ALIAS",
      "FOREIGN KEY",
      "FUNCTION",
      "HISTORY TABLE",
      "INDEX",
      "KEYED LOGICAL FILE",
      "LOGICAL FILE",
      "MASK",
      "MATERIALIZED QUERY TABLE",
      "PERMISSION",
      "PROCEDURE",
      "TEXT INDEX",
      "TRIGGER",
      "VARIABLE",
      "VIEW",
      "XML SCHEMA");

  private BuiltinTools() {}

  /**
   * Names of builtins that will register for the given gates. {@code describe_sql_object}
   * is always included; discovery tools follow {@code enableBuiltinTools}; {@code execute_sql}
   * follows {@code enableExecuteSql}.
   */
  public static Set<String> activeBuiltinNames(boolean enableBuiltinTools, boolean enableExecuteSql) {
    Set<String> names = new LinkedHashSet<>();
    names.add(DESCRIBE_SQL_OBJECT_NAME);
    if (enableBuiltinTools) {
      names.add(LIST_SCHEMAS_NAME);
      names.add(LIST_TABLES_IN_SCHEMA_NAME);
      names.add(GET_TABLE_COLUMNS_NAME);
      names.add(GET_RELATED_OBJECTS_NAME);
      names.add(VALIDATE_QUERY_NAME);
    }
    if (enableExecuteSql) {
      names.add(EXECUTE_SQL_NAME);
    }
    return Set.copyOf(names);
  }

  /**
   * Builds the {@link SqlToolConfig} instances that should be registered for the given gates.
   * Source must already be resolved (same first-source rule as {@code execute_sql}).
   */
  public static List<SqlToolConfig> configsForGates(
      String sourceName, boolean enableBuiltinTools, boolean enableExecuteSql, boolean executeSqlReadonly) {
    List<SqlToolConfig> configs = new ArrayList<>();
    configs.add(describeSqlObject(sourceName));
    if (enableBuiltinTools) {
      configs.add(listSchemas(sourceName));
      configs.add(listTablesInSchema(sourceName));
      configs.add(getTableColumns(sourceName));
      configs.add(getRelatedObjects(sourceName));
      configs.add(validateQuery(sourceName));
    }
    if (enableExecuteSql) {
      configs.add(executeSql(sourceName, executeSqlReadonly));
    }
    return List.copyOf(configs);
  }

  /**
   * Always-on DDL generation via {@code QSYS2.GENERATE_SQL}. Uses {@code readOnly: false}
   * because {@code CALL} is rejected by the read-only validator; the procedure itself is
   * descriptive. Result rows include {@code SRCDTA} source lines.
   */
  public static SqlToolConfig describeSqlObject(String sourceName) {
    String statement = """
        CALL QSYS2.GENERATE_SQL(
          DATABASE_OBJECT_NAME => :object_name,
          DATABASE_OBJECT_LIBRARY_NAME => :object_library,
          DATABASE_OBJECT_TYPE => :object_type,
          CREATE_OR_REPLACE_OPTION => '1',
          PRIVILEGES_OPTION => '0',
          STATEMENT_FORMATTING_OPTION => '0',
          SOURCE_STREAM_FILE_END_OF_LINE => 'LF',
          SOURCE_STREAM_FILE_CCSID => 1208
        )
        """;
    return sqlTool(
        DESCRIBE_SQL_OBJECT_NAME,
        sourceName,
        "Generate the SQL DDL statement for an IBM i database object. Use this to see the "
            + "full CREATE definition of a table, view, index, procedure, function, or other object. "
            + "Returns GENERATE_SQL result rows (including SRCDTA source lines).",
        statement,
        List.of(
            stringParam(
                "object_name",
                "The name of the IBM i database object to generate DDL for.",
                null,
                true,
                1,
                128,
                null),
            stringParam(
                "object_library",
                "The library where the database object is located. Defaults to QSYS2.",
                "QSYS2",
                null,
                1,
                128,
                null),
            stringParam(
                "object_type",
                "The type of database object to generate DDL for "
                    + "(TABLE, VIEW, INDEX, PROCEDURE, FUNCTION, etc.).",
                "TABLE",
                null,
                null,
                null,
                GENERATE_SQL_OBJECT_TYPES)),
        false,
        Map.of("readOnlyHint", false));
  }

  /** Lists schemas/libraries from {@code QSYS2.SYSSCHEMAS}. */
  public static SqlToolConfig listSchemas(String sourceName) {
    String statement = """
        SELECT SCHEMA_NAME,
               SCHEMA_TEXT,
               SYSTEM_SCHEMA_NAME,
               SCHEMA_SIZE
        FROM QSYS2.SYSSCHEMAS
        WHERE (:include_system = 1
               OR (SCHEMA_NAME NOT LIKE 'Q%' AND SCHEMA_NAME NOT LIKE 'SYS%'))
          AND (:filter = '*ALL' OR SCHEMA_NAME LIKE UPPER(:filter))
        ORDER BY SCHEMA_NAME
        OFFSET :offset ROWS FETCH FIRST :limit ROWS ONLY
        """;
    return sqlTool(
        LIST_SCHEMAS_NAME,
        sourceName,
        "List available schemas/libraries on the IBM i system. First step in schema discovery "
            + "for text-to-SQL workflows.",
        statement,
        List.of(
            stringParam(
                "filter",
                "Optional schema name pattern (SQL LIKE, e.g. 'MY%', 'LIB%'). Use '*ALL' for no filter.",
                "*ALL",
                null,
                null,
                128,
                null),
            boolParam(
                "include_system",
                "Include system schemas (Q* and SYS* prefixed). Default: false.",
                false),
            intParam("limit", "Maximum number of rows to return (1-500).", 50, 1, 500),
            intParam("offset", "Number of rows to skip for pagination.", 0, 0, null)),
        true,
        readOnlyAnnotations());
  }

  /** Lists tables/views/PFs in a schema with row-count metadata. */
  public static SqlToolConfig listTablesInSchema(String sourceName) {
    String statement = """
        SELECT T.TABLE_SCHEMA,
               T.TABLE_NAME,
               T.TABLE_TYPE,
               T.TABLE_TEXT,
               COALESCE(S.NUMBER_ROWS, 0) AS NUMBER_ROWS,
               T.COLUMN_COUNT
        FROM QSYS2.SYSTABLES T
        LEFT JOIN QSYS2.SYSTABLESTAT S
          ON T.TABLE_SCHEMA = S.TABLE_SCHEMA
         AND T.TABLE_NAME = S.TABLE_NAME
        WHERE T.TABLE_SCHEMA = UPPER(:schema_name)
          AND T.TABLE_TYPE IN ('T', 'V', 'P')
          AND (:table_filter = '*ALL' OR T.TABLE_NAME LIKE UPPER(:table_filter))
        ORDER BY T.TABLE_TYPE, T.TABLE_NAME
        OFFSET :offset ROWS FETCH FIRST :limit ROWS ONLY
        """;
    return sqlTool(
        LIST_TABLES_IN_SCHEMA_NAME,
        sourceName,
        "List tables, views, and physical files in a specific schema with metadata including "
            + "row counts. Essential for understanding schema structure.",
        statement,
        List.of(
            stringParam(
                "schema_name",
                "Schema name to list tables from (e.g., 'QIWS', 'SAMPLE', 'MYLIB')",
                null,
                true,
                1,
                128,
                null),
            stringParam(
                "table_filter",
                "Filter tables by name pattern (e.g., 'CUST%', 'ORD%'). Use '*ALL' for all tables.",
                "*ALL",
                null,
                null,
                128,
                null),
            intParam("limit", "Maximum number of rows to return (1-500).", 50, 1, 500),
            intParam("offset", "Number of rows to skip for pagination.", 0, 0, null)),
        true,
        readOnlyAnnotations());
  }

  /** Column metadata from {@code QSYS2.SYSCOLUMNS2}. */
  public static SqlToolConfig getTableColumns(String sourceName) {
    String statement = """
        SELECT COLUMN_NAME,
               SYSTEM_COLUMN_NAME,
               DATA_TYPE,
               LENGTH,
               NUMERIC_SCALE,
               NUMERIC_PRECISION,
               IS_NULLABLE,
               HAS_DEFAULT,
               COLUMN_DEFAULT,
               COLUMN_TEXT,
               COLUMN_HEADING,
               ORDINAL_POSITION,
               CCSID,
               HIDDEN,
               IS_IDENTITY
        FROM QSYS2.SYSCOLUMNS2
        WHERE TABLE_SCHEMA = UPPER(:schema_name)
          AND TABLE_NAME = UPPER(:table_name)
        ORDER BY ORDINAL_POSITION
        """;
    return sqlTool(
        GET_TABLE_COLUMNS_NAME,
        sourceName,
        "Get column metadata for a table or view. Use before writing SQL against a table.",
        statement,
        List.of(
            stringParam(
                "schema_name",
                "Schema containing the table (e.g., 'QIWS', 'SAMPLE')",
                null,
                true,
                1,
                128,
                null),
            stringParam(
                "table_name",
                "Table or view name (e.g., 'QCUSTCDT', 'EMPLOYEE')",
                null,
                true,
                1,
                128,
                null)),
        true,
        readOnlyAnnotations());
  }

  /** Dependent objects via {@code SYSTOOLS.RELATED_OBJECTS}. */
  public static SqlToolConfig getRelatedObjects(String sourceName) {
    String statement = """
        SELECT *
        FROM TABLE(SYSTOOLS.RELATED_OBJECTS(
          LIBRARY_NAME => :library_name,
          FILE_NAME => :file_name
        )) AS R
        WHERE (:object_type_filter = '*ALL' OR R.SQL_OBJECT_TYPE = :object_type_filter)
        ORDER BY R.SQL_OBJECT_TYPE, R.SQL_NAME
        """;
    return sqlTool(
        GET_RELATED_OBJECTS_NAME,
        sourceName,
        "Discover objects that depend on a database file (views, indexes, triggers, foreign keys, "
            + "logical files, and others) using SYSTOOLS.RELATED_OBJECTS.",
        statement,
        List.of(
            stringParam(
                "library_name",
                "Library containing the database file (e.g., 'APPLIB', 'MYLIB')",
                null,
                true,
                1,
                10,
                null),
            stringParam(
                "file_name",
                "System name of the database file to find dependents for (e.g., 'ORDERS', 'CUSTOMER')",
                null,
                true,
                1,
                10,
                null),
            stringParam(
                "object_type_filter",
                "Filter to a dependent object type (e.g., 'INDEX', 'VIEW'). Use '*ALL' for all types.",
                "*ALL",
                null,
                null,
                null,
                RELATED_OBJECT_TYPE_FILTERS)),
        true,
        readOnlyAnnotations());
  }

  /**
   * Syntax validation via {@code QSYS2.PARSE_STATEMENT}. Empty result rows indicate invalid SQL.
   * Catalog hallucination checks are out of scope (follow-up issue).
   */
  public static SqlToolConfig validateQuery(String sourceName) {
    String statement = """
        SELECT *
        FROM TABLE(QSYS2.PARSE_STATEMENT(
          SQL_STATEMENT => :sql_statement,
          NAMING => '*SQL',
          DECIMAL_POINT => '*PERIOD',
          SQL_STRING_DELIMITER => '*APOSTSQL'
        )) AS P
        """;
    return sqlTool(
        VALIDATE_QUERY_NAME,
        sourceName,
        "Validate SQL query syntax using IBM i's native SQL parser (QSYS2.PARSE_STATEMENT). "
            + "Returns statement type and parsing results without executing the query. "
            + "If no results are returned, the statement is invalid.",
        statement,
        List.of(stringParam(
            "sql_statement",
            "SQL statement to validate (e.g., 'SELECT * FROM QIWS.QCUSTCDT')",
            null,
            true,
            5,
            10_000,
            null)),
        true,
        readOnlyAnnotations());
  }

  private static String executeSqlDescription(boolean readOnly) {
    if (readOnly) {
      return "Executes a SELECT query on the IBM i database and returns the results. "
          + "(Read-only: SELECT/WITH only.)";
    }
    return "Executes a SQL query on the IBM i database and returns the results.";
  }

  /**
   * Built-in ad-hoc SQL tool. The statement {@code :sql} uses direct substitution at call time;
   * {@link com.ibm.ibmi.mcp.sql.SqlSecurityValidator} enforces read-only when configured.
   */
  public static SqlToolConfig executeSql(String sourceName, boolean readOnly) {
    return new SqlToolConfig(
        EXECUTE_SQL_NAME,
        true,
        sourceName,
        executeSqlDescription(readOnly),
        ":sql",
        List.of(new ParameterConfig(
            "sql",
            "string",
            "The SQL query to execute on the IBM i database",
            null,
            true,
            null, null, null, 1, 10_000, null, null)),
        null,
        null,
        null,
        Map.of("readOnlyHint", readOnly),
        new SecurityConfig(readOnly, 10_000, null),
        null,
        null,
        null,
        null);
  }

  private static Map<String, Object> readOnlyAnnotations() {
    return Map.of(
        "readOnlyHint", true,
        "idempotentHint", true,
        "domain", "development",
        "category", "text2sql");
  }

  private static SqlToolConfig sqlTool(
      String name,
      String sourceName,
      String description,
      String statement,
      List<ParameterConfig> parameters,
      boolean readOnly,
      Map<String, Object> annotations) {
    return new SqlToolConfig(
        name,
        true,
        sourceName,
        description,
        statement.strip(),
        parameters,
        null,
        null,
        null,
        annotations,
        new SecurityConfig(readOnly, 10_000, null),
        null,
        null,
        "development",
        "text2sql");
  }

  private static ParameterConfig stringParam(
      String name,
      String description,
      Object defaultValue,
      Boolean required,
      Integer minLength,
      Integer maxLength,
      List<Object> enumValues) {
    return new ParameterConfig(
        name,
        "string",
        description,
        defaultValue,
        required,
        null,
        null,
        null,
        minLength,
        maxLength,
        enumValues,
        null);
  }

  private static ParameterConfig intParam(
      String name, String description, int defaultValue, Number min, Number max) {
    return new ParameterConfig(
        name,
        "integer",
        description,
        defaultValue,
        null,
        null,
        min,
        max,
        null,
        null,
        null,
        null);
  }

  private static ParameterConfig boolParam(String name, String description, boolean defaultValue) {
    return new ParameterConfig(
        name,
        "boolean",
        description,
        defaultValue,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null);
  }
}
