package com.ibm.ibmi.mcp.sql;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.ibm.ibmi.mcp.config.SecurityConfig;

class SqlSecurityValidatorTest {

  @Test
  void selectAndWithPassByDefault() {
    assertDoesNotThrow(() -> SqlSecurityValidator.validate(
        "SELECT * FROM QSYS2.SYSTEM_STATUS_INFO", SecurityConfig.DEFAULTS));
    assertDoesNotThrow(() -> SqlSecurityValidator.validate(
        "WITH X AS (SELECT 1 FROM SYSIBM.SYSDUMMY1) SELECT * FROM X", SecurityConfig.DEFAULTS));
  }

  @Test
  void writesAreRejectedByDefault() {
    assertThrows(SecurityException.class, () -> SqlSecurityValidator.validate(
        "DELETE FROM SAMPLE.EMPLOYEE", SecurityConfig.DEFAULTS));
    assertThrows(SecurityException.class, () -> SqlSecurityValidator.validate(
        "SELECT 1 FROM SYSIBM.SYSDUMMY1; DROP TABLE T", SecurityConfig.DEFAULTS));
  }

  @Test
  void readOnlyFalseAllowsWrites() {
    SecurityConfig writable = new SecurityConfig(false, null, null);
    assertDoesNotThrow(() -> SqlSecurityValidator.validate(
        "INSERT INTO T VALUES (1)", writable));
  }

  @Test
  void dangerousWordInsideStringLiteralIsIgnored() {
    assertDoesNotThrow(() -> SqlSecurityValidator.validate(
        "SELECT * FROM T WHERE NOTE = 'PLEASE DROP ME A LINE'", SecurityConfig.DEFAULTS));
  }

  @Test
  void dangerousWordAsPartOfIdentifierIsIgnored() {
    // PROJECT_START_DATE contains START but is not the START keyword
    assertDoesNotThrow(() -> SqlSecurityValidator.validate(
        "SELECT PROJECT_START_DATE FROM SAMPLE.PROJECT", SecurityConfig.DEFAULTS));
  }

  @Test
  void forbiddenKeywordsApplyEvenWhenWritable() {
    SecurityConfig config = new SecurityConfig(false, null, List.of("TRUNCATE"));
    assertThrows(SecurityException.class, () -> SqlSecurityValidator.validate(
        "TRUNCATE TABLE T", config));
  }

  @Test
  void maxQueryLengthEnforced() {
    SecurityConfig config = new SecurityConfig(null, 20, null);
    assertThrows(SecurityException.class, () -> SqlSecurityValidator.validate(
        "SELECT 'way too long for the limit' FROM SYSIBM.SYSDUMMY1", config));
  }

  @Test
  void writeKeywordInsideBlockCommentIgnored() {
    assertDoesNotThrow(() -> SqlSecurityValidator.validate(
        "SELECT 1 /* DELETE */ FROM T", SecurityConfig.DEFAULTS));
  }

  @Test
  void writeKeywordInsideDoubleQuotedIdentifierIgnored() {
    assertDoesNotThrow(() -> SqlSecurityValidator.validate(
        "SELECT \"DELETE\" FROM T", SecurityConfig.DEFAULTS));
  }

  @Test
  void multiStatementWithWriteRejected() {
    assertThrows(SecurityException.class, () -> SqlSecurityValidator.validate(
        "SELECT 1; DELETE FROM T", SecurityConfig.DEFAULTS));
  }

  @Test
  void callProcedureRejected() {
    assertThrows(SecurityException.class, () -> SqlSecurityValidator.validate(
        "CALL SOMEPROC()", SecurityConfig.DEFAULTS));
  }

  @Test
  void cteWithSubSelectPasses() {
    assertDoesNotThrow(() -> SqlSecurityValidator.validate(
        "WITH X AS (SELECT 1 FROM SYSIBM.SYSDUMMY1) SELECT * FROM X",
        SecurityConfig.DEFAULTS));
  }

  @Test
  void clLabelPrefixSelectPasses() {
    assertDoesNotThrow(() -> SqlSecurityValidator.validate(
        "MYLABEL: SELECT 1 FROM SYSIBM.SYSDUMMY1", SecurityConfig.DEFAULTS));
  }

  @Test
  void execSqlPrefixSelectPasses() {
    assertDoesNotThrow(() -> SqlSecurityValidator.validate(
        "EXEC SQL SELECT 1 FROM SYSIBM.SYSDUMMY1", SecurityConfig.DEFAULTS));
  }

  @Test
  void leadingCommentBeforeExecSqlSelectPasses() {
    assertDoesNotThrow(() -> SqlSecurityValidator.validate(
        "/* comment */ EXEC SQL SELECT 1 FROM SYSIBM.SYSDUMMY1", SecurityConfig.DEFAULTS));
  }

  @Test
  void leadingCommentBeforeLabelSelectPasses() {
    assertDoesNotThrow(() -> SqlSecurityValidator.validate(
        "-- comment\nMYLABEL: SELECT 1 FROM SYSIBM.SYSDUMMY1", SecurityConfig.DEFAULTS));
  }

  @Test
  void escapedQuoteStringContainingDropPasses() {
    assertDoesNotThrow(() -> SqlSecurityValidator.validate(
        "SELECT 'can''t DROP'", SecurityConfig.DEFAULTS));
  }

  @Test
  void forbiddenKeywordTokenScanIgnoresStringLiteral() {
    SecurityConfig config = new SecurityConfig(null, null, List.of("FORBIDDEN"));
    assertDoesNotThrow(() -> SqlSecurityValidator.validate(
        "SELECT 'FORBIDDEN' FROM T", config));
    assertThrows(SecurityException.class, () -> SqlSecurityValidator.validate(
        "SELECT FORBIDDEN FROM T", config));
  }

  @Test
  void tokenizerFailureUsesRegexFallback() {
    assertThrows(SecurityException.class, () -> SqlSecurityValidator.validate(
        "SELECT 'unclosed; DELETE FROM T", SecurityConfig.DEFAULTS));
  }

  @Test
  void fallbackStripHandlesEscapedQuotesInString() {
    // Unclosed quoted identifier forces tokenizer failure; fallback must still strip
    // 'can''t DROP' as one literal (not treat the inner quote as closing the string).
    assertDoesNotThrow(() -> SqlSecurityValidator.validate(
        "SELECT 'can''t DROP' FROM T\"", SecurityConfig.DEFAULTS));
  }

  @Test
  void barePlaceholderSqlIsValidatedWhenCalledDirectly() {
    assertThrows(SecurityException.class, () -> SqlSecurityValidator.validate(
        ":sql", SecurityConfig.DEFAULTS));
  }
}
