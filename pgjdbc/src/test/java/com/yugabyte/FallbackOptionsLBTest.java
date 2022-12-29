package com.yugabyte;

import org.postgresql.util.PSQLException;

import com.yugabyte.ysql.LoadBalanceProperties;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.sql.*;
import java.util.ArrayList;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

public class FallbackOptionsLBTest {
  private static int numConnections = 12;
  private static final String path = System.getenv("YBDB_PATH");
  private static String url1 = "jdbc:yugabytedb://localhost:5433/yugabyte?load-balance=true&"
      + LoadBalanceProperties.TOPOLOGY_AWARE_PROPERTY_KEY + "=";

  public static void main(String[] args) throws SQLException, ClassNotFoundException {
    if (path == null || path.trim().isEmpty()) {
      throw new IllegalStateException("YBDB_PATH not defined.");
    }
    Class.forName("org.postgresql.Driver");

    checkBasicBehavior();
    checkNodeDownBehavior();
  }

  private static void checkBasicBehavior() throws SQLException {
    // Start RF=3 cluster across 2a, 2b and 2c
    startYBDBCluster();
    try {
      String url2 = "aws.us-west.us-west-2a,aws.us-west.us-west-2b:1,aws.us-west.us-west-2c:2";
      Connection conn = DriverManager.getConnection(url1 + url2, "yugabyte", "yugabyte");
      Statement stmt = conn.createStatement();
      stmt.execute("CREATE TABLE IF NOT EXISTS employee" +
          "  (id int primary key, name varchar, age int, language text)");
      System.out.println("Created table");
      conn.close();

      // All valid/available placement zones
      createConnectionsAndVerify(url1, "aws.us-west.us-west-2a,aws.us-west.us-west-2c", format(6, 0, 6));
      createConnectionsAndVerify(url1, "aws.us-west.us-west-2a,aws.us-west.us-west-2b:1,aws.us-west.us-west-2c:2", format(6, 6, 0));
      createConnectionsAndVerify(url1, "aws.us-west.us-west-2a:1,aws.us-west.us-west-2b:2,aws.us-west.us-west-2c:3", format(12, 0, 0));
      createConnectionsAndVerify(url1, "aws.us-west.*,aws.us-west.us-west-2b:1,aws.us-west.us-west-2c:2", format(4, 4, 4));
      createConnectionsAndVerify(url1, "aws.us-west.*:1,aws.us-west.us-west-2b:2,aws.us-west.us-west-2c:3", format(4, 4, 4));

      // Some invalid/unavailable placement zones
      createConnectionsAndVerify(url1, "BAD.BAD.BAD:1,aws.us-west.us-west-2b:2,aws.us-west.us-west-2c:3", format(0, 12, 0));
      createConnectionsAndVerify(url1, "BAD.BAD.BAD:1,aws.us-west.us-west-2b:2,aws.us-west.us-west-2c:2", format(0, 6, 6));
      createConnectionsAndVerify(url1, "aws.us-west.us-west-2a:1,BAD.BAD.BAD:2,aws.us-west.us-west-2c:3", format(12, 0, 0));
      createConnectionsAndVerify(url1, "BAD.BAD.BAD:1,BAD.BAD.BAD:2,aws.us-west.us-west-2c:3", format(0, 0, 12));
      createConnectionsAndVerify(url1, "BAD.BAD.BAD:1,BAD.BAD.BAD:2,aws.us-west.*:3", format(4, 4, 4));

      // Invalid preference values, results in failure
      createConnectionsAndVerify(url1, "aws.us-west.us-west-2a:11,aws.us-west.us-west-2b:2,aws.us-west.us-west-2c:3", format(-1, 0, 0));
      createConnectionsAndVerify(url1, "aws.us-west.us-west-2a:1,aws.us-west.us-west-2b:-2,aws.us-west.us-west-2c:3", format(-1, 0, 0));
      createConnectionsAndVerify(url1, "aws.us-west.us-west-2a:1,aws.us-west.us-west-2b:2,aws.us-west.us-west-2c:", format(-1, 0, 0));
    } finally {
      executeCmd(path + "/bin/yb-ctl destroy", "Stop YugabyteDB cluster.", 10);
    }
  }

  private static ArrayList<Integer> format(int... counts) {
    ArrayList input = new ArrayList<>();
    for (int i : counts) {
      input.add(i);
    }
    return input;
  }

  private static void checkNodeDownBehavior() throws SQLException {
    startYBDBClusterWithReadReplica();
    String url = "jdbc:yugabytedb://127.0.0.4:5433/yugabyte?load-balance=true&topology-keys=";

    try {
      createConnectionsAndVerify(url, "aws.us-west.us-west-1a", format(4, 4, 4));
      // stop 1,2,3 before moving ahead
      executeCmd(path + "/bin/yb-ctl stop_node 1", "Stop node 1", 10);
      executeCmd(path + "/bin/yb-ctl stop_node 2", "Stop node 2", 10);
      executeCmd(path + "/bin/yb-ctl stop_node 3", "Stop node 3", 10);
      createConnectionsAndVerify(url, "aws.us-west.us-west-1a", format(-1, -1, -1, 4, 4, 4));
    } finally {
      executeCmd(path + "/bin/yb-ctl destroy", "Stop YugabyteDB cluster", 10);
    }
  }

