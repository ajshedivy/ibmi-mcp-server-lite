package com.ibm.ibmi.mcp.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class RequestContextTest {

  @Test
  void create_generatesEightCharAlphanumericId() {
    RequestContext context = RequestContext.create("my_tool");

    assertEquals(8, context.requestId().length());
    assertTrue(context.requestId().matches("[A-Z0-9]{8}"));
    assertEquals("my_tool", context.toolName());
    assertTrue(context.startMillis() > 0);
  }

  @Test
  void create_generatesUniqueIds() {
    RequestContext first = RequestContext.create("tool_a");
    RequestContext second = RequestContext.create("tool_b");

    assertNotEquals(first.requestId(), second.requestId());
  }
}
