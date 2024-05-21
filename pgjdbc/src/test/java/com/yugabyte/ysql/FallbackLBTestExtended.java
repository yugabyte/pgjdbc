package com.yugabyte.ysql;

import static com.yugabyte.ysql.FallbackOptionsLBTest.executeCmd;
import static com.yugabyte.ysql.FallbackOptionsLBTest.expectedInput;
import static com.yugabyte.ysql.FallbackOptionsLBTest.startYBDBClusterWithNineNodes;
import static com.yugabyte.ysql.FallbackOptionsLBTest.verifyOn;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.Map;
import java.util.Properties;

public class FallbackLBTestExtended {
  private static int numConnections = 18;
  private static final String path = System.getenv("YBDB_PATH");
  private static String baseUrl = "jdbc:yugabytedb://127.0.0.1:5433,127.0.0.4:5433,127.0.0.7:5433/yugabyte";
  private static Properties props = new Properties();
  private static boolean useProperties = false;


  public static void main(String[] args) throws SQLException, ClassNotFoundException {
    if (path == null || path.trim().isEmpty()) {
      throw new IllegalStateException("YBDB_PATH not defined.");
    }
    Class.forName("org.postgresql.Driver");

    System.out.println("Running CheckMultiNodeDown() ....");
    checkMultiNodeDown();
    System.out.println("Running checkMultiNodeDown(with properties) ...");
    useProperties = true;
    checkMultiNodeDown();
    System.out.println("Running checkNodeDownPrimary() ....");
    checkNodeDownPrimary();
    System.out.println("Running checkNodeDownPrimary(with url) ...");
    useProperties = false;
    checkNodeDownPrimary();
  }

  private static void checkMultiNodeDown() throws SQLException {
    // Start RF=3 cluster with 9 nodes and with placements
    // (127.0.0.1, 127.0.0.2, 127.0.0.3) -> us-west-1a,
    // (127.0.0.4, 127.0.0.5) -> us-east-2a
    // (127.0.0.6, 127.0.0.9) -> eu-north-2a,
    // (127.0.0.7, 127.0.0.8) -> eu-west-2a.
    startYBDBClusterWithNineNodes();
    String url = "jdbc:yugabytedb://127.0.0.1:5433,127.0.0.4:5433,127.0.0" +
        ".7:5433/yugabyte?load-balance=true&yb-servers-refresh-interval=0&topology-keys=";
    String tk = "aws.us-west.*:1,aws.us-east.*:2,aws.eu-west.*:3,aws.eu-north.*:4";
    String controlHost = "127.0.0.1";
    if (useProperties) {
      props.clear();
      props.setProperty("user", "yugabyte");
      props.setProperty("password", "yugabyte");
      props.setProperty("load-balance", "true");
      props.setProperty("yb-servers-refresh-interval", "0");
      props.setProperty("topology-keys", tk);
    }

    try {
      Connection[] connections1 = new Connection[numConnections];
      createConnectionsWithoutCloseAndVerify(url+tk, controlHost, connections1, expectedInput(6+1, 6, 6, 0, 0, 0, 0, 0, 0));

      executeCmd(path + "/bin/yb-ctl stop_node 1", "Stop node 1", 10);
      executeCmd(path + "/bin/yb-ctl stop_node 2", "Stop node 2", 10);
      executeCmd(path + "/bin/yb-ctl stop_node 3", "Stop node 3", 10);
      executeCmd(path + "/bin/yb-ctl stop_node 4", "Stop node 4", 10);
      executeCmd(path + "/bin/yb-ctl stop_node 5", "Stop node 5", 10);
      executeCmd(path + "/bin/yb-ctl stop_node 7", "Stop node 7", 10);
      executeCmd(path + "/bin/yb-ctl stop_node 8", "Stop node 8", 10);
      Connection[] connections2 = new Connection[numConnections];
      controlHost = "127.0.0.6";
      createConnectionsWithoutCloseAndVerify(url+tk, controlHost, connections2, expectedInput(-1, -1, -1, -1, -1, 9+1, -1, -1, 9));

      executeCmd(path + "/bin/yb-ctl stop_node 9", "Stop node 9", 10);
      Connection[] connections3 = new Connection[numConnections];
      createConnectionsWithoutCloseAndVerify(url+tk, controlHost, connections3, expectedInput(-1, -1, -1, -1, -1, 27+1, -1, -1, -1));

      executeCmd(path + "/bin/yb-ctl start_node 2 --placement_info \"aws.us-west.us-west-1a\"",
          "Start node 2", 10);
      try {
        Thread.sleep(15000);
      } catch (InterruptedException ie) {
      }
      Connection[] connections4 = new Connection[numConnections];
      createConnectionsWithoutCloseAndVerify(url+tk, controlHost, connections4, expectedInput(-1, 18, -1, -1, -1, 27+1, -1, -1, -1));

      executeCmd(path + "/bin/yb-ctl stop_node 2", "Stop node 2", 10);
      Connection[] connections5 = new Connection[numConnections];
      createConnectionsWithoutCloseAndVerify(url+tk, controlHost, connections5, expectedInput(-1, -1, -1, -1, -1, 45+1, -1, -1, -1));

      executeCmd(path + "/bin/yb-ctl start_node 5 --placement_info \"aws.us-east.us-east-2a\"",
          "Start node 5", 10);
      try {
        Thread.sleep(15000);
      } catch (InterruptedException ie) {
      }
      Connection[] connections6 = new Connection[numConnections];
      createConnectionsWithoutCloseAndVerify(url+tk, controlHost, connections6, expectedInput(-1, -1, -1, -1, 18, 45+1, -1, -1, -1));

      executeCmd(path + "/bin/yb-ctl stop_node 5", "Stop node 5", 10);
      Connection[] connections7 = new Connection[numConnections];
      createConnectionsWithoutCloseAndVerify(url+tk, controlHost, connections7, expectedInput(-1, -1, -1, -1, -1, 63+1, -1, -1, -1));

      close(connections1);
      close(connections2);
      close(connections3);
      close(connections4);
      close(connections5);
      close(connections6);
      close(connections7);
    } finally {
      LoadBalanceService.clear();
      executeCmd(path + "/bin/yb-ctl destroy", "Stop YugabyteDB cluster", 10);
    }
  }

