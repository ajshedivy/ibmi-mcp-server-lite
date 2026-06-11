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
 * enum    → "enum": [...] (replaces other constraints)
 * default → "default": value (parameter becomes optional)
 * </pre>
 *
 * The schema object is closed ({@code additionalProperties: false}).
 */
public final class JsonSchemaBuilder {

  private final ObjectMapper mapper;

  public JsonSchemaBuilder(ObjectMapper mapper) {
    this.mapper = mapper;
  }

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

  private ObjectNode buildProperty(ParameterConfig param) {
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

    if (param.enumValues() != null && !param.enumValues().isEmpty() && !"boolean".equals(param.type())) {
      ArrayNode enumNode = prop.putArray("enum");
      param.enumValues().forEach(v -> enumNode.add(mapper.valueToTree(v)));
    }
    if (param.description() != null) {
      prop.put("description", param.description());
    }
    if (param.defaultValue() != null) {
      prop.set("default", mapper.valueToTree(param.defaultValue()));
    }
    return prop;
  }

  private static void putRange(ObjectNode prop, ParameterConfig param) {
    if (param.min() != null) prop.put("minimum", param.min().doubleValue());
    if (param.max() != null) prop.put("maximum", param.max().doubleValue());
  }

  private static String jsonType(String paramType) {
    return switch (paramType) {
      case "integer" -> "integer";
      case "float" -> "number";
      case "boolean" -> "boolean";
      default -> "string";
    };
  }
}
