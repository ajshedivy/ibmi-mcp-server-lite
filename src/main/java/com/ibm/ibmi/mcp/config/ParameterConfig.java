package com.ibm.ibmi.mcp.config;

import java.util.List;

/**
 * One entry of a tool's {@code parameters:} list.
 *
 * <p>Supported types mirror the reference implementation: {@code string}, {@code integer},
 * {@code float}, {@code boolean}, {@code array} (with {@code itemType} for elements).
 */
public record ParameterConfig(
    String name,
    String type,
    String description,
    Object defaultValue,
    Boolean required,
    String itemType,
    Number min,
    Number max,
    Integer minLength,
    Integer maxLength,
    List<Object> enumValues,
    String pattern) {

  public static final List<String> TYPES = List.of("string", "integer", "float", "boolean", "array");

  /**
   * Reference-implementation rule: a parameter appears in the JSON Schema {@code required}
   * array unless {@code required: false} was set or a {@code default} is provided.
   */
  public boolean isRequiredInSchema() {
    return required != Boolean.FALSE && defaultValue == null;
  }
}
