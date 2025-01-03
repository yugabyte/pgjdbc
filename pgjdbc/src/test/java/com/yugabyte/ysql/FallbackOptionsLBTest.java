package com.yugabyte.ysql;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

public class FallbackOptionsLBTest {
  private static int numConnections = 12;
  private static String controlHost = "";
  public static final String path = System.getenv("YBDB_PATH");
  private static String baseUrl = "jdbc:yugabytedb://localhost:5433/yugabyte?load-balance=true&"
      + LoadBalanceProperties.TOPOLOGY_AWARE_PROPERTY_KEY + "=";

  public static void main(String[] args) throws SQLException, ClassNotFoundException {
    if (path == null || path.trim().isEmpty()) {
      throw new IllegalStateException("YBDB_PATH not defined.");
    }
    Class.forName("org.postgresql.Driver");

    System.out.println("Running checkBasicBehavior() ....");
    checkBasicBehavior();
    System.out.println("Running checkNodeDownBehavior() ....");
    checkNodeDownBehavior();
    System.out.println("Running checkNodeDownBehaviorMultiFallback() ....");
    checkNodeDownBehaviorMultiFallback();
  }

  private static void checkBasicBehavior() throws SQLException {
    // Start RF=3 cluster with placements 127.0.0.1 -> 2a, 127.0.0.2 -> 2b and 127.0.0.3 -> 2c
    startYBDBCluster();
    try {
      String tkValues = "aws.us-west.us-west-2a,aws.us-west.us-west-2b:1,aws.us-west.us-west-2c:2";
      Connection conn = DriverManager.getConnection(baseUrl + tkValues, "yugabyte", "yugabyte");
      Statement stmt = conn.createStatement();
      stmt.execute("CREATE TABLE IF NOT EXISTS employee" +
          "  (id int primary key, name varchar, age int, language text)");
      System.out.println("Created table");
      conn.close();

      controlHost = "127.0.0.1";
      // All valid/available placement zones. +1 is for control connection
      createConnectionsAndVerify(baseUrl, "aws.us-west.us-west-2a,aws.us-west.us-west-2c", expectedInput(6+1, 0, 6));
      createConnectionsAndVerify(baseUrl, "aws.us-west.us-west-2a,aws.us-west.us-west-2b:1,aws" + ".us-west.us-west-2c:2", expectedInput(6+1, 6, 0));
      createConnectionsAndVerify(baseUrl, "aws.us-west.us-west-2a:1,aws.us-west.us-west-2b:2,aws" + ".us-west.us-west-2c:3", expectedInput(12+1, 0, 0));
      createConnectionsAndVerify(baseUrl, "aws.us-west.*,aws.us-west.us-west-2b:1,aws.us-west" + ".us-west-2c:2", expectedInput(4+1, 4, 4));
      createConnectionsAndVerify(baseUrl, "aws.us-west.*:1,aws.us-west.us-west-2b:2,aws.us-west" + ".us-west-2c:3", expectedInput(4+1, 4, 4));

      // Some invalid/unavailable placement zones
      createConnectionsAndVerify(baseUrl, "BAD.BAD.BAD:1,aws.us-west.us-west-2b:2,aws.us-west" + ".us-west-2c:3", expectedInput(+1, 12, 0));
      createConnectionsAndVerify(baseUrl, "BAD.BAD.BAD:1,aws.us-west.us-west-2b:2,aws.us-west" + ".us-west-2c:2", expectedInput(+1, 6, 6));
      createConnectionsAndVerify(baseUrl, "aws.us-west.us-west-2a:1,BAD.BAD.BAD:2,aws.us-west" + ".us-west-2c:3", expectedInput(12+1, 0, 0));
      createConnectionsAndVerify(baseUrl, "BAD.BAD.BAD:1,BAD.BAD.BAD:2,aws.us-west.us-west-2c:3", expectedInput(+1, 0, 12));
      createConnectionsAndVerify(baseUrl, "BAD.BAD.BAD:1,BAD.BAD.BAD:2,aws.us-west.*:3", expectedInput(4+1, 4, 4));

      // Invalid preference value results in failure, value -1 indicates an error is expected.
      createConnectionsAndVerify(baseUrl, "aws.us-west.us-west-2a:11,aws.us-west.us-west-2b:2,aws" + ".us-west.us-west-2c:3", null);
      createConnectionsAndVerify(baseUrl, "aws.us-west.us-west-2a:1,aws.us-west.us-west-2b:-2,aws" + ".us-west.us-west-2c:3", null);
      createConnectionsAndVerify(baseUrl, "aws.us-west.us-west-2a:1,aws.us-west.us-west-2b:2,aws" + ".us-west.us-west-2c:", null);
    } finally {
      LoadBalanceProperties.clearConnectionManagerMap();
      LoadBalanceService.clear();
      executeCmd(path + "/bin/yb-ctl destroy", "Stop YugabyteDB cluster", 10);
    }
  }

