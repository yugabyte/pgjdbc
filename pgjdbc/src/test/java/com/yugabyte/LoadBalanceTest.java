package com.yugabyte;

import static com.yugabyte.FallbackOptionsLBTest.*;
import static com.yugabyte.ysql.LoadBalanceProperties.CONNECTION_MANAGER_MAP;

import com.yugabyte.ysql.LoadBalanceProperties;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.Map;

public class LoadBalanceTest {
  private static int numConnectionsPerThread = 2;
  private static int numThreads = 24;
  private static boolean waitForSignal = true;
  private static String baseUrl = "jdbc:yugabytedb://localhost:5433/yugabyte?load-balance=true"
      + "&" + LoadBalanceProperties.REFRESH_INTERVAL_KEY + "=1"; // &loggerLevel=DEBUG";

  private static String baseTAUrl =
      baseUrl + "&" + LoadBalanceProperties.TOPOLOGY_AWARE_PROPERTY_KEY + "=";

  public static void main(String[] args) throws SQLException, ClassNotFoundException, InterruptedException {
    if (path == null || path.trim().isEmpty()) {
      throw new IllegalStateException("YBDB_PATH not defined.");
    }
    Class.forName("org.postgresql.Driver");

    Map<String, Integer> expected1 = new HashMap<>();
    expected1.put("127.0.0.1", numThreads/3 + 1); // +1 for control connection
    expected1.put("127.0.0.2", numThreads/3);
    expected1.put("127.0.0.3", numThreads/3);
    int total = numThreads * numConnectionsPerThread;
    Map<String, Integer> expected2 = new HashMap<>();
    expected2.put("127.0.0.1", total/4 + 1); // +1 for control connection
    expected2.put("127.0.0.2", total/4);
    expected2.put("127.0.0.3", total/4);
    expected2.put("127.0.0.4", total/4);
    testConcurrentConnectionCreations(baseUrl, expected1, expected2, "127.0.0.1");

    String tkValues = "aws.us-west.us-west-2z:1,aws.us-west.us-west-2b:2,aws.us-west.us-west-2c:2";
    expected1.clear();
    expected1.put("127.0.0.1", +1); // control connection
    expected1.put("127.0.0.2", numThreads/2);
    expected1.put("127.0.0.3", numThreads/2);
    expected2.clear();
    expected2.put("127.0.0.1", +1);
    expected2.put("127.0.0.2", numThreads/2);
    expected2.put("127.0.0.3", numThreads/2); // control connection
    expected2.put("127.0.0.4", numThreads);
    testConcurrentConnectionCreations(baseTAUrl + tkValues, expected1, expected2, "127.0.0.1");
  }

  private static void testConcurrentConnectionCreations(String url,
      Map<String, Integer> expected1, Map<String, Integer> expected2, String controlHost) throws SQLException,
      InterruptedException {
    System.out.println("Running testConcurrentConnectionCreations() with url " + url);
    startYBDBCluster();
    try {
      System.out.println("Cluster started!");
      Thread.sleep(5000);
      int total = numThreads * numConnectionsPerThread;
      Thread[] threads = new Thread[numThreads];
      Connection[] connections = new Connection[total];

      for (int i = 0 ; i < numThreads ; i++) {
        final int j = i;
        threads[i] = new Thread(() -> {
          try {
            connections[j] = DriverManager.getConnection(url, "yugabyte", "yugabyte");
            while (waitForSignal) {
              try {
                Thread.sleep(1000);
              } catch (InterruptedException e) {
                System.out.println("Interrupted while waiting for a go-ahead. " + e);
              }
            }
            connections[j + numThreads] = DriverManager.getConnection(url, "yugabyte", "yugabyte");
          } catch (SQLException e) {
            System.out.println("getConnection() failed: " + e);
          }
        });
      }

      for (int i = 0 ; i < numThreads; i++) {
        threads[i].setDaemon(true);
        threads[i].start();
      }
      System.out.println("Launched " + numThreads + " threads to create " + numConnectionsPerThread + " connections each");

      Thread.sleep(10000);
      for (Map.Entry<String, Integer> e : expected1.entrySet()) {
        verifyOn(e.getKey(), e.getValue(), controlHost);
        System.out.print(", ");
      }

      executeCmd(path + "/bin/yb-ctl add_node --placement_info \"aws.us-west.us-west-2z\"",
          "Add node", 15);
      // Sometimes, the cluster is not available for connections immediately. So wait a bit
      Thread.sleep(10000);
      waitForSignal = false;
      for (int i = 0; i < numThreads; i++) {
        try {
          threads[i].join();
        } catch (InterruptedException e) {
          System.out.println("Thread " + i + " interrupted: " + e);
        }
      }

      for (Map.Entry<String, Integer> e : expected2.entrySet()) {
        verifyOn(e.getKey(), e.getValue(), controlHost);
        System.out.print(", ");
      }

      System.out.println("Closing connections ...");
      for (int i = 0 ; i < total; i++) {
        connections[i].close();
      }

    } finally {
      waitForSignal = true;
      CONNECTION_MANAGER_MAP.clear();
      executeCmd(path + "/bin/yb-ctl destroy", "Stop YugabyteDB cluster", 15);
    }
  }
}
