package com.yugabyte.ysql;

import static com.yugabyte.ysql.LoadBalanceProperties.DEFAULT_FAILED_HOST_TTL_SECONDS;
import static com.yugabyte.ysql.LoadBalanceProperties.FAILED_HOST_RECONNECT_DELAY_SECS_KEY;
import static org.postgresql.Driver.hostSpecs;

import org.postgresql.jdbc.PgConnection;
import org.postgresql.util.GT;
import org.postgresql.util.HostSpec;
import org.postgresql.util.PSQLException;
import org.postgresql.util.PSQLState;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

public class LoadBalanceManager {

  private static ConcurrentHashMap<String, NodeInfo> clusterInfoMap = new ConcurrentHashMap<>();
  private static Connection controlConnection = null;
  protected static final String GET_SERVERS_QUERY = "select * from yb_servers()";
  protected static final Logger LOGGER = Logger.getLogger(LoadBalanceManager.class.getName());
  private static long lastRefreshTime;
  private static boolean forceRefreshOnce = false;
  private static Boolean useHostColumn = null;

  /**
   * FOR TEST PURPOSE ONLY
   */
  public static synchronized void clear() {
    forceRefreshOnce = false;
    clusterInfoMap.clear();
    lastRefreshTime = 0;
    useHostColumn = null;
  }

  /**
   * FOR TEST PURPOSE ONLY
   */
  public static void setForceRefreshOnce() {
    forceRefreshOnce = true;
  }

  /**
   * FOR TEST PURPOSE ONLY
   */
  public static void printHostToConnectionMap() {
    System.out.println("Current load on servers");
    System.out.println("-------------------");
    for (Map.Entry<String, NodeInfo> e : clusterInfoMap.entrySet()) {
      System.out.println(e.getKey() + " - " + e.getValue().connectionCount);
    }
  }

  public static long getLastRefreshTime() {
    return lastRefreshTime;
  }

  public static boolean needsRefresh(long refreshInterval) {
    if (forceRefreshOnce) {
      LOGGER.finest("forceRefreshOnce is set to true");
      return true;
    }
    long elapsed = (System.currentTimeMillis() - lastRefreshTime) / 1000;
    if (elapsed > refreshInterval) {
      LOGGER.fine("Needs refresh as list of servers may be stale or being fetched for " +
          "the first time, refreshInterval: " + refreshInterval);
      return true;
    }
    LOGGER.fine("Refresh not required, refreshInterval: " + refreshInterval);
    return false;
  }

