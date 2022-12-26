package com.yugabyte;

import org.postgresql.util.PSQLException;

import com.yugabyte.ysql.LoadBalanceProperties;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.sql.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

public class FallbackOptionsLBTest {
  private static int numConnections = 12;
  private static String path;

  public static void main(String[] args) throws SQLException, ClassNotFoundException {
    // Start YugabyteDB cluster if env YBDB_PATH is defined. Else, assume that the cluster is up.
    // Start RF=3 cluster across 2a, 2b and 2c
    startYBDBCluster();
    try {
      Class.forName("org.postgresql.Driver");
      String url1 = "jdbc:yugabytedb://localhost:5433/yugabyte?load-balance=true&" + LoadBalanceProperties.TOPOLOGY_AWARE_PROPERTY_KEY + "=";
      String url2 = "aws.us-west.us-west-2a,aws.us-west.us-west-2b:1,aws.us-west.us-west-2c:2";
      Connection conn = DriverManager.getConnection(url1 + url2,
          "yugabyte", "yugabyte");
      Statement stmt = conn.createStatement();
      stmt.execute("CREATE TABLE IF NOT EXISTS employee" +
          "  (id int primary key, name varchar, age int, language text)");
      System.out.println("Created table");
      conn.close();

      // All valid/available placement zones
      createConnectionsAndVerify(url1, "aws.us-west.us-west-2a,aws.us-west.us-west-2c", 6, 0, 6);
      createConnectionsAndVerify(url1, "aws.us-west.us-west-2a,aws.us-west.us-west-2b:1,aws.us-west.us-west-2c:2", 6, 6, 0);
      createConnectionsAndVerify(url1, "aws.us-west.us-west-2a:1,aws.us-west.us-west-2b:2,aws.us-west.us-west-2c:3", 12, 0, 0);
      createConnectionsAndVerify(url1, "aws.us-west.*,aws.us-west.us-west-2b:1,aws.us-west.us-west-2c:2", 4, 4, 4);
      createConnectionsAndVerify(url1, "aws.us-west.*:1,aws.us-west.us-west-2b:2,aws.us-west.us-west-2c:3", 4, 4, 4);

      // Some invalid/unavailable placement zones
      createConnectionsAndVerify(url1, "BAD.BAD.BAD:1,aws.us-west.us-west-2b:2,aws.us-west.us-west-2c:3", 0, 12, 0);
      createConnectionsAndVerify(url1, "aws.us-west.us-west-2a:1,BAD.BAD.BAD:2,aws.us-west.us-west-2c:3", 12, 0, 0);
      createConnectionsAndVerify(url1, "aws.us-west.us-west-2a:1,aws.us-west.us-west-2b:2,BAD.BAD.BAD:3", 12, 0, 0);
      createConnectionsAndVerify(url1, "BAD.BAD.BAD:1,BAD.BAD.BAD:2,aws.us-west.us-west-2c:3", 0, 0, 12);
      createConnectionsAndVerify(url1, "BAD.BAD.BAD:1,BAD.BAD.BAD:2,aws.us-west.*:3", 4, 4, 4);

      // Invalid preference values, results in failure
      createConnectionsAndVerify(url1, "aws.us-west.us-west-2a:11,aws.us-west.us-west-2b:2,aws.us-west.us-west-2c:3", -1, 0, 0);
      createConnectionsAndVerify(url1, "aws.us-west.us-west-2a:1,aws.us-west.us-west-2b:-2,aws.us-west.us-west-2c:3", -1, 0, 0);
      createConnectionsAndVerify(url1, "aws.us-west.us-west-2a:1,aws.us-west.us-west-2b:2,aws.us-west.us-west-2c:", -1, 0, 0);
    } finally {
      if (path != null && !path.trim().isEmpty()) {
        stopYBDBCluster();
      }
    }
  }