  static Map<String, Integer> expectedInput(int... counts) {
    Map<String, Integer> input = new HashMap();
    int s = 1;
    for (int i : counts) {
      input.put("127.0.0." + s, i);
      s++;
    }
    return input;
  }

  private static void checkNodeDownBehavior() throws SQLException {
    // Start RF=3 cluster with 6 nodes and with placements
    // (127.0.0.1, 127.0.0.2, 127.0.0.3) -> us-west-1a,
    // 127.0.0.4 -> us-east-2a,
    // 127.0.0.5 -> us-east-2b
    // 127.0.0.6 -> us-east-2c
    startYBDBClusterWithSixNodes();
    String url = "jdbc:yugabytedb://127.0.0.4:5433/yugabyte?load-balance=true&yb-servers-refresh-interval=0&topology-keys=";

    try {
      controlHost = "127.0.0.4";
      createConnectionsAndVerify(url, "aws.us-west.us-west-1a", expectedInput(4, 4, 4, +1));
      // stop 1,2,3 before moving ahead
      executeCmd(path + "/bin/yb-ctl stop_node 1", "Stop node 1", 10);
      executeCmd(path + "/bin/yb-ctl stop_node 2", "Stop node 2", 10);
      executeCmd(path + "/bin/yb-ctl stop_node 3", "Stop node 3", 10);
      createConnectionsAndVerify(url, "aws.us-west.us-west-1a", expectedInput(-1, -1, -1, 4+1, 4, 4));
      controlHost = "skip"; // skips client side validation because these connections are not tracked since they are not load balanced
      createConnectionsAndVerify("jdbc:yugabytedb://127.0.0.4:5433/yugabyte?load-balance=true&fallback-to-topology-keys-only=true&topology-keys=",
          "aws.us-west.us-west-1a", expectedInput(-1, -1, -1, 12+1, 0, 0));
    } finally {
      LoadBalanceProperties.clearConnectionManagerMap();
      LoadBalanceService.clear();
      executeCmd(path + "/bin/yb-ctl destroy", "Stop YugabyteDB cluster", 10);
    }
  }

