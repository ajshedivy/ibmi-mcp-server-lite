package com.ibm.ibmi.mcp.sql;

import java.util.List;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import java.util.stream.Collectors;

import com.ibm.ibmi.mcp.config.ParameterConfig;

/**
 * Validates parameter values against their declared constraints (min, max, minLength,
 * maxLength, pattern, enum). Invoked after type coercion but before SQL binding to ensure
 * all constraint violations are caught with clear error messages before any database
 * operation occurs.
 *
 * <p>Validation semantics match the reference Node.js implementation:
 * <ul>
 *   <li><b>enum</b>: Checked after coercion. For arrays, validates each element. Takes
 *       precedence over other constraints (a parameter with enum ignores min/max/pattern).
 *       <b>Enum comparison semantics:</b> Both the incoming coerced value and the enum values
 *       from YAML are normalized using the same coercion rules (boolean→1/0, numbers→int/double
 *       based on parameter type) before comparison. This ensures that a numeric enum [1,2,3]
 *       matches an incoming 2, and a boolean parameter with enum [true,false] matches incoming
 *       true (coerced to 1) against the enum's true (also coerced to 1).
 *   <li><b>min/max</b>: Inclusive numeric bounds for integer/float types. Compared via
 *       {@code doubleValue()} for consistency with {@code JsonSchemaBuilder.putRange}.
 *   <li><b>minLength/maxLength</b>: For strings, bounds {@code String.length()}. For arrays,
 *       bounds element count (maps to JSON Schema minItems/maxItems).
 *   <li><b>pattern</b>: Full regex match (not substring) applied to string values only.
 *       Invalid patterns in YAML surface as validation errors with the regex syntax issue.
 * </ul>
 *
 * <p>All validation errors throw {@link IllegalArgumentException} with a message naming the
 * parameter and constraint, surfaced to the client as an error {@code CallToolResult} by
 * {@code SqlToolHandler}.
 */
public final class ParameterValidator {

  private ParameterValidator() {}

  /**
   * Validates a coerced parameter value against its declared constraints.
   *
   * @param param the parameter configuration with constraints
   * @param coercedValue the value after type coercion (e.g., string → int, boolean → 1/0)
   * @throws IllegalArgumentException if any constraint is violated
   */
  public static void validate(ParameterConfig param, Object coercedValue) {
    // Enum takes precedence over all other constraints
    if (param.enumValues() != null && !param.enumValues().isEmpty()) {
      validateEnum(param, coercedValue);
      return; // enum replaces other constraints
    }

    // Type-specific constraints
    switch (param.type()) {
      case "string":
        validateString(param, coercedValue);
        break;
      case "integer":
      case "float":
        validateNumeric(param, coercedValue);
        break;
      case "array":
        validateArray(param, coercedValue);
        break;
    }
  }

  /**
   * Validates enum constraints by comparing normalized values. Both the incoming coerced
   * parameter value and the enum values from YAML are normalized using the same type
   * coercion rules (boolean→1/0, numbers→int/double based on parameter type) before
   * comparison. This ensures consistent matching: a numeric enum [1,2,3] matches incoming
   * 2, and a boolean parameter with enum [true,false] matches incoming true (coerced to 1)
   * against the enum's true (also coerced to 1).
   */
  private static void validateEnum(ParameterConfig param, Object value) {
    // Normalize enum values using the same coercion rules as incoming values
    List<Object> normalizedEnumValues = param.enumValues().stream()
        .map(enumVal -> coerceEnumValue(param, enumVal))
        .toList();

    if (value instanceof List<?> list) {
      // Array parameter: validate each element against normalized enum
      for (int i = 0; i < list.size(); i++) {
        Object element = list.get(i);
        if (!normalizedEnumValues.contains(element)) {
          throw new IllegalArgumentException(
              String.format("Parameter '%s' element at index %d must be one of: %s, got: %s",
                  param.name(), i, formatEnumValues(param.enumValues()), element));
        }
      }
    } else {
      // Scalar parameter: validate against normalized enum
      if (!normalizedEnumValues.contains(value)) {
        throw new IllegalArgumentException(
            String.format("Parameter '%s' must be one of: %s, got: %s",
                param.name(), formatEnumValues(param.enumValues()), value));
      }
    }
  }

