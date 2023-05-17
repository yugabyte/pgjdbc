// Copyright (c) YugaByte, Inc.
//
// Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
// in compliance with the License.  You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software distributed under the License
// is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
// or implied.  See the License for the specific language governing permissions and limitations
// under the License.
//

package com.yugabyte.ysql;

import org.postgresql.jdbc.PgConnection;
import org.postgresql.util.GT;
import org.postgresql.util.PSQLException;
import org.postgresql.util.HostSpec;
import org.postgresql.util.PSQLState;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
import java.util.logging.Level;
import java.util.logging.Logger;

import static com.yugabyte.ysql.LoadBalanceProperties.*;
import static org.postgresql.Driver.hostSpecs;

public class ClusterAwareLoadBalancer implements LoadBalancer {
  protected static final String GET_SERVERS_QUERY = "select * from yb_servers()";
  protected static final Logger LOGGER = Logger.getLogger("org.postgresql.Driver");
  /**
   * The default value should ideally match the interval at which the server-list is updated at
   * cluster side for yb_servers() function. Here, kept it 5 seconds which is not too high (30s) and
   * not too low (1s).
   */
  static final int DEFAULT_FAILED_HOST_TTL_SECONDS = 5;

  private static volatile ClusterAwareLoadBalancer instance;
  private long lastServerListFetchTime = 0L;
  private volatile ArrayList<String> servers = null;
  Map<String, Integer> hostToNumConnMap = new HashMap<>();
  Map<String, Integer> hostToNumConnCount = new HashMap<>();
  Map<String, Long> unreachableHosts = new HashMap<String, Long>();
  protected Map<String, String> hostPortMap = new HashMap<>();
  final Map<String, Integer> hostToPriorityMap = new HashMap<>();
  protected Map<String, String> hostPortMapPublic = new HashMap<>();
  protected ArrayList<String> currentPublicIps = new ArrayList<>();
  protected Boolean useHostColumn = null;
  protected int refreshListSeconds = LoadBalanceProperties.DEFAULT_REFRESH_INTERVAL;

  public static ClusterAwareLoadBalancer instance() {
    return instance;
  }

  public ClusterAwareLoadBalancer() {
  }

  public static ClusterAwareLoadBalancer getInstance(int refreshListSeconds) {
    if (instance == null) {
      synchronized (ClusterAwareLoadBalancer.class) {
        if (instance == null) {
          instance = new ClusterAwareLoadBalancer();
          instance.refreshListSeconds =
              refreshListSeconds > 0 && refreshListSeconds <= LoadBalanceProperties.MAX_REFRESH_INTERVAL ?
                  refreshListSeconds : LoadBalanceProperties.DEFAULT_REFRESH_INTERVAL;
        }
      }
    }
    return instance;
  }

  public String getPort(String host) {
    String port = hostPortMap.get(host);
    if (port == null) {
      port = hostPortMapPublic.get(host);
    }
    return port;
  }

  public boolean hasMorePreferredNode(String chosenHost) {
    return false;
  }

