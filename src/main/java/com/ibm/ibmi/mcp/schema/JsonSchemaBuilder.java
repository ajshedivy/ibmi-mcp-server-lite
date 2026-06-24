package com.ibm.ibmi.mcp.schema;

import java.util.List;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.ibm.ibmi.mcp.config.ParameterConfig;

/**
 * Translates a tool's YAML {@code parameters:} list into the JSON Schema string used as the
 * MCP tool {@code inputSchema}.
 *
 * <p>Mapping (mirrors the reference implementation):
 *
 * <pre>
 * string  → {"type":"string"}  + minLength / maxLength / pattern
 * integer → {"type":"integer"} + minimum (min) / maximum (max)
 * float   → {"type":"number"}  + minimum / maximum
 * boolean → {"type":"boolean"}
 * array   → {"type":"array","items":{"type":itemType|"string"}} + minItems / maxItems
 * enum (non-boolean, replaces base type):
 *   1 value       → {"const": value}
 *   all strings   → {"type":"string","enum":[...]}
 *   mixed/numeric → {"anyOf":[{"const":v1},...]}
 * default → "default": value (parameter becomes optional)
 * </pre>
 *
 * The schema object is closed ({@code additionalProperties: false}).
 */
public final class JsonSchemaBuilder {

  private final ObjectMapper mapper;

  /** Creates a builder that serializes schemas with the given {@link ObjectMapper}. */
  public JsonSchemaBuilder(ObjectMapper mapper) {
    this.mapper = mapper;
  }

  /**
   * Builds a closed JSON Schema object ({@code additionalProperties: false}) for a tool's
   * parameters and returns it as a JSON string suitable for MCP {@code inputSchema}.
   */
  public String buildInputSchema(List<ParameterConfig> parameters) {
    ObjectNode schema = mapper.createObjectNode();
    schema.put("type", "object");
    ObjectNode properties = schema.putObject("properties");
    ArrayNode required = mapper.createArrayNode();

    for (ParameterConfig param : parameters) {
      properties.set(param.name(), buildProperty(param));
      if (param.isRequiredInSchema()) {
        required.add(param.name());
      }
    }
    if (!required.isEmpty()) {
      schema.set("required", required);
    }
    schema.put("additionalProperties", false);
    try {
      return mapper.writeValueAsString(schema);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("Failed to serialize input schema", e);
    }
  }

  /** Builds the JSON Schema for one parameter, including description and default if present. */
  private ObjectNode buildProperty(ParameterConfig param) {
    ObjectNode prop = hasEnum(param)
        ? buildEnumProperty(param.enumValues())
        : buildBaseTypeProperty(param);

    String description = hasEnum(param) ? buildDescription(param) : param.description();

    if (description != null) {
      prop.put("description", description);
    }
    if (param.defaultValue() != null) {
      prop.set("default", mapper.valueToTree(param.defaultValue()));
    }
    return prop;
  }

  /**
   * Builds the parameter description with a "Must be one of: ..." suffix when enum values
   * are present.
   */
  private static String buildDescription(ParameterConfig param) {
    String formattedValues = param.enumValues().stream()
        .map(JsonSchemaBuilder::formatEnumValueForDescription)
        .reduce((a, b) -> a + ", " + b)
        .orElse("");

    String enumClause = "Must be one of: " + formattedValues;

    String base = param.description();
    if (base == null || base.isBlank()) {
      return enumClause;
    }

    String result = base.trim();
    if (!result.endsWith(".") && !result.endsWith("?") && !result.endsWith("!")) {
      result += ".";
    }
    return result + " " + enumClause;
  }

  private static String formatEnumValueForDescription(Object value) {
    if (value instanceof String s) {
      return "'" + s + "'";
    }
    return String.valueOf(value);
  }

  /** Returns {@code true} when the parameter has enum values (ignored for booleans). */
  private static boolean hasEnum(ParameterConfig param) {
    return param.enumValues() != null
        && !param.enumValues().isEmpty()
        && !"boolean".equals(param.type());
  }

  /**
   * Builds enum constraint schema: {@code const} for one value, {@code type}+{@code enum}
   * for all strings, or {@code anyOf} of {@code const} objects for numeric or mixed values.
   */
  private ObjectNode buildEnumProperty(List<Object> enumValues) {
    ObjectNode prop = mapper.createObjectNode();
    if (enumValues.size() == 1) {
      prop.set("const", mapper.valueToTree(enumValues.get(0)));
      return prop;
    }
    if (enumValues.stream().allMatch(String.class::isInstance)) {
      prop.put("type", "string");
      ArrayNode enumNode = prop.putArray("enum");
      enumValues.forEach(v -> enumNode.add((String) v));
      return prop;
    }
    ArrayNode anyOf = prop.putArray("anyOf");
    for (Object value : enumValues) {
      anyOf.addObject().set("const", mapper.valueToTree(value));
    }
    return prop;
  }

  /** Builds type-specific JSON Schema ({@code string}, {@code integer}, {@code array}, etc.). */
  private ObjectNode buildBaseTypeProperty(ParameterConfig param) {
    ObjectNode prop = mapper.createObjectNode();
    switch (param.type()) {
      case "string" -> {
        prop.put("type", "string");
        if (param.minLength() != null) prop.put("minLength", param.minLength());
        if (param.maxLength() != null) prop.put("maxLength", param.maxLength());
        if (param.pattern() != null) prop.put("pattern", param.pattern());
      }
      case "integer" -> {
        prop.put("type", "integer");
        putRange(prop, param);
      }
      case "float" -> {
        prop.put("type", "number");
        putRange(prop, param);
      }
      case "boolean" -> prop.put("type", "boolean");
      case "array" -> {
        prop.put("type", "array");
        ObjectNode items = prop.putObject("items");
        items.put("type", jsonType(param.itemType() == null ? "string" : param.itemType()));
        if (param.minLength() != null) prop.put("minItems", param.minLength());
        if (param.maxLength() != null) prop.put("maxItems", param.maxLength());
      }
      default -> throw new IllegalArgumentException("Unsupported parameter type: " + param.type());
    }
    return prop;
  }

  /** Adds {@code minimum} and {@code maximum} from the parameter's {@code min}/{@code max}. */
  private static void putRange(ObjectNode prop, ParameterConfig param) {
    if (param.min() != null) prop.put("minimum", param.min().doubleValue());
    if (param.max() != null) prop.put("maximum", param.max().doubleValue());
  }

  /** Maps a YAML parameter or item type to its JSON Schema type keyword. */
  private static String jsonType(String paramType) {
    return switch (paramType) {
      case "integer" -> "integer";
      case "float" -> "number";
      case "boolean" -> "boolean";
      default -> "string";
    };
  }

}
