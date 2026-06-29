package com.ibm.ibmi.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GracefulShutdownTest {

  private static final String MINIMAL_TOOLS_YAML = """
      sources:
        ibmi:
          host: localhost
          user: dummy
          password: dummy
      tools:
        ping:
          source: ibmi
          description: "noop"
          parameters: []
          statement: SELECT 1 FROM SYSIBM.SYSDUMMY1
      """;

  private static final String INITIALIZE = """
      {"jsonrpc":"2.0","id":1,"method":"initialize","params":{\
      "protocolVersion":"2024-11-05","capabilities":{},\
      "clientInfo":{"name":"shutdown-test","version":"0"}}}
      """;

  @Test
  void stdinEofExitsProcess(@TempDir Path tempDir) throws IOException, InterruptedException {
    Path yaml = tempDir.resolve("tools.yaml");
    Files.writeString(yaml, MINIMAL_TOOLS_YAML);

    String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
    ProcessBuilder pb = new ProcessBuilder(
        java, "-cp", System.getProperty("java.class.path"),
        Main.class.getName(),
        "--tools", yaml.toAbsolutePath().toString());
    pb.redirectErrorStream(true);
    Process proc = pb.start();

    proc.getOutputStream().write((INITIALIZE.strip() + "\n").getBytes(StandardCharsets.UTF_8));
    proc.getOutputStream().flush();
    proc.getOutputStream().close();

    assertTrue(proc.waitFor(15, TimeUnit.SECONDS), "server did not exit after stdin EOF");
    assertEquals(0, proc.exitValue());
  }
}
