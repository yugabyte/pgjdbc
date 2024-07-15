package com.yugabyte.examples;

import com.yugabyte.jdbc.PgConnection;
import com.yugabyte.ysql.ClusterAwareLoadBalancer;
import com.yugabyte.ysql.LoadBalanceProperties;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.sql.*;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.io.*;

public class FallbackLoadBalanceExample {

  private static String zone2a = "aws.us-west.us-west-2a", zone2b = "aws.us-west.us-west-2b",
      zone2c = "aws.us-west.us-west-2c";
  private static String s1 = "127.0.0.1", s2 = "127.0.0.2", s3 = "127.0.0.3";
  private static int numConnections = 12;
  private static String path = System.getenv("YBDB_PATH");
  private static boolean verbose , interactive , debug ;


  public static void main(String[] args) throws ClassNotFoundException, SQLException {

    verbose = Integer.parseInt(args[0]) == 1;
    interactive = Integer.parseInt(args[1]) == 1;
    debug = Integer.parseInt(args[2]) == 1;

    if (verbose) {
      System.out.println("Running checkMultiLevelFallback() ...");
      System.out.println("You can verify the connections getting repaired on the server side using " +
          "your browser, you can visit \"hostIP:13000/rpcz\" ... ");
    }
    checkMultiLevelFallback();
  }

  public static void pause() {
    if (interactive) {
      long start = 0, finish = 0, timeElapsed = 0, remainingTime = 0;
      BufferedReader br = new BufferedReader(new InputStreamReader(System.in));
      try {
        System.out.print("Press ENTER to continue : ");
        start = System.currentTimeMillis();
        br.readLine();
        finish = System.currentTimeMillis();
        timeElapsed = finish - start;
      } catch (IOException ioe) {
        System.out.println(ioe);
      }
      if (timeElapsed < 30000) {
        remainingTime = 30000 - timeElapsed;
        System.out.println("Wait of " + remainingTime + " milliseconds for the Hikari connection pool " +
            "to be created/repaired ...");
        try {
          Thread.sleep(remainingTime);
        } catch (InterruptedException io) {
        }
      }
    } else {
      System.out.println("Wait of 30 seconds for the Hikari connection pool to be " +
          "created/repaired ...");
      try {
        Thread.sleep(30000);
      } catch (InterruptedException io) {
      }
    }
  }

  // Using HikariDataSource
  private static HikariDataSource ds = null;

  private static void checkMultiLevelFallback() throws SQLException {
    startYBDBClusterWithNineNodes();
    String url;
    if (debug) {
      url = "jdbc:yugabytedb://127.0.0.1:5433,127.0.0.4:5433,127.0.0" +
          ".7:5433/yugabyte?&load-balance=true&loggerLevel=DEBUG";
    } else {
      url = "jdbc:yugabytedb://127.0.0.1:5433,127.0.0.4:5433,127.0.0" +
          ".7:5433/yugabyte?&load-balance=true";
    }

    try {
      ds = configureHikari(url);
      Map<String, Integer> input = new HashMap();
      pause();
      input.put("127.0.0.1", 4);
      input.put("127.0.0.2", 4);
      input.put("127.0.0.3", 4);
      verifyCount(input);

      executeCmd(path + "/bin/yb-ctl stop_node 1", "Stop node 1", 10);
      pause();
      input.clear();
      input.put("127.0.0.2", 6);
      input.put("127.0.0.3", 6);
      verifyCount(input);

      executeCmd(path + "/bin/yb-ctl start_node 1 --placement_info \"aws.us-west.us-west-1a\"",
          "Start node 1", 20);
      pause();
      //Sleep for 10 sec to make sure refresh happens and hikari pool is recreated
      try {
        Thread.sleep(10000);
      } catch (InterruptedException io) {
      }
      input.clear();
      input.put("127.0.0.1", 4);
      input.put("127.0.0.2", 4);
      input.put("127.0.0.3", 4);
      verifyCount(input);

      executeCmd(path + "/bin/yb-ctl stop_node 1", "Stop node 1", 10);
      pause();
      input.clear();
      input.put("127.0.0.2", 6);
      input.put("127.0.0.3", 6);
      verifyCount(input);

      executeCmd(path + "/bin/yb-ctl stop_node 2", "Stop node 2", 10);
      pause();
      input.clear();
      input.put("127.0.0.3", 12);
      verifyCount(input);

      executeCmd(path + "/bin/yb-ctl stop_node 3", "Stop node 3", 10);
      pause();
      input.clear();
      input.put("127.0.0.4", 4);
      input.put("127.0.0.5", 4);
      input.put("127.0.0.6", 4);
      verifyCount(input);

      executeCmd(path + "/bin/yb-ctl stop_node 4", "Stop node 4", 10);
      pause();
      input.clear();
      input.put("127.0.0.5", 6);
      input.put("127.0.0.6", 6);
      verifyCount(input);

      executeCmd(path + "/bin/yb-ctl stop_node 5", "Stop node 5", 10);
      pause();
      input.clear();
      input.put("127.0.0.6", 12);
      verifyCount(input);

      executeCmd(path + "/bin/yb-ctl stop_node 6", "Stop node 6", 10);
      pause();
      input.clear();
      input.put("127.0.0.7", 4);
      input.put("127.0.0.8", 4);
      input.put("127.0.0.9", 4);
      verifyCount(input);

      executeCmd(path + "/bin/yb-ctl stop_node 7", "Stop node 7", 10);
      pause();
      input.clear();
      input.put("127.0.0.8", 6);
      input.put("127.0.0.9", 6);
      verifyCount(input);

      executeCmd(path + "/bin/yb-ctl stop_node 8", "Stop node 8", 10);
      pause();
      input.clear();
      input.put("127.0.0.9", 12);
      verifyCount(input);

    } finally {
      executeCmd(path + "/bin/yb-ctl destroy", "Stop YugabyteDB cluster", 10);
    }
  }

