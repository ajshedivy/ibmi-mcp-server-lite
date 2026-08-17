package com.ibm.ibmi.mcp.it;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.opentest4j.TestAbortedException;

class MapepireEnvTest {

  @Test
  void permissionFailureAbortsInsteadOfErrors() {
    TestAbortedException aborted = assertThrows(
        TestAbortedException.class,
        () -> MapepireEnv.assumeAvailable(() -> {
          throw new IllegalStateException(
              "Secret-bearing config file '.env' is group/world readable");
        }));

    assertTrue(aborted.getMessage().contains("Mapepire environment unavailable"));
    assertTrue(aborted.getMessage().contains("group/world readable"));
  }
}
