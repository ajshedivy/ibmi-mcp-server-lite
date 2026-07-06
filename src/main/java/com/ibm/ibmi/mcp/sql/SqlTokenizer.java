package com.ibm.ibmi.mcp.sql;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Lightweight single-pass SQL tokenizer for security validation.
 * Handles string escapes, line/block comments, and double-quoted identifiers.
 */
public final class SqlTokenizer {

  public enum TokenType {
    STRING,
    LINE_COMMENT,
    BLOCK_COMMENT,
    QUOTED_NAME,
    WORD,
    SEMICOLON,
    PUNCT
  }

  public record Token(TokenType type, String value, int start, int end) {}

  private SqlTokenizer() {}

  public static List<Token> tokenize(String sql) {
    List<Token> tokens = new ArrayList<>();
    int i = 0;
    int len = sql.length();

    while (i < len) {
      char c = sql.charAt(i);

      if (Character.isWhitespace(c)) {
        i++;
        continue;
      }

      if (c == '\'') {
        int start = i;
        i = readStringLiteral(sql, i);
        tokens.add(new Token(TokenType.STRING, sql.substring(start, i), start, i));
        continue;
      }

      if (c == '"') {
        int start = i;
        i = readQuotedName(sql, i);
        tokens.add(new Token(TokenType.QUOTED_NAME, sql.substring(start, i), start, i));
        continue;
      }

      if (c == '-' && i + 1 < len && sql.charAt(i + 1) == '-') {
        int start = i;
        i += 2;
        while (i < len && sql.charAt(i) != '\n' && sql.charAt(i) != '\r') {
          i++;
        }
        tokens.add(new Token(TokenType.LINE_COMMENT, sql.substring(start, i), start, i));
        continue;
      }

      if (c == '/' && i + 1 < len && sql.charAt(i + 1) == '*') {
        int start = i;
        i += 2;
        while (i + 1 < len && !(sql.charAt(i) == '*' && sql.charAt(i + 1) == '/')) {
          i++;
        }
        if (i + 1 >= len) {
          throw new SqlTokenizerException("Unclosed block comment");
        }
        i += 2;
        tokens.add(new Token(TokenType.BLOCK_COMMENT, sql.substring(start, i), start, i));
        continue;
      }

      if (c == ';') {
        tokens.add(new Token(TokenType.SEMICOLON, ";", i, i + 1));
        i++;
        continue;
      }

      if (isWordChar(c)) {
        int start = i;
        i++;
        while (i < len && isWordChar(sql.charAt(i))) {
          i++;
        }
        String word = sql.substring(start, i).toUpperCase(Locale.ROOT);
        tokens.add(new Token(TokenType.WORD, word, start, i));
        continue;
      }

      tokens.add(new Token(TokenType.PUNCT, String.valueOf(c), i, i + 1));
      i++;
    }

    return tokens;
  }

  private static boolean isWordChar(char c) {
    return Character.isLetterOrDigit(c) || c == '_' || c == '$' || c == '@' || c == '#';
  }

  private static int readStringLiteral(String sql, int start) {
    int i = start + 1;
    while (i < sql.length()) {
      char c = sql.charAt(i);
      if (c == '\'') {
        if (i + 1 < sql.length() && sql.charAt(i + 1) == '\'') {
          i += 2;
        } else {
          return i + 1;
        }
      } else {
        i++;
      }
    }
    throw new SqlTokenizerException("Unclosed string literal");
  }

  private static int readQuotedName(String sql, int start) {
    int i = start + 1;
    while (i < sql.length()) {
      char c = sql.charAt(i);
      if (c == '"') {
        if (i + 1 < sql.length() && sql.charAt(i + 1) == '"') {
          i += 2;
        } else {
          return i + 1;
        }
      } else {
        i++;
      }
    }
    throw new SqlTokenizerException("Unclosed quoted identifier");
  }

  /** Split tokens into statement segments on semicolons at parenthesis depth zero. */
  public static List<List<Token>> splitStatements(List<Token> tokens) {
    List<List<Token>> statements = new ArrayList<>();
    List<Token> current = new ArrayList<>();
    int depth = 0;

    for (Token token : tokens) {
      if (token.type() == TokenType.PUNCT) {
        if ("(".equals(token.value())) {
          depth++;
        } else if (")".equals(token.value()) && depth > 0) {
          depth--;
        }
      }

      if (token.type() == TokenType.SEMICOLON && depth == 0) {
        if (!current.isEmpty()) {
          statements.add(List.copyOf(current));
          current = new ArrayList<>();
        }
        continue;
      }

      current.add(token);
    }

    if (!current.isEmpty()) {
      statements.add(List.copyOf(current));
    }

    return statements;
  }

  /**
   * Classify a statement segment by its leading statement-type keyword, skipping an
   * optional {@code EXEC SQL} prefix or {@code label:} prefix (vscode-db2i {@code Statement}
   * constructor behavior).
   */
  public static StatementType classifyStatement(List<Token> tokens) {
    int start = 0;
    if (tokens.size() >= 2
        && tokens.get(0).type() == TokenType.WORD
        && "EXEC".equals(tokens.get(0).value())
        && tokens.get(1).type() == TokenType.WORD
        && "SQL".equals(tokens.get(1).value())) {
      start = 2;
    } else if (tokens.size() >= 2
        && tokens.get(0).type() == TokenType.WORD
        && tokens.get(1).type() == TokenType.PUNCT
        && ":".equals(tokens.get(1).value())) {
      start = 2;
    }

    for (int i = start; i < tokens.size(); i++) {
      Token token = tokens.get(i);
      if (token.type() == TokenType.WORD) {
        return StatementType.fromWord(token.value());
      }
    }
    return StatementType.UNKNOWN;
  }
}