  private static void startYBDBCluster() {
    try {
      path = System.getenv("YBDB_PATH");
      if (path == null || path.trim().isEmpty()) {
        System.out.println("YBDB_PATH not defined, assuming YugabyteDB cluster to be running already.");
        return;
      }
      stopYBDBCluster();

//       executeCmd(path + "/bin/yb-ctl --rf 3 start --placement_info \"aws.us-west.us-west-1a," +
//           "aws.us-east.us-east-2a,aws.us-east.us-east-1a\" --tserver_flags \"placement_uuid=live," +
//           "max_stale_read_bound_time_ms=60000000\"", "Started YugabyteDB cluster", 15);
//       executeCmd(path + "/bin/yb-ctl add_node", "Added a node", 10);
//       executeCmd(path + "/bin/yb-ctl add_node", "Added a node", 10);
//       executeCmd(path + "/bin/yb-ctl add_node", "Added a node", 10);
//       executeCmd(path + "/bin/yb-admin -master_addresses 127.0.0.1:7100,127.0.0.2:7100," +
//           "127.0.0.3:7100 modify_placement_info aws.us-west.us-west-1a,aws.us-east.us-east-2a," +
//           "aws.us-east.us-east-1a 3 live", "Modified placement info", 10);

      ProcessBuilder builder = new ProcessBuilder();
      builder.command("sh", "-c", path + "/bin/yb-ctl start --rf 3 --placement_info \"aws.us-west.us-west-2a,aws.us-west.us-west-2b,aws.us-west.us-west-2c\"");
      Process process = builder.start();
      String output = new BufferedReader(new InputStreamReader(process.getInputStream()))
          .lines().collect(Collectors.joining("\n"));
      process.waitFor(15, TimeUnit.SECONDS);
      int exitCode = process.exitValue();
      System.out.println(output);
      if (exitCode != 0) {
        throw new RuntimeException("Failed to start rf=3 cluster!");
      }
    } catch (Exception e) {
      System.out.println("Exception " + e);
    }
  }

  private static void executeCmd(String cmd, String msg, int timeout) {
    try {
      ProcessBuilder builder = new ProcessBuilder();
      builder.command("sh", "-c", cmd);
      Process process = builder.start();
      process.waitFor(timeout, TimeUnit.SECONDS);
      int exitCode = process.exitValue();
      if (exitCode != 0) {
        throw new RuntimeException("FAILED! " + msg);
      }
      System.out.println(msg);
    } catch (Exception e) {
      System.out.println("Exception " + e);
    }
  }

  private static void stopYBDBCluster() {
    try {
      ProcessBuilder builder = new ProcessBuilder();
      builder.command("sh", "-c", path + "/bin/yb-ctl destroy");
      Process process = builder.start();
      process.waitFor(10, TimeUnit.SECONDS);
      int exitCode = process.exitValue();
      if (exitCode != 0) {
        throw new RuntimeException("Failed to stop existing cluster!");
      }
      System.out.println("Stopped YugabyteDB cluster.");
    } catch (Exception e) {
      System.out.println("Exception " + e);
    }
  }

  private static void createConnectionsAndVerify(String url, String tkValue, int cnt1, int cnt2, int cnt3) throws SQLException {
    Connection[] connections = new Connection[numConnections];
    for (int i = 0; i < numConnections; i++) {
      try {
        connections[i] = DriverManager.getConnection(url + tkValue, "yugabyte", "yugabyte");
      } catch (PSQLException e) {
        if (cnt1 != -1) {
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

    System.out.print("Client backend processes on ");
    verifyOn("127.0.0.1", cnt1, tkValue);
    System.out.print(", ");
    verifyOn("127.0.0.2", cnt2, tkValue);
    System.out.print(", ");
    verifyOn("127.0.0.3", cnt3, tkValue);
    System.out.println("");
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
      int recorded = LoadBalanceProperties.CONNECTION_MANAGER_MAP.get(tkValue).getConnectionCountFor(server);
      if (recorded != expectedCount) {
        throw new RuntimeException("Client side connection count didn't match. (expected, actual): "
            + expectedCount + ", " + recorded);
      }
    } catch (Exception e) {
      System.out.println("Exception " + e);
    }
  }
}