  public synchronized String getLeastLoadedServer(List<String> failedHosts, ArrayList<String> timedOutHosts) {
    LOGGER.fine("failedHosts: " + failedHosts + ", hostToNumConnMap: " + hostToNumConnMap);
    if ((hostToNumConnMap.isEmpty() && currentPublicIps.isEmpty())
        || Boolean.getBoolean(EXPLICIT_FALLBACK_ONLY_KEY)) {
      // Try fallback on rest of the cluster nodes
      servers = getPrivateOrPublicServers(new ArrayList<String>(), currentPublicIps);
      if (servers != null && !servers.isEmpty()) {
        for (String h : servers) {
          if (!hostToNumConnMap.containsKey(h)) {
            if (hostToNumConnCount.containsKey(h)) {
              hostToNumConnMap.put(h, hostToNumConnCount.get(h));
            } else {
              hostToNumConnMap.put(h, 0);
            }
          }
        }
      } else {
        return null;
      }
    }
    int min = Integer.MAX_VALUE;
    ArrayList<String> minConnectionsHostList = new ArrayList<>();
    for (String h : hostToNumConnMap.keySet()) {
      boolean wasTimedOutHost = timedOutHosts != null && timedOutHosts.contains(h);
      if (failedHosts.contains(h) || wasTimedOutHost) {
        LOGGER.fine("Skipping failed host " + h + "(was timed out host=" + wasTimedOutHost +")");
        continue;
      }
      int currLoad = hostToNumConnMap.get(h);
      // System.out.println("Current load for host: " + h + " is " + currLoad);
      if (currLoad < min) {
        min = currLoad;
        minConnectionsHostList.clear();
        minConnectionsHostList.add(h);
      } else if (currLoad == min) {
        minConnectionsHostList.add(h);
      }
    }
    // Choose a random from the minimum list
    String chosenHost = null;
    if (minConnectionsHostList.size() > 0) {
      // Implement this in PGX and other language drivers
      int idx = ThreadLocalRandom.current().nextInt(0, minConnectionsHostList.size());
      chosenHost = minConnectionsHostList.get(idx);
    }
    if (chosenHost != null) {
      LOGGER.log(Level.FINE,
          getLoadBalancerType() + ": Host chosen for new connection: " + chosenHost);
      updateConnectionMap(chosenHost, 1);
    } else if (useHostColumn == null) {
      // Current host inet addr did not match with either host inet or public_ip inet addr AND
      // Now we have exhausted the servers list which was populated with (private) host values.
      // So try connecting to the public_ips.
      ArrayList<String> newList = new ArrayList<String>();
      newList.addAll(currentPublicIps); // todo try respective public ip list?
      if (!newList.isEmpty()) {
        LOGGER.info("No host found, attempting the public ips...");
        useHostColumn = Boolean.FALSE;
        servers = newList;
        unreachableHosts.clear();
        for (String h : servers) {
          if (!hostToNumConnMap.containsKey(h)) {
            if (hostToNumConnCount.containsKey(h)) {
              hostToNumConnMap.put(h, hostToNumConnCount.get(h));
            } else {
              hostToNumConnMap.put(h, 0);
            }
          }
        }
        // base condition for this recursive call is useHostColumn != null
        return getLeastLoadedServer(failedHosts, timedOutHosts);
      }
    }
    LOGGER.log(Level.FINE,
        getLoadBalancerType() + ": Host chosen for new connection: " + chosenHost);
    return chosenHost;
  }

  public static boolean forceRefresh = false;

  public boolean needsRefresh() {
    if (forceRefresh) {
      LOGGER.log(Level.FINE, getLoadBalancerType() + ": Force Refresh is set to true");
      return true;
    }
    long currentTimeInMillis = System.currentTimeMillis();
    long diff = (currentTimeInMillis - lastServerListFetchTime) / 1000;
    boolean firstTime = servers == null;
    refreshListSeconds = Integer.getInteger(REFRESH_INTERVAL_KEY, refreshListSeconds);
    refreshListSeconds = (refreshListSeconds < 0 || refreshListSeconds > MAX_REFRESH_INTERVAL) ?
        DEFAULT_REFRESH_INTERVAL : refreshListSeconds;
    if (firstTime || diff > refreshListSeconds) {
      LOGGER.log(Level.FINE, getLoadBalancerType() + ": "
          + "Needs refresh as list of servers may be stale or being fetched for the first time");
      return true;
    }
    LOGGER.log(Level.FINE, getLoadBalancerType() + ": Refresh not required.");
    return false;
  }

  protected static String columnToUseForHost = null;

