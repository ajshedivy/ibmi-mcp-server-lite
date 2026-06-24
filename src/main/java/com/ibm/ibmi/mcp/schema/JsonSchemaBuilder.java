package com.ibm.ibmi.mcp.schema;

import java.util.List;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.ibm.ibmi.mcp.config.ParameterConfig;

/**
 * Builds JSON Schema strings for MCP tool {@code inputSchema} and {@code outputSchema}.
 *
 * <p>{@link #buildInputSchema} translates a tool's YAML {@code parameters:} list into the
 * input schema.
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
   * Fixed output schema for every SQL tool — mirrors the reference server's
   * {@code StandardSqlToolOutputSchema} ({@code schemas/tools.ts}).
   */
  public String buildOutputSchema() {
    ObjectNode schema = mapper.createObjectNode();
    schema.put("type", "object");
    schema.put("description", "Standard SQL execution payload");
    ObjectNode properties = schema.putObject("properties");

    ObjectNode success = properties.putObject("success");
    success.put("type", "boolean");
    success.put("description", "Whether the SQL execution succeeded");

    ObjectNode data = properties.putObject("data");
    data.put("type", "array");
    data.put("description", "Rows returned by the SQL query");
    ObjectNode dataItems = data.putObject("items"); 
    dataItems.put("type", "object");
    dataItems.set("additionalProperties", mapper.createObjectNode());

    ObjectNode error = properties.putObject("error");
    error.put("type", "string");
    error.put("description", "Error message when execution fails");

    ObjectNode errorCode = properties.putObject("errorCode");
    ArrayNode errorCodeOneOf = errorCode.putArray("oneOf");
    errorCodeOneOf.addObject().put("type", "string");
    errorCodeOneOf.addObject().put("type", "number");
    errorCode.put("description", "Machine-readable error code when execution fails");

    ObjectNode errorDetails = properties.putObject("errorDetails");
    errorDetails.put("description", "Any additional diagnostic information about failures");

    properties.set("metadata", buildMetadataSchema());

    ArrayNode required = schema.putArray("required");
    required.add("success");
    required.add("data");
    schema.put("additionalProperties", false);

    try {
      return mapper.writeValueAsString(schema);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("Failed to serialize output schema", e);
    }
  }

  private ObjectNode buildMetadataSchema() {
    ObjectNode metadata = mapper.createObjectNode();
    metadata.put("type", "object");
    metadata.put("description", "Execution metadata including performance and parameters");
    ObjectNode props = metadata.putObject("properties");

    ObjectNode executionTime = props.putObject("executionTime");
    executionTime.put("type", "number");
    executionTime.put("description", "Execution duration in milliseconds");

    ObjectNode rowCount = props.putObject("rowCount");
    rowCount.put("type", "number");
    rowCount.put("description", "Row count returned");

    ObjectNode affectedRows = props.putObject("affectedRows");
    affectedRows.put("type", "number");
    affectedRows.put("description", "Number of rows affected for write operations");

    ObjectNode columns = props.putObject("columns");
    columns.put("type", "array");
    columns.put("description", "Column metadata for the result set");
    ObjectNode colItems = columns.putObject("items");
    colItems.put("type", "object");
    colItems.put("description", "Database column metadata");
    ObjectNode colProps = colItems.putObject("properties");

    ObjectNode colName = colProps.putObject("name");
    colName.put("type", "string");
    colName.put("description", "Column name as returned by the database");
    ObjectNode colType = colProps.putObject("type");
    colType.put("type", "string");
    colType.put("description", "Database reported data type");
    ObjectNode colLabel = colProps.putObject("label");
    colLabel.put("type", "string");
    colLabel.put("description", "Human-friendly label if provided");
    ArrayNode colRequired = colItems.putArray("required");
    colRequired.add("name");
    colItems.put("additionalProperties", false);

    ObjectNode parameterMode = props.putObject("parameterMode");
    parameterMode.put("type", "string");
    parameterMode.put("description", "Parameter binding mode used during execution");

    ObjectNode parameterCount = props.putObject("parameterCount");
    parameterCount.put("type", "number");
    parameterCount.put("description", "Number of parameters bound to the query");

    ObjectNode processedParameters = props.putObject("processedParameters");
    processedParameters.put("type", "array");
    processedParameters.put("description", "Ordered list of parameter names processed during binding");
    ObjectNode parameterItems = processedParameters.putObject("items");
    parameterItems.put("type", "string");

    ObjectNode toolName = props.putObject("toolName");
    toolName.put("type", "string");
    toolName.put("description", "Name of the tool that executed this query");

    ObjectNode sqlStatement = props.putObject("sqlStatement");
    sqlStatement.put("type", "string");
    sqlStatement.put("description", "The SQL statement that was executed");

    ObjectNode parameters = props.putObject("parameters");
    parameters.put("type", "object");
    parameters.put("description", "Parameters passed to the query");
    parameters.set("additionalProperties", mapper.createObjectNode());

    metadata.put("additionalProperties", false);
    return metadata;
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