  private static void checkNodeDownBehaviorMultiFallback() throws SQLException {
    // Start RF=3 cluster with 9 nodes and with placements
    // (127.0.0.1, 127.0.0.2, 127.0.0.3) -> us-west-1a,
    // (127.0.0.4, 127.0.0.5) -> us-east-2a
    // (127.0.0.6, 127.0.0.9) -> eu-north-2a,
    // (127.0.0.7, 127.0.0.8) -> eu-west-2a.
    startYBDBClusterWithNineNodes();
    String url = "jdbc:yugabytedb://127.0.0.1:5433,127.0.0.4:5433,127.0.0" +
        ".7:5433/yugabyte?load-balance=true&yb-servers-refresh-interval=0&topology-keys=";

    try {
      // +1 is for control connection
      controlHost = "127.0.0.1";
      String tk = "aws.us-west.*:1,aws.us-east.*:2,aws.eu-west.*:3,aws.eu-north.*:4";
      createConnectionsAndVerify(url, tk, expectedInput(4+1, 4, 4, 0, 0, 0, 0, 0, 0));

      executeCmd(path + "/bin/yb-ctl stop_node 1", "Stop node 1", 10);
      executeCmd(path + "/bin/yb-ctl stop_node 3", "Stop node 3", 10);
      controlHost = "127.0.0.4";
      createConnectionsAndVerify(url, tk, expectedInput(-1, 12, -1, +1, 0, 0, 0, 0, 0));

      executeCmd(path + "/bin/yb-ctl stop_node 2", "Stop node 2", 10);
      createConnectionsAndVerify(url, tk, expectedInput(-1, -1, -1, 6+1, 6, 0, 0, 0, 0));


      executeCmd(path + "/bin/yb-ctl stop_node 4", "Stop node 4", 10);
      controlHost = "127.0.0.7";
      createConnectionsAndVerify(url, tk, expectedInput(-1, -1, -1, -1, 12, 0, +1, 0, 0));

      executeCmd(path + "/bin/yb-ctl stop_node 5", "Stop node 5", 10);
      createConnectionsAndVerify(url, tk, expectedInput(-1, -1, -1, -1, -1, 0, 6+1, 6, 0));

      executeCmd(path + "/bin/yb-ctl stop_node 7", "Stop node 7", 10);
      executeCmd(path + "/bin/yb-ctl stop_node 8", "Stop node 8", 10);
      controlHost = "127.0.0.6";
      createConnectionsAndVerify(url, tk, expectedInput(-1, -1, -1, -1, -1, 6+1, -1, -1, 6));

      executeCmd(path + "/bin/yb-ctl stop_node 9", "Stop node 9", 10);
      createConnectionsAndVerify(url, tk, expectedInput(-1, -1, -1, -1, -1, 12+1, -1, -1, -1));

      executeCmd(path + "/bin/yb-ctl start_node 2 --placement_info \"aws.us-west.us-west-1a\"",
          "Start node 2", 10);
      try {
        Thread.sleep(15000);
      } catch (InterruptedException ie) {
      }
      createConnectionsAndVerify(url, tk, expectedInput(-1, 12, -1, -1, -1, +1, -1, -1, -1));

    } finally {
      LoadBalanceProperties.clearConnectionManagerMap();
      LoadBalanceService.clear();
      executeCmd(path + "/bin/yb-ctl destroy", "Stop YugabyteDB cluster", 10);
    }
  }

  static void startYBDBClusterWithNineNodes() {
    executeCmd(path + "/bin/yb-ctl destroy", "Stop YugabyteDB cluster", 10);

    executeCmd(path + "/bin/yb-ctl --rf 3 start --placement_info \"aws.us-west.us-west-1a\" ",
        "Start YugabyteDB rf=3 cluster", 15);
    try {
      Thread.sleep(5000);
    } catch (InterruptedException ie) {
    }
    executeCmd(path + "/bin/yb-ctl add_node --placement_info \"aws.us-east.us-east-2a\"",
        "Add a node", 10);
    executeCmd(path + "/bin/yb-ctl add_node --placement_info \"aws.us-east.us-east-2a\"",
        "Add a node", 10);
    executeCmd(path + "/bin/yb-ctl add_node --placement_info \"aws.eu-north.eu-north-2a\"",
        "Add a node", 10);

    executeCmd(path + "/bin/yb-ctl add_node --placement_info \"aws.eu-west.eu-west-2a\"",
        "Add a node", 10);
    executeCmd(path + "/bin/yb-ctl add_node --placement_info \"aws.eu-west.eu-west-2a\"",
        "Add a node", 10);
    executeCmd(path + "/bin/yb-ctl add_node --placement_info \"aws.eu-north.eu-north-2a\"",
        "Add a node", 10);

    try {
      Thread.sleep(10000);
    } catch (InterruptedException ie) {
    }
  }

  /**
   * Start RF=3 cluster with 6 nodes and with placements (127.0.0.1, 127.0.0.2, 127.0.0.3) -> us-west-1a,
   * and 127.0.0.4 -> us-east-2a, 127.0.0.5 -> us-east-2b and 127.0.0.6 -> us-east-2c
   */
  private static void startYBDBClusterWithSixNodes() {
    executeCmd(path + "/bin/yb-ctl destroy", "Stop YugabyteDB cluster", 10);

    executeCmd(path + "/bin/yb-ctl --rf 3 start --placement_info \"aws.us-west.us-west-1a\" " +
            "--tserver_flags \"placement_uuid=live,max_stale_read_bound_time_ms=60000000\"",
        "Start YugabyteDB rf=3 cluster", 15);
    executeCmd(path + "/bin/yb-ctl add_node --placement_info \"aws.us-west.us-east-2a\"",
        "Add a node", 10);
    executeCmd(path + "/bin/yb-ctl add_node --placement_info \"aws.us-west.us-east-2b\"",
        "Add a node", 10);
    executeCmd(path + "/bin/yb-ctl add_node --placement_info \"aws.us-west.us-east-2c\"",
        "Add a node", 10);
    try {
      System.out.println("Waiting 10 seconds for the cluster to be up...");
      Thread.sleep(10000);
      System.out.println("Done waiting 10 seconds for the cluster to be up");
    } catch (InterruptedException ie) {}
  }

