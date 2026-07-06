package com.ibm.ibmi.mcp.server;

import java.net.BindException;

import org.eclipse.jetty.ee10.servlet.ServletContextHandler;
import org.eclipse.jetty.ee10.servlet.ServletHolder;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;

import io.modelcontextprotocol.server.transport.HttpServletStreamableServerTransportProvider;

/**
 * Boots an embedded Jetty instance that hosts the MCP Streamable HTTP servlet transport.
 */
public final class HttpTransport {

  private HttpTransport() {}

  /**
   * Binds Jetty to {@code host}:{@code port}, mounts {@code servlet} at {@code endpointPath},
   * and starts the server. Use port {@code 0} for an ephemeral port (tests only); read the
   * bound port from {@link #localPort(Server)} after start.
   */
  public static Server start(
      HttpServletStreamableServerTransportProvider servlet,
      String host,
      int port,
      String endpointPath) throws Exception {
    Server server = new Server();
    ServerConnector connector = new ServerConnector(server);
    connector.setHost(host);
    connector.setPort(port);
    server.addConnector(connector);

    ServletContextHandler context = new ServletContextHandler(ServletContextHandler.NO_SESSIONS);
    context.setContextPath("/");

    ServletHolder holder = new ServletHolder(servlet);
    holder.setAsyncSupported(true);
    context.addServlet(holder, endpointPath);

    server.setHandler(context);
    try {
      server.start();
    } catch (Exception e) {
      if (port != 0 && isAddressInUse(e)) {
        throw new IllegalStateException("Port " + port + " already in use on " + host, e);
      }
      throw e;
    }
    return server;
  }

  /** Returns the local port Jetty bound to (useful when {@code port} was 0). */
  public static int localPort(Server server) {
    for (var connector : server.getConnectors()) {
      if (connector instanceof ServerConnector serverConnector) {
        return serverConnector.getLocalPort();
      }
    }
    throw new IllegalStateException("No ServerConnector on Jetty server");
  }

  private static boolean isAddressInUse(Throwable throwable) {
    for (Throwable current = throwable; current != null; current = current.getCause()) {
      if (current instanceof BindException) {
        return true;
      }
      String message = current.getMessage();
      if (message != null && message.contains("Address already in use")) {
        return true;
      }
    }
    return false;
  }
}
