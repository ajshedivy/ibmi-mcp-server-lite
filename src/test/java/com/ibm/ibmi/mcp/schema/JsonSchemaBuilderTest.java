package com.ibm.ibmi.mcp.schema;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ibm.ibmi.mcp.config.ParameterConfig;

class JsonSchemaBuilderTest {

  private final ObjectMapper mapper = new ObjectMapper();
  private final JsonSchemaBuilder builder = new JsonSchemaBuilder(mapper);

  private JsonNode schema(List<ParameterConfig> params) throws JsonProcessingException {
    return mapper.readTree(builder.buildInputSchema(params));
  }

  private static ParameterConfig param(String name, String type) {
    return new ParameterConfig(name, type, null, null, null, null, null, null, null, null, null, null);
  }

  @Test
  void emptyParametersGiveClosedObjectSchema() throws Exception {
    JsonNode node = schema(List.of());
    assertEquals("object", node.get("type").asText());
    assertFalse(node.get("additionalProperties").asBoolean());
    assertTrue(node.get("properties").isEmpty());
  }

  @Test
  void typesMapToJsonSchemaTypes() throws Exception {
    JsonNode node = schema(List.of(
        param("s", "string"), param("i", "integer"), param("f", "float"),
        param("b", "boolean"), param("a", "array")));
    JsonNode props = node.get("properties");
    assertEquals("string", props.get("s").get("type").asText());
    assertEquals("integer", props.get("i").get("type").asText());
    assertEquals("number", props.get("f").get("type").asText());
    assertEquals("boolean", props.get("b").get("type").asText());
    assertEquals("array", props.get("a").get("type").asText());
    assertEquals("string", props.get("a").get("items").get("type").asText()); // itemType default
  }

  @Test
  void constraintsAndDefaultsAreEmitted() throws Exception {
    ParameterConfig limit = new ParameterConfig(
        "limit", "integer", "Max rows", 10, null, null, 1, 100, null, null, null, null);
    ParameterConfig dept = new ParameterConfig(
        "dept", "string", null, null, true, null, null, null, null, null,
        List.of("A00", "B01"), null);
    JsonNode node = schema(List.of(limit, dept));

    JsonNode limitNode = node.get("properties").get("limit");
    assertEquals(1.0, limitNode.get("minimum").asDouble());
    assertEquals(100.0, limitNode.get("maximum").asDouble());
    assertEquals(10, limitNode.get("default").asInt());
    assertEquals("Max rows", limitNode.get("description").asText());

    assertEquals("A00", node.get("properties").get("dept").get("enum").get(0).asText());

    // limit has a default -> optional; dept is required
    List<String> required = new java.util.ArrayList<>();
    node.get("required").forEach(n -> required.add(n.asText()));
    assertEquals(List.of("dept"), required);
  }

  @Test
  void requiredFalseWithoutDefaultIsOptional() throws Exception {
    ParameterConfig optional = new ParameterConfig(
        "opt", "string", null, null, false, null, null, null, null, null, null, null);
    JsonNode node = schema(List.of(optional));
    assertFalse(node.has("required"));
  }
}