  private static void close(Connection[] conns) {
    Arrays.stream(conns).forEach(c -> {
      try {
        c.close();
      } catch (SQLException e) {
        System.out.println("Error closing connection: " + e);
      }
    });
  }
  private static void checkNodeDownPrimary() throws SQLException {

    executeCmd(path + "/bin/yb-ctl destroy", "Stop YugabyteDB cluster", 10);
    executeCmd(path + "/bin/yb-ctl --rf 3 start --placement_info \"aws.us-west.us-west-1a\" ",
        "Start YugabyteDB rf=3 cluster", 15);

    String url = "jdbc:yugabytedb://127.0.0.1:5433/yugabyte?load-balance=true&yb-servers-refresh-interval=0&topology-keys=";
    String tk = "aws.us-west.*:1";
    if (useProperties) {
      props.clear();
      baseUrl = "jdbc:yugabytedb://127.0.0.1:5433/yugabyte";
      props.setProperty("user", "yugabyte");
      props.setProperty("password", "yugabyte");
      props.setProperty("load-balance", "true");
      props.setProperty("yb-servers-refresh-interval", "0");
      props.setProperty("topology-keys", tk);
    }

    try {
      Connection[] connections1 = new Connection[numConnections];
      createConnectionsWithoutCloseAndVerify(url+tk, "127.0.0.1", connections1, expectedInput(6+1, 6, 6));

      executeCmd(path + "/bin/yb-ctl stop_node 1", "Stop node 1", 10);
      Connection[] connections2 = new Connection[numConnections];
      // control connection goes to 3 because user connection to 1 fails since it's down, and it sets force refresh flag
      createConnectionsWithoutCloseAndVerify(url+tk, "127.0.0.3", connections2, expectedInput(-1, 15, 15+1));

      executeCmd(path + "/bin/yb-ctl start_node 1 --placement_info \"aws.us-west.us-west-1a\"",
          "Start node 1", 10);
      try {
        Thread.sleep(5000);
      } catch (InterruptedException ie) {
      }
      Connection[] connections3 = new Connection[numConnections];
      createConnectionsWithoutCloseAndVerify(url+tk, "127.0.0.3", connections3, expectedInput(16, 16, 16+1));

      close(connections1);
      close(connections2);
      close(connections3);
    } finally {
      LoadBalanceService.clear();
      executeCmd(path + "/bin/yb-ctl destroy", "Stop YugabyteDB cluster", 10);
    }
  }

  private static void createConnectionsWithoutCloseAndVerify(String url, String controlHost,
      Connection[] connections, Map<String, Integer> counts) throws SQLException {
    for (int i = 0; i < numConnections; i++) {
      if (useProperties) {
        connections[i] = DriverManager.getConnection(baseUrl, props);
      } else {
        connections[i] = DriverManager.getConnection(url, "yugabyte", "yugabyte");
      }
    }
    System.out.println("Created " + numConnections + " connections");

    System.out.print("Client backend processes on ");
    counts.forEach((k, v) -> {
      if (v != -1) {
        verifyOn(k, v, controlHost);
        System.out.print(", ");
      }
    });
    System.out.println("");
  }
}