  private static synchronized boolean refresh(Connection conn, long refreshInterval) throws SQLException {
    forceRefreshOnce = false;
    Statement st = conn.createStatement();
    LOGGER.fine("Executing query: " + GET_SERVERS_QUERY + " to fetch list of servers");
    ResultSet rs = st.executeQuery(GET_SERVERS_QUERY);
    InetAddress hostConnectedInetAddress = getConnectedInetAddress(conn);

    boolean publicIPsGivenForAll = true;
    while (rs.next()) {
      String host = rs.getString("host");
      LOGGER.finest("Received entry for host " + host);
      String publicHost = rs.getString("public_ip");
      publicHost = publicHost == null ? "" : publicHost;
      String port = rs.getString("port");
      String cloud = rs.getString("cloud");
      String region = rs.getString("region");
      String zone = rs.getString("zone");
      NodeInfo nodeInfo = clusterInfoMap.containsKey(host) ? clusterInfoMap.get(host) : new NodeInfo();
      synchronized (nodeInfo) {
        nodeInfo.host = host;
        nodeInfo.publicIP = publicHost;
        publicIPsGivenForAll = !publicHost.isEmpty();
        nodeInfo.placement = new CloudPlacement(cloud, region, zone);
        try {
          nodeInfo.port = Integer.valueOf(port);
        } catch (NumberFormatException nfe) {
          LOGGER.warning("Could not parse port " + port + " for host " + host + ", using 5433 instead.");
          nodeInfo.port = 5433;
        }
        long failedHostTTL = Long.getLong(FAILED_HOST_RECONNECT_DELAY_SECS_KEY, DEFAULT_FAILED_HOST_TTL_SECONDS);
        if (nodeInfo.isDown) {
          if (System.currentTimeMillis() - nodeInfo.isDownSince > (failedHostTTL * 1000)) {
            LOGGER.fine("Marking " + nodeInfo.host + " as UP since failed-host-reconnect-delay-secs has elapsed");
            nodeInfo.isDown = false;
          } else {
            LOGGER.fine("Keeping " + nodeInfo.host + " as DOWN since failed-host-reconnect-delay-secs has not elapsed");
          }
        }
      }
      clusterInfoMap.putIfAbsent(host, nodeInfo);

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
        if (hostConnectedInetAddress.equals(hostInetAddr)) {
          useHostColumn = Boolean.TRUE;
        } else if (hostConnectedInetAddress.equals(publicHostInetAddr)) {
          useHostColumn = Boolean.FALSE;
        }
      }
    }
    if ((useHostColumn != null && !useHostColumn) || (useHostColumn == null && publicIPsGivenForAll)) {
      LOGGER.info("Will be using publicIPs for establishing connections");
      Enumeration<String> hosts = clusterInfoMap.keys();
      while (hosts.hasMoreElements()) {
        NodeInfo info = clusterInfoMap.get(hosts.nextElement());
        clusterInfoMap.put(info.publicIP, info);
        clusterInfoMap.remove(info.host);
      }
    } else if (useHostColumn == null) {
      LOGGER.warning("Unable to identify set of addresses to use for establishing connections. " +
          "Using private addresses.");
    }
    lastRefreshTime = System.currentTimeMillis();
    return true;
  }

  public static void markAsFailed(String host) {
    NodeInfo info = clusterInfoMap.get(host);
    if (info == null) return; // unexpected
    synchronized (info) {
      String previous = info.isDown ? "DOWN" : "UP";
      info.isDown = true;
      info.isDownSince = System.currentTimeMillis();
      info.connectionCount = 0;
      LOGGER.info("Marked " + host + " as DOWN (was " + previous + " earlier)");
    }
  }

  public static int getLoad(String host) {
    NodeInfo info = clusterInfoMap.get(host);
    return info == null ? 0 : info.connectionCount;
  }

  public static ArrayList<String> getAllEligibleHosts(LoadBalancer policy) {
    ArrayList<String> list = new ArrayList<>();
    Set<Map.Entry<String, NodeInfo>> set = clusterInfoMap.entrySet();
    for (Map.Entry<String, NodeInfo> e : set) {
      if (policy.isHostEligible(e)) {
        list.add(e.getKey());
      } else {
        LOGGER.finest("Skipping " + e + " because it is not eligible.");
      }
    }
    return list;
  }

  public static ArrayList<String> getAllAvailableHosts(ArrayList<String> attempted) {
    ArrayList<String> list = new ArrayList<>();
    Enumeration<String> hosts = clusterInfoMap.keys();
    while (hosts.hasMoreElements()) {
      String h = hosts.nextElement();
      if (!attempted.contains(h) && !clusterInfoMap.get(h).isDown) {
        list.add(h);
      }
    }
    return list;
  }

  public static int getPort(String host) {
    NodeInfo info = clusterInfoMap.get(host);
    return info != null ? info.port : 5433;
  }

  public static boolean incrementConnectionCount(String host) {
    NodeInfo info = clusterInfoMap.get(host);
    if (info != null) {
      synchronized (info) {
        if (info.connectionCount < 0) {
          info.connectionCount = 0;
          LOGGER.fine("Resetting connection count for " + host + " to zero from " + info.connectionCount);
        }
        info.connectionCount += 1;
        return true;
      }
    }
    return false;
  }

  public static boolean decrementConnectionCount(String host) {
    NodeInfo info = clusterInfoMap.get(host);
    if (info != null) {
      synchronized (info) {
        info.connectionCount -= 1;
        LOGGER.fine("Decremented connection count for " + host + " by one: " + info.connectionCount);
        if (info.connectionCount < 0) {
          info.connectionCount = 0;
          LOGGER.fine("Resetting connection count for " + host + " to zero.");
        }
        return true;
      }
    }
    return false;
  }

  public static Connection getConnection(String url, Properties properties, String user,
      String database) {
    LoadBalanceProperties lbProperties = LoadBalanceProperties.getLoadBalanceProperties(url,
        properties);
    // Cleanup extra properties used for load balancing?
    if (lbProperties.hasLoadBalance()) {
      Connection conn = getConnection(lbProperties,  user, database);
      if (conn != null) {
        return conn;
      }
      LOGGER.warning("Failed to apply load balance. Trying normal connection");
    }
    return null;
  }

  public static Connection getConnection(LoadBalanceProperties loadBalanceProperties, String user,
      String dbName) {
    LoadBalancer lb = loadBalanceProperties.getAppropriateLoadBalancer();
    Properties props = loadBalanceProperties.getOriginalProperties();
    String url = loadBalanceProperties.getStrippedURL();

    if (!checkAndRefresh(loadBalanceProperties, lb, user, dbName)) {
      return null;
    }

    List<String> failedHosts = new ArrayList<>();
    String chosenHost = lb.getLeastLoadedServer(true, failedHosts);
    PgConnection newConnection;
    SQLException firstException = null;
    while (chosenHost != null) {
      try {
        props.setProperty("PGHOST", chosenHost);
        props.setProperty("PGPORT", String.valueOf(LoadBalanceManager.getPort(chosenHost)));
        newConnection = new PgConnection(hostSpecs(props), user, dbName, props, url);
        newConnection.setLoadBalancer(lb);
        LOGGER.fine("Created connection to " + chosenHost);
        return newConnection;
      } catch (SQLException ex) {
        // Let the refresh be forced the next time it is tried. todo Is this needed?
        forceRefreshOnce = true;
        failedHosts.add(chosenHost);
        LoadBalanceManager.decrementConnectionCount(chosenHost);
        if (PSQLState.CONNECTION_UNABLE_TO_CONNECT.getState().equals(ex.getSQLState())) {
          if (firstException == null) {
            firstException = ex;
          }
          LOGGER.fine("couldn't connect to " + chosenHost + ", adding it to failed host list");
          LoadBalanceManager.markAsFailed(chosenHost);
        } else {
          // Log the exception. Consider other failures as temporary and not as serious as
          // PSQLState.CONNECTION_UNABLE_TO_CONNECT. So for other failures it will be ignored
          // only in this attempt, instead of adding it in the failed host list of the
          // load balancer itself because it won't be tried till the next refresh happens.
          LOGGER.warning("got exception " + ex.getMessage() + " while connecting to " + chosenHost);
        }
      } catch (Throwable e) {
        LOGGER.fine("Received Throwable: " + e);
        LoadBalanceManager.decrementConnectionCount(chosenHost);
        throw e;
      }
      chosenHost = lb.getLeastLoadedServer(false, failedHosts);
    }
    return null;
  }

  private static synchronized boolean checkAndRefresh(LoadBalanceProperties loadBalanceProperties,
      LoadBalancer lb, String user, String dbName) {
    if (needsRefresh(lb.getRefreshListSeconds())) {
      Properties props = loadBalanceProperties.getOriginalProperties();
      String url = loadBalanceProperties.getStrippedURL();
      HostSpec[] hspec = hostSpecs(props);

      ArrayList<String> hosts = getAllAvailableHosts(new ArrayList<>());
      while (true) {
        try {
          if (controlConnection == null || controlConnection.isClosed()) {
            controlConnection = new PgConnection(hspec, user, dbName, props, url);
          }
          refresh(controlConnection, lb.getRefreshListSeconds());
          controlConnection.close();
          break;
        } catch (SQLException ex) {
          if (PSQLState.UNDEFINED_FUNCTION.getState().equals(ex.getSQLState())) {
            LOGGER.warning("Received error UNDEFINED_FUNCTION (42883)");
            return false;
          }
          if (PSQLState.CONNECTION_UNABLE_TO_CONNECT.getState().equals(ex.getSQLState())) {
            for (HostSpec h : hspec) {
              markAsFailed(h.getHost());
            }
          }
          // Retry until servers are available
          for (HostSpec h : hspec) {
            hosts.remove(h.getHost());
          }
          if (hosts.isEmpty()) {
            LOGGER.fine("Failed to establish control connection to available servers");
            return false;
          } else {
            // Try the first host in the list (don't have to check least loaded one since it's
            // just for the control connection)
            HostSpec hs = new HostSpec(hosts.get(0), LoadBalanceManager.getPort(hosts.get(0)),
                loadBalanceProperties.getOriginalProperties().getProperty("localSocketAddress"));
            hspec = new HostSpec[]{hs};
            controlConnection = null;
          }
        }
      }
    }
    return true;
  }

  public static InetAddress getConnectedInetAddress(Connection conn) throws SQLException {
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
    return hostConnectedInetAddr;
  }

  static class NodeInfo {

    private String host;
    private int port;
    private CloudPlacement placement;
    private String publicIP;
    private int connectionCount;
    private boolean isDown;
    private long isDownSince;

    public boolean isDown() {
      return isDown;
    }

    public String getHost() {
      return host;
    }

    public String getPublicIP() {
      return publicIP;
    }

    public CloudPlacement getPlacement() {
      return placement;
    }

    public int getPort() {
      return port;
    }

    public int getConnectionCount() {
      return connectionCount;
    }

    public long getIsDownSince() {
      return isDownSince;
    }
  }

  static class CloudPlacement {
    private final String cloud;
    private final String region;
    private final String zone;

    CloudPlacement(String cloud, String region, String zone) {
      this.cloud = cloud;
      this.region = region;
      this.zone = zone;
    }

    public boolean isContainedIn(Set<CloudPlacement> set) {
      if (this.zone.equals("*")) {
        for (CloudPlacement cp : set) {
          if (cp.cloud.equalsIgnoreCase(this.cloud) && cp.region.equalsIgnoreCase(this.region)) {
            return true;
          }
        }
      } else {
        for (CloudPlacement cp : set) {
          if (cp.cloud.equalsIgnoreCase(this.cloud)
              && cp.region.equalsIgnoreCase(this.region)
              && (cp.zone.equalsIgnoreCase(this.zone) || cp.zone.equals("*"))) {
            return true;
          }
        }
      }
      return false;
    }

    public int hashCode() {
      return cloud.hashCode() ^ region.hashCode() ^ zone.hashCode();
    }

    public boolean equals(Object other) {
      boolean equal = false;
      LOGGER.fine("equals called for this: " + this + " and other = " + other);
      if (other instanceof CloudPlacement) {
        CloudPlacement o = (CloudPlacement) other;
        equal = this.cloud.equalsIgnoreCase(o.cloud) &&
            this.region.equalsIgnoreCase(o.region) &&
            this.zone.equalsIgnoreCase(o.zone);
      }
      LOGGER.fine("equals returning: " + equal);
      return equal;
    }

    public String toString() {
      return "CloudPlacement: " + cloud + "." + region + "." + zone;
    }
  }
}