  /**
   * Start RF=3 cluster with 9 nodes and with placements
   * 127.0.0.1, 127.0.0.2, 127.0.0.3   -> us-west-1a,
   * 127.0.0.4, 127.0.0.5, 127.0.0.6   -> us-east-2a
   * 127.0.0.7, 127.0.0.8, 127.0.0.9   -> eu-west-2a
   */

  private static void startYBDBClusterWithNineNodes() {
    executeCmd(path + "/bin/yb-ctl destroy", "Stop YugabyteDB cluster", 10);

    executeCmd(path + "/bin/yb-ctl --rf 3 start --placement_info \"aws.us-west.us-west-1a\" ",
        "Start YugabyteDB rf=3 cluster", 60);
    executeCmd(path + "/bin/yb-ctl add_node --placement_info \"aws.us-east.us-east-2a\"",
        "Add a node", 10);
    executeCmd(path + "/bin/yb-ctl add_node --placement_info \"aws.us-east.us-east-2a\"",
        "Add a node", 10);
    executeCmd(path + "/bin/yb-ctl add_node --placement_info \"aws.us-east.us-east-2a\"",
        "Add a node", 10);

    executeCmd(path + "/bin/yb-ctl add_node --placement_info \"aws.eu-west.eu-west-2a\"",
        "Add a node", 10);
    executeCmd(path + "/bin/yb-ctl add_node --placement_info \"aws.eu-west.eu-west-2a\"",
        "Add a node", 10);
    executeCmd(path + "/bin/yb-ctl add_node --placement_info \"aws.eu-west.eu-west-2a\"",
        "Add a node", 10);

    try {
      Thread.sleep(5000);
    } catch (InterruptedException ie) {
    }
  }

  private static HikariDataSource configureHikari(String url) {
    String ds_yb = "com.yugabyte.ysql.YBClusterAwareDataSource";
    String port = "5433";
    Properties poolProperties = new Properties();
    poolProperties.setProperty("poolName", "wellsfargo");
    poolProperties.setProperty("dataSourceClassName", ds_yb);
    poolProperties.setProperty("maximumPoolSize", "12");
    poolProperties.setProperty("allowPoolSuspension", "true");
    poolProperties.setProperty("maxLifetime", "35000");
    poolProperties.setProperty("idleTimeout", "0");
    poolProperties.setProperty("validationTimeout", "2000");
    poolProperties.setProperty("keepaliveTime", "30000"); // 120000
    poolProperties.setProperty("connectionTestQuery", "select now()");
    poolProperties.setProperty("connectionInitSql", "SET yb_read_from_followers=TRUE;SET " +
        "yb_follower_read_staleness_ms=60000;SET default_transaction_read_only=TRUE;");

    poolProperties.setProperty("dataSource.user", "yugabyte");
    poolProperties.setProperty("dataSource.currentSchema", "yugabyte");
    poolProperties.setProperty("dataSource.url", url);
    poolProperties.setProperty("dataSource.topologyKeys", "aws.us-west.*:1,aws.us-east.*:2,aws" +
        ".eu-west.*:3");
    poolProperties.setProperty("dataSource.loadBalanceHosts", "true");
    poolProperties.setProperty("dataSource.ybServersRefreshInterval", "30");

    HikariConfig hikariConfig = new HikariConfig(poolProperties);
    hikariConfig.validate();
    return new HikariDataSource(hikariConfig);
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

  private static void verifyCount(Map<String, Integer> input) {
    Iterator<Map.Entry<String, Integer>> it = input.entrySet().iterator();
    while (it.hasNext()) {
      Map.Entry<String, Integer> e = it.next();
      verifyOn(e.getKey(), e.getValue());
    }
  }

  private static ProcessBuilder builder = new ProcessBuilder();

  private static void verifyOn(String server, int expectedCount) {
    try {
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
      System.out.println("Exit code: " + exitCode + ", Client backend processes on " + server +
          ": " + (count.length - 1) + ", expected: " + expectedCount);
    } catch (Exception e) {
      System.out.println("Exception in VerifyOn() " + e);
    }
  }
}