  /**
   * Coerces an enum value from YAML using the same normalization rules as incoming
   * parameter values. This ensures enum comparison consistency.
   */
  private static Object coerceEnumValue(ParameterConfig param, Object enumValue) {
    switch (param.type()) {
      case "integer":
        if (enumValue instanceof Number n) return n.intValue();
        if (enumValue instanceof Boolean b) return b ? 1 : 0;
        try {
          return Integer.parseInt(String.valueOf(enumValue).trim());
        } catch (NumberFormatException e) {
          return enumValue; // Keep as-is if not parseable
        }
      case "float":
        if (enumValue instanceof Number n) return n.doubleValue();
        if (enumValue instanceof Boolean b) return b ? 1.0 : 0.0;
        try {
          return Double.parseDouble(String.valueOf(enumValue).trim());
        } catch (NumberFormatException e) {
          return enumValue; // Keep as-is if not parseable
        }
      case "boolean":
        // Boolean parameters are coerced to 1/0, so normalize enum values the same way
        if (enumValue instanceof Boolean b) return b ? 1 : 0;
        if (enumValue instanceof Number n) return n.intValue() != 0 ? 1 : 0;
        return enumValue; // Keep as-is for non-boolean enum values
      default:
        // String and array types: no coercion needed
        return enumValue;
    }
  }

  private static void validateString(ParameterConfig param, Object value) {
    if (!(value instanceof String str)) {
      throw new IllegalStateException(
          String.format("Internal error: validateString called with non-string value for parameter '%s': %s",
              param.name(), value.getClass().getName()));
    }

    if (param.minLength() != null && str.length() < param.minLength()) {
      throw new IllegalArgumentException(
          String.format("Parameter '%s' must be at least %d characters, got %d",
              param.name(), param.minLength(), str.length()));
    }

    if (param.maxLength() != null && str.length() > param.maxLength()) {
      throw new IllegalArgumentException(
          String.format("Parameter '%s' must be at most %d characters, got %d",
              param.name(), param.maxLength(), str.length()));
    }

    if (param.pattern() != null && !param.pattern().isEmpty()) {
      try {
        Pattern regex = Pattern.compile(param.pattern());
        if (!regex.matcher(str).matches()) {
          throw new IllegalArgumentException(
              String.format("Parameter '%s' must match pattern '%s', got: %s",
                  param.name(), param.pattern(), str));
        }
      } catch (PatternSyntaxException e) {
        throw new IllegalArgumentException(
            String.format("Parameter '%s' has invalid regex pattern '%s': %s",
                param.name(), param.pattern(), e.getMessage()));
      }
    }
  }

  private static void validateNumeric(ParameterConfig param, Object value) {
    if (!(value instanceof Number num)) {
      throw new IllegalStateException(
          String.format("Internal error: validateNumeric called with non-numeric value for parameter '%s': %s",
              param.name(), value.getClass().getName()));
    }

    double val = num.doubleValue();

    if (param.min() != null && val < param.min().doubleValue()) {
      throw new IllegalArgumentException(
          String.format("Parameter '%s' must be >= %s, got: %s",
              param.name(), param.min(), value));
    }

    if (param.max() != null && val > param.max().doubleValue()) {
      throw new IllegalArgumentException(
          String.format("Parameter '%s' must be <= %s, got: %s",
              param.name(), param.max(), value));
    }
  }

  private static void validateArray(ParameterConfig param, Object value) {
    if (!(value instanceof List<?> list)) {
      throw new IllegalStateException(
          String.format("Internal error: validateArray called with non-array value for parameter '%s': %s",
              param.name(), value.getClass().getName()));
    }

    int size = list.size();

    if (param.minLength() != null && size < param.minLength()) {
      throw new IllegalArgumentException(
          String.format("Parameter '%s' must have at least %d elements, got %d",
              param.name(), param.minLength(), size));
    }

    if (param.maxLength() != null && size > param.maxLength()) {
      throw new IllegalArgumentException(
          String.format("Parameter '%s' must have at most %d elements, got %d",
              param.name(), param.maxLength(), size));
    }
    
  }

  private static String formatEnumValues(List<Object> enumValues) {
    return enumValues.stream()
        .map(Object::toString)
        .collect(Collectors.joining(", "));
  }
}
