package com.ibm.ibmi.mcp.sql;

import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

import com.ibm.ibmi.mcp.config.SecurityConfig;

/**
 * Basic SQL safety checks applied before execution. Defaults mirror the reference server:
 * read-only is enforced unless a tool explicitly sets {@code security.readOnly: false},
 * and statements longer than 10000 characters are rejected.
 *
 * <p>This MVP uses the reference server's regex fallback strategy: string literals are
 * stripped, then the statement must start with SELECT or WITH and must not contain a
 * dangerous keyword. The reference implementation's primary path uses a full Db2 SQL
 * tokenizer/parser (vscode-db2i) — porting that is an INTERN TODO.
 */
public final class SqlSecurityValidator {

  public static final int DEFAULT_MAX_QUERY_LENGTH = 10_000;

  /** Reference server's DANGEROUS_OPERATIONS fallback list. */
  private static final List<String> DANGEROUS_OPERATIONS = List.of(
      "INSERT", "UPDATE", "DELETE", "MERGE", "TRUNCATE", "DROP", "CREATE", "ALTER",
      "RENAME", "CALL", "EXEC", "EXECUTE", "SET", "DECLARE", "GRANT", "REVOKE", "DENY",
      "LOAD", "IMPORT", "EXPORT", "BULK", "SHUTDOWN", "RESTART", "KILL", "STOP", "START",
      "BACKUP", "RESTORE", "DUMP", "LOCK", "UNLOCK", "COMMIT", "ROLLBACK", "SAVEPOINT",
      "QCMDEXC", "SQL_EXECUTE_IMMEDIATE");

  private static final Pattern STRING_LITERAL = Pattern.compile("'[^']*'");
  private static final Pattern LINE_COMMENT = Pattern.compile("--[^\\n]*");

  private SqlSecurityValidator() {}

  /** @throws SecurityException when the statement violates the effective security config */
  public static void validate(String sql, SecurityConfig security) {
    SecurityConfig effective = security == null ? SecurityConfig.DEFAULTS : security;

    int maxLength = effective.maxQueryLength() != null
        ? effective.maxQueryLength() : DEFAULT_MAX_QUERY_LENGTH;
    if (sql.length() > maxLength) {
      throw new SecurityException(
          "Query exceeds maximum length of " + maxLength + " characters");
    }

    String stripped = LINE_COMMENT.matcher(STRING_LITERAL.matcher(sql).replaceAll("''"))
        .replaceAll("").trim();
    String upper = stripped.toUpperCase(Locale.ROOT);

    if (effective.forbiddenKeywords() != null) {
      for (String keyword : effective.forbiddenKeywords()) {
        if (containsWord(upper, keyword.toUpperCase(Locale.ROOT))) {
          throw new SecurityException("Query contains forbidden keyword: " + keyword);
        }
      }
    }

    boolean readOnly = effective.readOnly() == null || effective.readOnly();
    if (readOnly) {
      if (!upper.startsWith("SELECT") && !upper.startsWith("WITH")) {
        throw new SecurityException(
            "Only SELECT/WITH statements are allowed for read-only tools");
      }
      for (String op : DANGEROUS_OPERATIONS) {
        if (containsWord(upper, op)) {
          throw new SecurityException(
              "Read-only validation failed: statement contains '" + op + "'");
        }
      }
    }
  }

  private static boolean containsWord(String text, String word) {
    return Pattern.compile("\\b" + Pattern.quote(word) + "\\b").matcher(text).find();
  }
}
