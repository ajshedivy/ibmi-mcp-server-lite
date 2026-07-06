package com.ibm.ibmi.mcp.sql;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.ibm.ibmi.mcp.config.SecurityConfig;
import com.ibm.ibmi.mcp.sql.SqlTokenizer.Token;
import com.ibm.ibmi.mcp.sql.SqlTokenizer.TokenType;

/**
 * SQL safety checks applied before execution. Defaults mirror the reference server:
 * read-only is enforced unless a tool explicitly sets {@code security.readOnly: false},
 * and statements longer than 10000 characters are rejected.
 *
 * <p>Primary validation uses {@link SqlTokenizer} to classify statement types (only
 * {@code SELECT} and {@code WITH} pass read-only) and to scan forbidden keywords while
 * ignoring string literals, comments, and quoted identifiers. When tokenization fails,
 * a regex fallback strips literals and comments then scans dangerous keywords/patterns.
 */
public final class SqlSecurityValidator {

  private static final Logger LOG = LoggerFactory.getLogger(SqlSecurityValidator.class);

  public static final int DEFAULT_MAX_QUERY_LENGTH = 10_000;

  /** Reference server's DANGEROUS_OPERATIONS fallback list (regex path only). */
  private static final List<String> DANGEROUS_OPERATIONS = List.of(
      "INSERT", "UPDATE", "DELETE", "MERGE", "TRUNCATE", "DROP", "CREATE", "ALTER",
      "RENAME", "CALL", "EXEC", "EXECUTE", "SET", "DECLARE", "GRANT", "REVOKE", "DENY",
      "LOAD", "IMPORT", "EXPORT", "BULK", "SHUTDOWN", "RESTART", "KILL", "STOP", "START",
      "BACKUP", "RESTORE", "DUMP", "LOCK", "UNLOCK", "COMMIT", "ROLLBACK", "SAVEPOINT",
      "QCMDEXC", "SQL_EXECUTE_IMMEDIATE");

  private static final Pattern STRING_LITERAL = Pattern.compile("'(?:''|[^'])*'");
  private static final Pattern LINE_COMMENT = Pattern.compile("--[^\\n]*");
  private static final Pattern BLOCK_COMMENT = Pattern.compile("/\\*[\\s\\S]*?\\*/");

  private static final Pattern[] DANGEROUS_PATTERNS = {
      // Multiple statement patterns (SQL injection via statement chaining)
      Pattern.compile(";\\s*(DROP|DELETE|INSERT|UPDATE|CREATE|ALTER)", Pattern.CASE_INSENSITIVE),
      // Union-based attacks (SQL injection via UNION with dangerous operations)
      Pattern.compile(
          "\\bUNION\\s+(ALL\\s+)?\\s*\\(\\s*(DROP|DELETE|INSERT|UPDATE)",
          Pattern.CASE_INSENSITIVE),
      // REPLACE statement (MySQL-specific write operation)
      Pattern.compile("\\bREPLACE\\s+INTO\\b", Pattern.CASE_INSENSITIVE),
  };

  /** Placeholder-only statements ({@code :sql}) are validated at call time after substitution. */
  private static final Pattern DIRECT_SUBSTITUTION_PLACEHOLDER = Pattern.compile(":\\w+");

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

    if (isDirectSubstitutionPlaceholder(sql)) {
      return;
    }

    List<String> forbiddenKeywords = effective.forbiddenKeywords();
    boolean readOnly = effective.readOnly() == null || effective.readOnly();
    if (forbiddenKeywords != null || readOnly) {
      try {
        List<Token> tokens = SqlTokenizer.tokenize(sql);
        if (forbiddenKeywords != null) {
          validateForbiddenKeywordsToken(tokens, forbiddenKeywords);
        }
        if (readOnly) {
          validateReadOnlyToken(tokens);
        }
      } catch (SqlTokenizerException e) {
        LOG.warn("SQL tokenization failed, using regex fallback: {}", e.getMessage());
        if (forbiddenKeywords != null) {
          validateForbiddenKeywordsFallback(sql, forbiddenKeywords);
        }
        if (readOnly) {
          validateReadOnlyFallback(sql);
        }
      }
    }
  }

  private static void validateForbiddenKeywordsToken(
      List<Token> tokens, List<String> forbiddenKeywords) {
    Set<String> forbidden = new HashSet<>();
    for (String keyword : forbiddenKeywords) {
      forbidden.add(keyword.toUpperCase(Locale.ROOT));
    }

    for (Token token : tokens) {
      if (isSkippedForKeywordScan(token.type())) {
        continue;
      }
      if (token.type() == TokenType.WORD && forbidden.contains(token.value())) {
        throw new SecurityException("Query contains forbidden keyword: " + token.value());
      }
    }
  }

  private static void validateForbiddenKeywordsFallback(
      String sql, List<String> forbiddenKeywords) {
    String stripped = stripForFallback(sql).toUpperCase(Locale.ROOT);
    for (String keyword : forbiddenKeywords) {
      if (containsWord(stripped, keyword.toUpperCase(Locale.ROOT))) {
        throw new SecurityException("Query contains forbidden keyword: " + keyword);
      }
    }
  }

  private static void validateReadOnlyToken(List<Token> tokens) {
    // Top-level statement type only (leading keyword per segment): nested DML inside a
    // WITH/SELECT (e.g. "WITH t AS (DELETE FROM x) SELECT * FROM t") is not checked here
    // but fails at Db2 execution — same as the reference server's parser path.
    List<List<Token>> statements = SqlTokenizer.splitStatements(tokens);

    if (statements.isEmpty()) {
      throw new SecurityException("Read-only validation failed: UNKNOWN");
    }

    for (List<Token> statement : statements) {
      StatementType type = SqlTokenizer.classifyStatement(statement);
      if (!type.isReadOnly()) {
        throw new SecurityException("Read-only validation failed: " + type.name());
      }
    }
  }

  private static void validateReadOnlyFallback(String sql) {
    String stripped = stripForFallback(sql).toUpperCase(Locale.ROOT);

    for (String op : DANGEROUS_OPERATIONS) {
      if (containsWord(stripped, op)) {
        throw new SecurityException(
            "Read-only validation failed: statement contains '" + op + "'");
      }
    }

    for (Pattern pattern : DANGEROUS_PATTERNS) {
      if (pattern.matcher(stripped).find()) {
        throw new SecurityException(
            "Read-only validation failed: dangerous pattern detected");
      }
    }
  }

  private static boolean isSkippedForKeywordScan(TokenType type) {
    return type == TokenType.STRING
        || type == TokenType.LINE_COMMENT
        || type == TokenType.BLOCK_COMMENT
        || type == TokenType.QUOTED_NAME;
  }

  private static String stripForFallback(String sql) {
    String stripped = STRING_LITERAL.matcher(sql).replaceAll("''");
    stripped = LINE_COMMENT.matcher(stripped).replaceAll("");
    stripped = BLOCK_COMMENT.matcher(stripped).replaceAll("");
    return stripped;
  }

  private static boolean isDirectSubstitutionPlaceholder(String sql) {
    String stripped = LINE_COMMENT.matcher(STRING_LITERAL.matcher(sql).replaceAll("''"))
        .replaceAll("").trim();
    return DIRECT_SUBSTITUTION_PLACEHOLDER.matcher(stripped).matches();
  }

  private static boolean containsWord(String text, String word) {
    return Pattern.compile("\\b" + Pattern.quote(word) + "\\b").matcher(text).find();
  }
}