  protected ArrayList<String> getCurrentServers(Connection conn) throws SQLException {
    Statement st = conn.createStatement();
    LOGGER.log(Level.FINE, getLoadBalancerType() + ": Executing query: "
        + GET_SERVERS_QUERY + " to fetch list of servers");
    ResultSet rs = st.executeQuery(GET_SERVERS_QUERY);
    ArrayList<String> currentPrivateIps = new ArrayList<>();
    String hostConnectedTo = ((PgConnection) conn).getQueryExecutor().getHostSpec().getHost();
    InetAddress hostConnectedInetAddr = LoadBalanceUtils.getConnectedInetAddr(conn);

    hostToPriorityMap.clear();
    clearHostIPLists();
    while (rs.next()) {
      String host = rs.getString("host");
      String publicHost = rs.getString("public_ip");
      String port = rs.getString("port");
      String cloud = rs.getString("cloud");
      String region = rs.getString("region");
      String zone = rs.getString("zone");
      hostPortMap.put(host, port);
      updatePriorityMap(host, cloud, region, zone);
      hostPortMapPublic.put(publicHost, port);
      if (!unreachableHosts.containsKey(host)) {
        updateCurrentHostList(currentPrivateIps, host, publicHost, cloud, region, zone);
      }
      InetAddress hostInetAddr;
      InetAddress publicHostInetAddr;
      try {
        hostInetAddr = InetAddress.getByName(host);
      } catch (UnknownHostException e) {
        // set the hostInet to null
        hostInetAddr = null;
      }
      try {
        publicHostInetAddr = !publicHost.isEmpty()
            ? InetAddress.getByName(publicHost) : null;
      } catch (UnknownHostException e) {
        // set the publicHostInetAddr to null
        publicHostInetAddr = null;
      }
      if (useHostColumn == null) {
        if (hostConnectedInetAddr.equals(hostInetAddr)) {
          useHostColumn = Boolean.TRUE;
        } else if (hostConnectedInetAddr.equals(publicHostInetAddr)) {
          useHostColumn = Boolean.FALSE;
        }
      }
    }
    return getPrivateOrPublicServers(currentPrivateIps, currentPublicIps);
  }

  protected void updatePriorityMap(String host, String cloud, String region, String zone) {
  }

  protected void clearHostIPLists() {
    currentPublicIps.clear();
  }

  protected ArrayList<String> getPrivateOrPublicServers(ArrayList<String> privateHosts,
      ArrayList<String> publicHosts) {
    if (useHostColumn == null) {
      if (publicHosts.isEmpty()) {
        useHostColumn = Boolean.TRUE;
      }
      LOGGER.log(Level.FINE, getLoadBalancerType() + ": Either private or public address should "
          + "have matched with one of the servers. Using private addresses.");
      return privateHosts;
    }
    ArrayList<String> currentHosts = useHostColumn ? privateHosts : publicHosts;
    LOGGER.log(Level.FINE, getLoadBalancerType() + ": List of servers got {0}", currentHosts);
    return currentHosts;
  }

  protected void updateCurrentHostList(ArrayList<String> currentPrivateIps, String host,
      String publicIp, String cloud, String region, String zone) {
    currentPrivateIps.add(host);
    if (!publicIp.trim().isEmpty()) {
      currentPublicIps.add(publicIp);
    }
  }

  public String getLoadBalancerType() {
    return "ClusterAwareLoadBalancer";
  }

