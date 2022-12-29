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

public class ClusterAwareLoadBalancer {
  protected static final String GET_SERVERS_QUERY = "select * from yb_servers()";
  protected static final Logger LOGGER = Logger.getLogger("org.postgresql.Driver");

  private static volatile ClusterAwareLoadBalancer instance;
  private long lastServerListFetchTime = 0L;
  private volatile ArrayList<String> servers = null;
  Map<String, Integer> hostToNumConnMap = new HashMap<>();
  Map<String, Long> unreachableHosts = new HashMap<String, Long>();
  protected Map<String, String> hostPortMap = new HashMap<>();
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

  public synchronized String getLeastLoadedServer(List<String> failedHosts) {
    if (hostToNumConnMap.isEmpty() && currentPublicIps.isEmpty()) {
      // Try fallback on rest of the cluster nodes
      servers = getPrivateOrPublicServers(new ArrayList<String>(), currentPublicIps);
      if (servers != null && !servers.isEmpty()) {
        LOGGER.fine("Falling back on the rest of the cluster nodes ...");
        for (String h : servers) {
          if (!hostToNumConnMap.containsKey(h)) {
            hostToNumConnMap.put(h, 0);
          }
        }
      } else {
        return null;
      }
    }
    int min = Integer.MAX_VALUE;
    ArrayList<String> minConnectionsHostList = new ArrayList<>();
    for (String h : hostToNumConnMap.keySet()) {
      if (failedHosts.contains(h)) {
        LOGGER.fine("Skipping failed host " + h);
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
      int idx = ThreadLocalRandom.current().nextInt(0, minConnectionsHostList.size());
      chosenHost = minConnectionsHostList.get(idx);
    }
    if (chosenHost != null) {
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
            hostToNumConnMap.put(h, 0);
          }
        }
        // base condition for this recursive call is useHostColumn != null
        return getLeastLoadedServer(failedHosts);
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
    InetAddress hostConnectedInetAddr;

    boolean isIpv6Addresses = hostConnectedTo.contains(":");
    if (isIpv6Addresses) {
      hostConnectedTo = hostConnectedTo.replace("[", "").replace("]", "");
    }

    try {
      hostConnectedInetAddr = InetAddress.getByName(hostConnectedTo);
    } catch (UnknownHostException e) {
      // This is totally unexpected. As the connection is already created on this host
      throw new PSQLException(GT.tr("Unexpected UnknownHostException for ${0} ", hostConnectedTo),
          PSQLState.UNKNOWN_STATE, e);
    }

    clearHostIPLists();
    while (rs.next()) {
      String host = rs.getString("host");
      String publicHost = rs.getString("public_ip");
      String port = rs.getString("port");
      String cloud = rs.getString("cloud");
      String region = rs.getString("region");
      String zone = rs.getString("zone");
      hostPortMap.put(host, port);
      hostPortMapPublic.put(publicHost, port);
      updateCurrentHostList(currentPrivateIps, host, publicHost, cloud, region, zone);
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

  protected String getLoadBalancerType() {
    return "ClusterAwareLoadBalancer";
  }

  public synchronized boolean refresh(Connection conn) throws SQLException {
    if (!needsRefresh()) {
      return true;
    }
    // else clear server list
    long currTime = System.currentTimeMillis();
    servers = getCurrentServers(conn);
    if (servers == null) {
      return false;
    }
    lastServerListFetchTime = currTime;
    long now = System.currentTimeMillis() / 1000;
    long failedHostTTL = Long.getLong("failed-host-ttl-seconds", 5);
    Set<String> possiblyReachableHosts = new HashSet();
    for (Map.Entry<String, Long> e : unreachableHosts.entrySet()) {
      if ((now - e.getValue()) > failedHostTTL) {
        possiblyReachableHosts.add(e.getKey());
      } else {
        LOGGER.fine("Not removing this host from unreachableHosts: " + e.getKey());
      }
    }
    for (String h : possiblyReachableHosts) {
      unreachableHosts.remove(h);
    }

    for (String h : servers) {
      if (!hostToNumConnMap.containsKey(h)) {
        hostToNumConnMap.put(h, 0);
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

  public Set<String> getUnreachableHosts() {
    return unreachableHosts.keySet();
  }

  public synchronized void updateFailedHosts(String chosenHost) {
    unreachableHosts.putIfAbsent(chosenHost, System.currentTimeMillis() / 1000);
    hostToNumConnMap.remove(chosenHost);
  }

  protected String loadBalancingNodes() {
    return "all";
  }

  public void setForRefresh() {
    lastServerListFetchTime = 0L;
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
