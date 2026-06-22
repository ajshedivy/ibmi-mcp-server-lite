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

  private static ParameterConfig withEnum(
      String name, String type, String description, List<Object> enumValues) {
    return new ParameterConfig(
        name, type, description, null, null, null, null, null, null, null, enumValues, null);
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

    JsonNode deptNode = node.get("properties").get("dept");
    assertEquals("string", deptNode.get("type").asText());
    assertEquals("A00", deptNode.get("enum").get(0).asText());
    assertEquals("B01", deptNode.get("enum").get(1).asText());
    assertEquals("Must be one of: 'A00', 'B01'", deptNode.get("description").asText());

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

  @Test
  void singleValueEnumEmitsConst() throws Exception {
    JsonNode prop = schema(List.of(withEnum("env", "string", "Environment", List.of("PROD"))))
        .get("properties").get("env");

    assertEquals("PROD", prop.get("const").asText());
    assertFalse(prop.has("enum"));
    assertFalse(prop.has("anyOf"));
    assertFalse(prop.has("type"));
    assertEquals("Environment. Must be one of: 'PROD'", prop.get("description").asText());
  }

  @Test
  void allStringMultiValueEnumEmitsTypedEnumArray() throws Exception {
    JsonNode prop = schema(List.of(withEnum("dept", "string", "Department", List.of("A00", "B01"))))
        .get("properties").get("dept");

    assertEquals("string", prop.get("type").asText());
    assertEquals(List.of("A00", "B01"), enumValues(prop));
    assertFalse(prop.has("const"));
    assertFalse(prop.has("anyOf"));
    assertEquals("Department. Must be one of: 'A00', 'B01'", prop.get("description").asText());
  }

  @Test
  void mixedTypeEnumEmitsAnyOfConsts() throws Exception {
    JsonNode prop = schema(List.of(withEnum("mode", "string", null, List.of(1, "two"))))
        .get("properties").get("mode");

    assertFalse(prop.has("enum"));
    assertFalse(prop.has("type"));
    JsonNode anyOf = prop.get("anyOf");
    assertEquals(2, anyOf.size());
    assertEquals(1, anyOf.get(0).get("const").asInt());
    assertEquals("two", anyOf.get(1).get("const").asText());
    assertEquals("Must be one of: 1, 'two'", prop.get("description").asText());
  }

  @Test
  void numericOnlyEnumEmitsAnyOfConsts() throws Exception {
    JsonNode prop = schema(List.of(withEnum("pageSize", "integer", null, List.of(1, 2, 3))))
        .get("properties").get("pageSize");

    assertFalse(prop.has("enum"));
    assertFalse(prop.has("type"));
    JsonNode anyOf = prop.get("anyOf");
    assertEquals(3, anyOf.size());
    assertEquals(1, anyOf.get(0).get("const").asInt());
    assertEquals(2, anyOf.get(1).get("const").asInt());
    assertEquals(3, anyOf.get(2).get("const").asInt());
    assertEquals("Must be one of: 1, 2, 3", prop.get("description").asText());
  }

  @Test
  void booleanParameterIgnoresEnum() throws Exception {
    ParameterConfig flag = new ParameterConfig(
        "flag", "boolean", "Enable feature", null, null, null,
        null, null, null, null, List.of("yes", "no"), null);
    JsonNode prop = schema(List.of(flag)).get("properties").get("flag");

    assertEquals("boolean", prop.get("type").asText());
    assertFalse(prop.has("const"));
    assertFalse(prop.has("enum"));
    assertFalse(prop.has("anyOf"));
    assertEquals("Enable feature", prop.get("description").asText());
  }

  @Test
  void enumDescriptionSuffixAppendsToExistingDescription() throws Exception {
    JsonNode prop = schema(List.of(withEnum("dept", "string", "Department", List.of("A00", "B01"))))
        .get("properties").get("dept");
    assertEquals("Department. Must be one of: 'A00', 'B01'", prop.get("description").asText());
  }

  @Test
  void enumDescriptionSuffixPreservesQuestionMark() throws Exception {
    JsonNode prop = schema(List.of(withEnum("dept", "string", "Pick one?", List.of("A", "B"))))
        .get("properties").get("dept");
    assertEquals("Pick one? Must be one of: 'A', 'B'", prop.get("description").asText());
  }

  @Test
  void enumDescriptionSuffixPreservesExistingPeriod() throws Exception {
    JsonNode prop = schema(List.of(withEnum("limit", "integer", "Already done.", List.of(1, 2))))
        .get("properties").get("limit");
    assertEquals("Already done. Must be one of: 1, 2", prop.get("description").asText());
  }

  @Test
  void enumWithoutBaseDescriptionUsesClauseOnly() throws Exception {
    JsonNode prop = schema(List.of(withEnum("dept", "string", null, List.of("A00", "B01"))))
        .get("properties").get("dept");
    assertEquals("Must be one of: 'A00', 'B01'", prop.get("description").asText());
  }

  @Test
  void nonEnumParameterDescriptionIsUnchanged() throws Exception {
    JsonNode prop = schema(List.of(
        new ParameterConfig("name", "string", "Job name filter", null, null,
            null, null, null, null, null, null, null)))
        .get("properties").get("name");
    assertEquals("Job name filter", prop.get("description").asText());
  }

  @Test
  void enumReplacesBaseTypeConstraints() throws Exception {
    ParameterConfig dept = new ParameterConfig(
        "dept", "string", null, null, null, null, null, null, 1, 10,
        List.of("A00", "B01"), "^[A-Z0-9]+$");
    JsonNode prop = schema(List.of(dept)).get("properties").get("dept");

    assertEquals("string", prop.get("type").asText());
    assertFalse(prop.has("minLength"));
    assertFalse(prop.has("maxLength"));
    assertFalse(prop.has("pattern"));
  }

  private static List<String> enumValues(JsonNode prop) {
    List<String> values = new java.util.ArrayList<>();
    prop.get("enum").forEach(n -> values.add(n.asText()));
    return values;
  }
}