  public synchronized boolean refresh(Connection conn) throws SQLException {
    if (!needsRefresh()) {
      return true;
    }
    // else clear server list
    long currTime = System.currentTimeMillis();
    lastServerListFetchTime = currTime;
    long now = System.currentTimeMillis() / 1000;
    long failedHostTTL = Long.getLong("failed-host-ttl-seconds", DEFAULT_FAILED_HOST_TTL_SECONDS);
    Set<String> possiblyReachableHosts = new HashSet();
    for (Map.Entry<String, Long> e : unreachableHosts.entrySet()) {
      if ((now - e.getValue()) > failedHostTTL) {
        LOGGER.fine("Putting host  " + e.getKey() + " into possiblyReachableHosts");
        possiblyReachableHosts.add(e.getKey());
      } else {
        LOGGER.fine("Not removing this host from unreachableHosts: " + e.getKey());
      }
    }

    boolean emptyHostToNumConnMap = false;
    for (String h : possiblyReachableHosts) {
      unreachableHosts.remove(h);
      emptyHostToNumConnMap = true;
    }

    if (emptyHostToNumConnMap && !hostToNumConnMap.isEmpty()) {
      LOGGER.fine("Clearing hostToNumConnMap: " + hostToNumConnMap.keySet());
      for (String h : hostToNumConnMap.keySet()) {
        hostToNumConnCount.put(h, hostToNumConnMap.get(h));
      }
      LOGGER.fine("hosts in hostToNumConnCount: " + hostToNumConnCount);
      hostToNumConnMap.clear();
    }

    servers = getCurrentServers(conn);
    if (servers == null) {
      return false;
    }

    for (String h : servers) {
      if (!hostToNumConnMap.containsKey(h) && !unreachableHosts.containsKey(h)) {
        if (hostToNumConnCount.containsKey(h)) {
          hostToNumConnMap.put(h, hostToNumConnCount.get(h));
        } else {
          hostToNumConnMap.put(h, 0);
        }
        LOGGER.fine("Added host " + h + " to hostToNumConnMap, with count " + hostToNumConnMap.get(h));
      }
    }
    return true;
  }

  public List<String> getServers() {
    return Collections.unmodifiableList(servers);
  }

  public synchronized void updateConnectionMap(String host, int incDec) {
    LOGGER.log(Level.FINE, getLoadBalancerType() + ": updating connection count for {0} by {1}",
        new String[]{host, String.valueOf(incDec)});
    Integer currentCount = hostToNumConnMap.get(host);
    if ((currentCount == null || currentCount == 0) && incDec < 0) {
      return;
    }
    if (currentCount == null && incDec > 0) {
      hostToNumConnMap.put(host, incDec);
    } else if (currentCount != null) {
      hostToNumConnMap.put(host, currentCount + incDec);
    }
  }

  public synchronized void decrementHostToNumConnCount(String chosenHost) {
  }

  public Set<String> getUnreachableHosts() {
    return unreachableHosts.keySet();
  }

  public synchronized void updateFailedHosts(String chosenHost) {
    unreachableHosts.putIfAbsent(chosenHost, System.currentTimeMillis() / 1000);
    hostToNumConnMap.remove(chosenHost);
    hostToNumConnCount.remove(chosenHost);
  }

  protected String loadBalancingNodes() {
    return "all";
  }

  public void setForRefresh() {
    lastServerListFetchTime = 0L;
  }