  private static void startYBDBClusterWithReadReplica() {
    executeCmd(path + "/bin/yb-ctl destroy", "Stop YugabyteDB cluster", 10);

    executeCmd(path + "/bin/yb-ctl --rf 3 start --placement_info \"aws.us-west.us-west-1a\" " +
            "--tserver_flags \"placement_uuid=live,max_stale_read_bound_time_ms=60000000\"",
        "Start YugabyteDB rf=3 cluster", 15);
    executeCmd(path + "/bin/yb-ctl add_node", "Add a node", 10);
    executeCmd(path + "/bin/yb-ctl add_node", "Add a node", 10);
    executeCmd(path + "/bin/yb-ctl add_node", "Add a node", 10);
    executeCmd(path + "/bin/yb-admin -master_addresses 127.0.0.1:7100,127.0.0.2:7100," +
        "127.0.0.3:7100 modify_placement_info aws.us-west.us-west-1a,aws.us-east.us-east-2a," +
        "aws.us-east.us-east-1a 3 live", "Modify placement info", 10);
    try {
      Thread.sleep(5000);
    } catch (InterruptedException ie) {}
  }

  private static void startYBDBCluster() {
    executeCmd(path + "/bin/yb-ctl destroy", "Stop YugabyteDB cluster", 10);
    executeCmd(path + "/bin/yb-ctl start --rf 3 --placement_info \"aws.us-west.us-west-2a,aws" +
        ".us-west.us-west-2b,aws.us-west.us-west-2c\"", "Start YugabyteDB rf=3 cluster", 15);
  }

  private static void executeCmd(String cmd, String msg, int timeout) {
    try {
      ProcessBuilder builder = new ProcessBuilder();
      builder.command("sh", "-c", cmd);
      Process process = builder.start();
      process.waitFor(timeout, TimeUnit.SECONDS);
      int exitCode = process.exitValue();
      if (exitCode != 0) {
        throw new RuntimeException(msg + ": FAILED");
      }
      System.out.println(msg + ": SUCCEEDED!");
    } catch (Exception e) {
      System.out.println("Exception " + e);
    }
  }

  private static void createConnectionsAndVerify(String url, String tkValue, ArrayList<Integer> counts) throws SQLException {
    Connection[] connections = new Connection[numConnections];
    for (int i = 0; i < numConnections; i++) {
      try {
        connections[i] = DriverManager.getConnection(url + tkValue, "yugabyte", "yugabyte");
      } catch (PSQLException e) {
        if (counts.get(0) != -1) {
          throw new RuntimeException("Did not expect an exception! ", e);
        }
        System.out.println(e.getCause());
        if (!(e.getCause() instanceof IllegalArgumentException)) {
          throw new RuntimeException("Did not expect this exception! ", e);
        }
        return;
      }
    }
    System.out.println("Created "+ numConnections +" connections");

    int j = 1;
    System.out.print("Client backend processes on ");
    for (int expectedCount : counts) {
      if (expectedCount != -1) {
        verifyOn("127.0.0." + j, expectedCount, (j > 3 && j < 7) ? "skip" : tkValue);
        System.out.print(", ");
      }
      j++;
    }
    System.out.println("");
//     verifyOn("127.0.0.1", cnt1, tkValue);
//     System.out.print(", ");
//     verifyOn("127.0.0.2", cnt2, tkValue);
//     System.out.print(", ");
//     verifyOn("127.0.0.3", cnt3, tkValue);
//     System.out.println("");
    for (Connection con : connections) {
      if (con != null) {
        con.close();
      }
    }
  }

  private static void verifyOn(String server, int expectedCount, String tkValue) {
    try {
      ProcessBuilder builder = new ProcessBuilder();
      builder.command("sh", "-c", "curl http://" + server + ":13000/rpcz");
      Process process = builder.start();
      String result = new BufferedReader(new InputStreamReader(process.getInputStream()))
          .lines().collect(Collectors.joining("\n"));
      process.waitFor(10, TimeUnit.SECONDS);
      int exitCode = process.exitValue();
      if (exitCode != 0) {
        throw new RuntimeException("Could not access /rpcz on " + server);
      }
      String[] count = result.split("client backend");
      System.out.print(server + " = " + (count.length - 1));
      // Server side validation
      if (expectedCount != (count.length - 1)) {
        throw new RuntimeException("Client backend processes did not match. (expected, actual): "
            + expectedCount + ", " + (count.length-1));
      }
      // Client side validation
      if ("skip".equals(tkValue)) return;
      int recorded = LoadBalanceProperties.CONNECTION_MANAGER_MAP.get(tkValue).getConnectionCountFor(server);
      if (recorded != expectedCount) {
        throw new RuntimeException("Client side connection count didn't match. (expected, actual): "
            + expectedCount + ", " + recorded);
      }
    } catch (IOException | InterruptedException e) {
      System.out.println(e);
    }
  }
}
