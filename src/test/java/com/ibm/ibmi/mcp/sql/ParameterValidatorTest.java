package com.ibm.ibmi.mcp.sql;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.ibm.ibmi.mcp.config.ParameterConfig;

/**
 * Tests for {@link ParameterValidator} covering all constraint types: enum, min/max,
 * minLength/maxLength, and pattern. Each test validates both pass and fail cases to ensure
 * constraints are enforced correctly and error messages are clear.
 */
class ParameterValidatorTest {

  // Helper to create ParameterConfig with specific constraints
  private static ParameterConfig param(String name, String type, Number min, Number max,
      Integer minLength, Integer maxLength, List<Object> enumValues, String pattern) {
    return new ParameterConfig(name, type, null, null, null, null,
        min, max, minLength, maxLength, enumValues, pattern);
  }

  // ==================== ENUM VALIDATION ====================

  @Test
  void enumValidation_scalarInteger_validValue() {
    ParameterConfig param = param("status", "integer", null, null, null, null,
        List.of(1, 2, 3), null);
    assertDoesNotThrow(() -> ParameterValidator.validate(param, 2));
  }

  @Test
  void enumValidation_scalarInteger_invalidValue() {
    ParameterConfig param = param("status", "integer", null, null, null, null,
        List.of(1, 2, 3), null);
    IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
        () -> ParameterValidator.validate(param, 5));
    assertTrue(e.getMessage().contains("must be one of: 1, 2, 3"));
    assertTrue(e.getMessage().contains("got: 5"));
  }

  @Test
  void enumValidation_scalarString_validValue() {
    ParameterConfig param = param("color", "string", null, null, null, null,
        List.of("RED", "GREEN", "BLUE"), null);
    assertDoesNotThrow(() -> ParameterValidator.validate(param, "GREEN"));
  }

  @Test
  void enumValidation_scalarString_invalidValue() {
    ParameterConfig param = param("color", "string", null, null, null, null,
        List.of("RED", "GREEN", "BLUE"), null);
    IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
        () -> ParameterValidator.validate(param, "YELLOW"));
    assertTrue(e.getMessage().contains("must be one of: RED, GREEN, BLUE"));
    assertTrue(e.getMessage().contains("got: YELLOW"));
  }

  @Test
  void enumValidation_array_allElementsValid() {
    ParameterConfig param = param("statuses", "array", null, null, null, null,
        List.of("ACTIVE", "PENDING", "CLOSED"), null);
    assertDoesNotThrow(() -> ParameterValidator.validate(param,
        List.of("ACTIVE", "PENDING", "ACTIVE")));
  }

  @Test
  void enumValidation_array_oneElementInvalid() {
    ParameterConfig param = param("statuses", "array", null, null, null, null,
        List.of("ACTIVE", "PENDING", "CLOSED"), null);
    IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
        () -> ParameterValidator.validate(param, List.of("ACTIVE", "INVALID", "PENDING")));
    assertTrue(e.getMessage().contains("element at index 1"));
    assertTrue(e.getMessage().contains("must be one of: ACTIVE, PENDING, CLOSED"));
    assertTrue(e.getMessage().contains("got: INVALID"));
  }

  @Test
  void enumValidation_takesPrecedenceOverOtherConstraints() {
    // Enum should be checked even if min/max/pattern are also defined
    ParameterConfig param = param("value", "integer", 0, 100, null, null,
        List.of(5, 10, 15), null);
    assertDoesNotThrow(() -> ParameterValidator.validate(param, 10));
    IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
        () -> ParameterValidator.validate(param, 50)); // within min/max but not in enum
    assertTrue(e.getMessage().contains("must be one of: 5, 10, 15"));
  }

  // ==================== NUMERIC MIN/MAX VALIDATION ====================

  @Test
  void numericValidation_integer_withinRange() {
    ParameterConfig param = param("limit", "integer", 1, 100, null, null, null, null);
    assertDoesNotThrow(() -> ParameterValidator.validate(param, 50));
    assertDoesNotThrow(() -> ParameterValidator.validate(param, 1)); // min boundary
    assertDoesNotThrow(() -> ParameterValidator.validate(param, 100)); // max boundary
  }

  @Test
  void numericValidation_integer_belowMin() {
    ParameterConfig param = param("limit", "integer", 1, 100, null, null, null, null);
    IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
        () -> ParameterValidator.validate(param, 0));
    assertTrue(e.getMessage().contains("must be >= 1"));
    assertTrue(e.getMessage().contains("got: 0"));
  }

  @Test
  void numericValidation_integer_aboveMax() {
    ParameterConfig param = param("limit", "integer", 1, 100, null, null, null, null);
    IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
        () -> ParameterValidator.validate(param, 101));
    assertTrue(e.getMessage().contains("must be <= 100"));
    assertTrue(e.getMessage().contains("got: 101"));
  }

  @Test
  void numericValidation_float_withinRange() {
    ParameterConfig param = param("rate", "float", 0.0, 1.0, null, null, null, null);
    assertDoesNotThrow(() -> ParameterValidator.validate(param, 0.5));
    assertDoesNotThrow(() -> ParameterValidator.validate(param, 0.0)); // min boundary
    assertDoesNotThrow(() -> ParameterValidator.validate(param, 1.0)); // max boundary
  }

  @Test
  void numericValidation_float_belowMin() {
    ParameterConfig param = param("rate", "float", 0.0, 1.0, null, null, null, null);
    IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
        () -> ParameterValidator.validate(param, -0.1));
    assertTrue(e.getMessage().contains("must be >= 0.0"));
  }

  @Test
  void numericValidation_float_aboveMax() {
    ParameterConfig param = param("rate", "float", 0.0, 1.0, null, null, null, null);
    IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
        () -> ParameterValidator.validate(param, 1.1));
    assertTrue(e.getMessage().contains("must be <= 1.0"));
  }

  @Test
  void numericValidation_onlyMin() {
    ParameterConfig param = param("positive", "integer", 1, null, null, null, null, null);
    assertDoesNotThrow(() -> ParameterValidator.validate(param, 1000));
    IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
        () -> ParameterValidator.validate(param, 0));
    assertTrue(e.getMessage().contains("must be >= 1"));
  }

  @Test
  void numericValidation_onlyMax() {
    ParameterConfig param = param("small", "integer", null, 10, null, null, null, null);
    assertDoesNotThrow(() -> ParameterValidator.validate(param, -1000));
    IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
        () -> ParameterValidator.validate(param, 11));
    assertTrue(e.getMessage().contains("must be <= 10"));
  }

  // ==================== STRING LENGTH VALIDATION ====================

  @Test
  void stringValidation_length_withinRange() {
    ParameterConfig param = param("name", "string", null, null, 2, 10, null, null);
    assertDoesNotThrow(() -> ParameterValidator.validate(param, "Alice"));
    assertDoesNotThrow(() -> ParameterValidator.validate(param, "AB")); // min boundary
    assertDoesNotThrow(() -> ParameterValidator.validate(param, "1234567890")); // max boundary
  }

  @Test
  void stringValidation_length_tooShort() {
    ParameterConfig param = param("name", "string", null, null, 2, 10, null, null);
    IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
        () -> ParameterValidator.validate(param, "A"));
    assertTrue(e.getMessage().contains("must be at least 2 characters"));
    assertTrue(e.getMessage().contains("got 1"));
  }

  @Test
  void stringValidation_length_tooLong() {
    ParameterConfig param = param("name", "string", null, null, 2, 10, null, null);
    IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
        () -> ParameterValidator.validate(param, "12345678901"));
    assertTrue(e.getMessage().contains("must be at most 10 characters"));
    assertTrue(e.getMessage().contains("got 11"));
  }

  @Test
  void stringValidation_onlyMinLength() {
    ParameterConfig param = param("description", "string", null, null, 5, null, null, null);
    assertDoesNotThrow(() -> ParameterValidator.validate(param, "Hello World"));
    IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
        () -> ParameterValidator.validate(param, "Hi"));
    assertTrue(e.getMessage().contains("must be at least 5 characters"));
  }

  @Test
  void stringValidation_onlyMaxLength() {
    ParameterConfig param = param("code", "string", null, null, null, 5, null, null);
    assertDoesNotThrow(() -> ParameterValidator.validate(param, "ABC"));
    IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
        () -> ParameterValidator.validate(param, "ABCDEF"));
    assertTrue(e.getMessage().contains("must be at most 5 characters"));
  }

  // ==================== PATTERN VALIDATION ====================

  @Test
  void patternValidation_matches() {
    ParameterConfig param = param("code", "string", null, null, null, null, null, "^[A-Z]{3}$");
    assertDoesNotThrow(() -> ParameterValidator.validate(param, "ABC"));
    assertDoesNotThrow(() -> ParameterValidator.validate(param, "XYZ"));
  }

  @Test
  void patternValidation_doesNotMatch() {
    ParameterConfig param = param("code", "string", null, null, null, null, null, "^[A-Z]{3}$");
    IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
        () -> ParameterValidator.validate(param, "AB"));
    assertTrue(e.getMessage().contains("must match pattern '^[A-Z]{3}$'"));
    assertTrue(e.getMessage().contains("got: AB"));
  }

  @Test
  void patternValidation_complexPattern() {
    // Email-like pattern
    ParameterConfig param = param("email", "string", null, null, null, null, null,
        "^[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}$");
    assertDoesNotThrow(() -> ParameterValidator.validate(param, "user@example.com"));
    IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
        () -> ParameterValidator.validate(param, "invalid-email"));
    assertTrue(e.getMessage().contains("must match pattern"));
  }

  @Test
  void patternValidation_invalidRegex() {
    ParameterConfig param = param("bad", "string", null, null, null, null, null, "[unclosed");
    IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
        () -> ParameterValidator.validate(param, "test"));
    assertTrue(e.getMessage().contains("invalid regex pattern"));
    assertTrue(e.getMessage().contains("[unclosed"));
  }

  // ==================== ARRAY LENGTH VALIDATION ====================

  @Test
  void arrayValidation_length_withinRange() {
    ParameterConfig param = param("items", "array", null, null, 1, 5, null, null);
    assertDoesNotThrow(() -> ParameterValidator.validate(param, List.of("A", "B", "C")));
    assertDoesNotThrow(() -> ParameterValidator.validate(param, List.of("A"))); // min boundary
    assertDoesNotThrow(() -> ParameterValidator.validate(param, List.of("A", "B", "C", "D", "E"))); // max boundary
  }

  @Test
  void arrayValidation_length_tooFew() {
    ParameterConfig param = param("items", "array", null, null, 2, 5, null, null);
    IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
        () -> ParameterValidator.validate(param, List.of("A")));
    assertTrue(e.getMessage().contains("must have at least 2 elements"));
    assertTrue(e.getMessage().contains("got 1"));
  }

  @Test
  void arrayValidation_length_tooMany() {
    ParameterConfig param = param("items", "array", null, null, 1, 3, null, null);
    IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
        () -> ParameterValidator.validate(param, List.of("A", "B", "C", "D")));
    assertTrue(e.getMessage().contains("must have at most 3 elements"));
    assertTrue(e.getMessage().contains("got 4"));
  }

  @Test
  void arrayValidation_emptyArray_allowedIfNoMinLength() {
    ParameterConfig param = param("optional_items", "array", null, null, null, 5, null, null);
    assertDoesNotThrow(() -> ParameterValidator.validate(param, List.of()));
  }

  @Test
  void arrayValidation_emptyArray_rejectedIfMinLength() {
    ParameterConfig param = param("required_items", "array", null, null, 1, 5, null, null);
    IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
        () -> ParameterValidator.validate(param, List.of()));
    assertTrue(e.getMessage().contains("must have at least 1 elements"));
  }

  // ==================== NO CONSTRAINTS ====================

  @Test
  void noConstraints_alwaysValid() {
    ParameterConfig param = param("any", "string", null, null, null, null, null, null);
    assertDoesNotThrow(() -> ParameterValidator.validate(param, ""));
    assertDoesNotThrow(() -> ParameterValidator.validate(param, "any value"));
    assertDoesNotThrow(() -> ParameterValidator.validate(param, "x".repeat(10000)));
  }

  @Test
  void booleanType_noConstraintsApplied() {
    // Boolean parameters (coerced to 1/0) have no constraints beyond type
    ParameterConfig param = param("flag", "boolean", null, null, null, null, null, null);
    assertDoesNotThrow(() -> ParameterValidator.validate(param, 1));
    assertDoesNotThrow(() -> ParameterValidator.validate(param, 0));
  }

  // ==================== COMBINED CONSTRAINTS ====================

  @Test
  void combinedConstraints_stringWithLengthAndPattern() {
    // Postal code: 5 digits
    ParameterConfig param = param("zip", "string", null, null, 5, 5, null, "^\\d{5}$");
    assertDoesNotThrow(() -> ParameterValidator.validate(param, "12345"));
    
    // Too short
    IllegalArgumentException e1 = assertThrows(IllegalArgumentException.class,
        () -> ParameterValidator.validate(param, "1234"));
    assertTrue(e1.getMessage().contains("must be at least 5 characters"));
    
    // Right length but wrong pattern
    IllegalArgumentException e2 = assertThrows(IllegalArgumentException.class,
        () -> ParameterValidator.validate(param, "ABCDE"));
    assertTrue(e2.getMessage().contains("must match pattern"));
  }

  @Test
  void combinedConstraints_numericWithMinAndMax() {
    ParameterConfig param = param("percentage", "integer", 0, 100, null, null, null, null);
    assertDoesNotThrow(() -> ParameterValidator.validate(param, 0));
    assertDoesNotThrow(() -> ParameterValidator.validate(param, 50));
    assertDoesNotThrow(() -> ParameterValidator.validate(param, 100));
    
    IllegalArgumentException e1 = assertThrows(IllegalArgumentException.class,
        () -> ParameterValidator.validate(param, -1));
    assertTrue(e1.getMessage().contains("must be >= 0"));
    
    IllegalArgumentException e2 = assertThrows(IllegalArgumentException.class,
        () -> ParameterValidator.validate(param, 101));
    assertTrue(e2.getMessage().contains("must be <= 100"));
  }
}