  @Override
  public Connection getConnection(LoadBalanceProperties loadBalanceProperties, String user,
      String dbName) {
    LOGGER.log(Level.FINE, "GetConnectionBalanced called");
    LoadBalancer loadBalancer = loadBalanceProperties.getAppropriateLoadBalancer();
    Properties props = loadBalanceProperties.getStrippedProperties();
    String url = loadBalanceProperties.getStrippedURL();
    Set<String> unreachableHosts = loadBalancer.getUnreachableHosts();
    List<String> failedHosts = new ArrayList<>(unreachableHosts);
    String chosenHost = loadBalancer.getLeastLoadedServer(failedHosts);
    PgConnection newConnection = null;
    Connection controlConnection = null;
    SQLException firstException = null;
    if (chosenHost == null) {
      boolean connectionCreated = false;
      boolean gotException = false;
      try {
        HostSpec[] hspec = hostSpecs(loadBalanceProperties.getOriginalProperties());
        controlConnection = new PgConnection(hspec, user, dbName, props, url);
        connectionCreated = true;
        if (!loadBalancer.refresh(controlConnection)) {
          LOGGER.log(Level.WARNING, "yb_servers() refresh failed in first"
              + " attempt itself. Falling back to default behaviour");
          return null;
        }
        controlConnection.close();
      } catch (SQLException ex) {
        if (PSQLState.UNDEFINED_FUNCTION.getState().equals(ex.getSQLState())) {
          return null;
        }
        gotException = true;
      } finally {
        if (gotException && connectionCreated) {
          try {
            controlConnection.close();
          } catch (SQLException throwables) {
            // ignore it was to be closed
          }
        }
      }
      chosenHost = loadBalancer.getLeastLoadedServer(failedHosts);
    }
    if (chosenHost == null) {
      return null;
    }
    // refresh can also fail on a particular server so try in loop till
    // options are exhausted
    while (chosenHost != null) {
      try {
        props.setProperty("PGHOST", chosenHost);
        String port = loadBalancer.getPort(chosenHost);
        if (port != null) {
          props.setProperty("PGPORT", port);
        }
        newConnection = new PgConnection(
            hostSpecs(props), user, dbName, props, url);
        newConnection.setLoadBalancer(loadBalancer);
        if (!loadBalancer.refresh(newConnection)) {
          // There seems to be a problem with the current chosen host as well.
          // Close the connection and try next
          LOGGER.log(Level.WARNING, "yb_servers() refresh returned no servers");
          loadBalancer.updateConnectionMap(chosenHost, -1);
          failedHosts.add(chosenHost);
          // but let the refresh be forced the next time it is tried.
          loadBalancer.setForRefresh();
          try {
            newConnection.close();
          } catch (Exception e) {
            // ignore as exception is expected. This close is for any other cleanup
            // which the driver side may be doing
          }
        } else {
          boolean betterNodeAvailable = loadBalancer.hasMorePreferredNode(chosenHost);
          if (betterNodeAvailable) {
            LOGGER.log(Level.FINE,
                "A higher priority node than " + chosenHost + " is available");
            loadBalancer.decrementHostToNumConnCount(chosenHost);
            newConnection.close();
            return getConnection(loadBalanceProperties, user, dbName);
          }
          return newConnection;
        }
      } catch (SQLException ex) {
        // Let the refresh be forced the next time it is tried.
        loadBalancer.setForRefresh();
        // close the connection for any cleanup. We can ignore the exception here
        try {
          newConnection.close();
        } catch (Exception e) {
          // ignore as the connection is already bad that's why we are here. Calling
          // close so that client side cleanup can happen.
        }
        failedHosts.add(chosenHost);
        if (PSQLState.CONNECTION_UNABLE_TO_CONNECT.getState().equals(ex.getSQLState())) {
          if (firstException == null) {
            firstException = ex;
          }
          // TODO log exception go to the next one after adding to failed list
          LOGGER.log(Level.FINE,
              "couldn't connect to " + chosenHost + ", adding it to failed host list");
          loadBalancer.updateFailedHosts(chosenHost);
        } else {
          // Log the exception. Consider other failures as temporary and not as serious as
          // PSQLState.CONNECTION_UNABLE_TO_CONNECT. So for other failures it will be ignored
          // only in this attempt, instead of adding it in the failed host list of the
          // load balancer itself because it won't be tried till the next refresh happens.
          LOGGER.log(Level.WARNING,
              "got exception " + ex.getMessage() + ", while connecting to " + chosenHost);
        }
      }
      chosenHost = loadBalancer.getLeastLoadedServer(failedHosts);
    }
    return null;
  }

  public void printHostToConnMap() {
    System.out.println("Current load on " + loadBalancingNodes() + " servers");
    System.out.println("-------------------");
    for (Map.Entry<String, Integer> e : hostToNumConnMap.entrySet()) {
      System.out.println(e.getKey() + " - " + e.getValue());
    }
  }

  public int getConnectionCountFor(String server) {
    return (hostToNumConnMap.get(server) == null) ? 0 : hostToNumConnMap.get(server);
  }
}