  /**
   * Start RF=3 cluster with placements 127.0.0.1 -> 2a, 127.0.0.2 -> 2b and 127.0.0.3 -> 2c
   */
  public static void startYBDBCluster() {
    executeCmd(path + "/bin/yb-ctl destroy", "Stop YugabyteDB cluster", 15);
    executeCmd(path + "/bin/yb-ctl start --rf 3 --placement_info \"aws.us-west.us-west-2a,aws" +
        ".us-west.us-west-2b,aws.us-west.us-west-2c\"", "Start YugabyteDB rf=3 cluster", 60);
    try {
      Thread.sleep(10000);
    } catch (InterruptedException e) {
    }
  }

  public static void executeCmd(String cmd, String msg, int timeout) {
    try {
      ProcessBuilder builder = new ProcessBuilder();
      builder.command("sh", "-c", cmd);
      Process process = builder.start();
      process.waitFor(timeout, TimeUnit.SECONDS);
      int exitCode = process.exitValue();
      if (exitCode != 0) {
        String result = new BufferedReader(new InputStreamReader(process.getInputStream()))
            .lines().collect(Collectors.joining("\n"));
        throw new RuntimeException(msg + ": FAILED" + "\n" + result);
      }
      System.out.println(msg + ": SUCCEEDED!");
    } catch (Exception e) {
      System.out.println("Exception " + e);
    }
  }

  private static void createConnectionsAndVerify(String url, String tkValue, Map<String, Integer> counts) throws SQLException {
    Connection[] connections = new Connection[numConnections];
    for (int i = 0; i < numConnections; i++) {
      try {
        connections[i] = DriverManager.getConnection(url + tkValue, "yugabyte", "yugabyte");
      } catch (SQLException e) {
        if (counts != null) {
          throw new RuntimeException("Did not expect an exception! ", e);
        }
        System.out.println(e.getCause());
        if (!(e.getCause() instanceof IllegalArgumentException)) {
          throw new RuntimeException("Did not expect this exception! ", e);
        }
        return;
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
    for (Connection con : connections) {
      if (con != null) {
        con.close();
      }
    }
  }

  /**
   *
   * @param server
   * @param expectedCount
   * @param multipurposeParam Used to indicate the control connection host or to "skip" client side validation
   */
  public static void verifyOn(String server, int expectedCount, String multipurposeParam) {
    try {
      ProcessBuilder builder = new ProcessBuilder();
      builder.command("sh", "-c", "curl http://" + server + ":13000/rpcz");
      Process process = builder.start();
      String result = new BufferedReader(new InputStreamReader(process.getInputStream()))
          .lines().collect(Collectors.joining("\n"));
      process.waitFor(10, TimeUnit.SECONDS);
      int exitCode = process.exitValue();
      if (exitCode != 0) {
        String out = new BufferedReader(new InputStreamReader(process.getInputStream()))
            .lines().collect(Collectors.joining("\n"));
        throw new RuntimeException("Could not access /rpcz on " + server + "\n" + out);
      }
      String[] count = result.split("client backend");
      System.out.print(server + " = " + (count.length - 1));
      // Server side validation
      if (expectedCount != (count.length - 1)) {
        throw new RuntimeException("Client backend processes did not match for " + server + ", " +
            "(expected, actual): " + expectedCount + ", " + (count.length - 1));
      }
      // Client side validation
      if ("skip".equals(multipurposeParam)) {
        return;
      }
      int recorded = LoadBalanceService.getLoad("", server); // todo we do not maintain these tests. See refer to those in the driver-examples repo instead
      if (server.equalsIgnoreCase(multipurposeParam)) {
        // Account for control connection
        expectedCount -= 1;
      }
      if (recorded != expectedCount) {
        throw new RuntimeException("Client side connection count did not match for " + server +
            "," + " (expected, actual): " + expectedCount + ", " + recorded);
      }
    } catch (IOException | InterruptedException e) {
      System.out.println("Verification failed: " + e);
    }
  }
}
