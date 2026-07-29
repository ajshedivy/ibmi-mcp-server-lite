package com.ibm.ibmi.mcp.server;

import java.io.IOException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ibm.ibmi.mcp.mapepire.SourceManager;

import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Unauthenticated liveness probe at {@code GET /healthz}.
 * Always returns HTTP 200; operators should inspect body {@code status}
 * ({@code ok} vs {@code degraded}).
 *
 * <p>Reflects cached pool lifecycle after connect attempts — does not probe
 * Mapepire. Empty {@code pools} means no source has been used yet (still
 * {@code ok} even if Mapepire is down).
 */
public final class HealthServlet extends HttpServlet {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private final SourceManager sources;

  public HealthServlet(SourceManager sources) {
    this.sources = sources;
  }

  @Override
  protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
    Map<String, Map<String, Object>> pools = sources.getHealthSummary();
    boolean hasUnhealthy = pools.values().stream()
        .anyMatch(p -> "unhealthy".equals(p.get("healthStatus")));

    Map<String, Object> body = new LinkedHashMap<>();
    body.put("status", hasUnhealthy ? "degraded" : "ok");
    body.put("timestamp", Instant.now().toString());
    body.put("pools", pools);

    resp.setStatus(HttpServletResponse.SC_OK);
    resp.setContentType("application/json");
    resp.setCharacterEncoding("UTF-8");
    MAPPER.writeValue(resp.getOutputStream(), body);
  }

  @Override
  protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException {
    resp.sendError(HttpServletResponse.SC_METHOD_NOT_ALLOWED);
  }
}
