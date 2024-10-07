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
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

public class LoadBalanceService {

  private static final ConcurrentHashMap<String, NodeInfo> clusterInfoMap = new ConcurrentHashMap<>();
  static final byte STRICT_PREFERENCE = 0b00000001;
  private static Connection controlConnection = null;
  protected static final String GET_SERVERS_QUERY = "select * from yb_servers()";
  protected static final Logger LOGGER = Logger.getLogger("org.postgresql." + LoadBalanceService.class.getName());
  private static long lastRefreshTime;
  private static boolean forceRefreshOnce = false;
  private static Boolean useHostColumn = null;

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

  /**
   * FOR TEST PURPOSE ONLY
   */
  static synchronized void clear() throws SQLException {
    LOGGER.warning("Clearing LoadBalanceService state for testing purposes");
    clusterInfoMap.clear();
    if (controlConnection != null) {
      controlConnection.close();
      controlConnection = null;
    }
    lastRefreshTime = 0;
    forceRefreshOnce = false;
    useHostColumn = null;
  }

  static long getLastRefreshTime() {
    return lastRefreshTime;
  }

  private static boolean needsRefresh(long refreshInterval) {
    if (forceRefreshOnce) {
      LOGGER.fine("forceRefreshOnce is set to true");
      return true;
    }
    long elapsed = (System.currentTimeMillis() - lastRefreshTime) / 1000;
    if (elapsed >= refreshInterval) {
      LOGGER.fine("Needs refresh as list of servers may be stale or being fetched for "
          + "the first time, refreshInterval: " + refreshInterval);
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
      String nodeType = rs.getString("node_type");
      NodeInfo nodeInfo = clusterInfoMap.containsKey(host) ? clusterInfoMap.get(host) : new NodeInfo();
      synchronized (nodeInfo) {
        nodeInfo.host = host;
        nodeInfo.publicIP = publicHost;
        publicIPsGivenForAll = !publicHost.isEmpty();
        nodeInfo.placement = new CloudPlacement(cloud, region, zone);
        LOGGER.fine("Setting node_type to " + nodeType + " for host " + host);
        nodeInfo.nodeType = nodeType;
        try {
          nodeInfo.port = Integer.valueOf(port);
        } catch (NumberFormatException nfe) {
          LOGGER.warning("Could not parse port " + port + " for host " + host + ", using 5433 instead.");
          nodeInfo.port = 5433;
        }
        long failedHostTTL = Long.getLong(FAILED_HOST_RECONNECT_DELAY_SECS_KEY, DEFAULT_FAILED_HOST_TTL_SECONDS);
        if (nodeInfo.isDown) {
          if (System.currentTimeMillis() - nodeInfo.isDownSince > (failedHostTTL * 1000)) {
            LOGGER.fine("Marking " + nodeInfo.host + " as UP since failed-host-reconnect-delay-secs (" + failedHostTTL + "s) has elapsed");
            nodeInfo.isDown = false;
          } else {
            LOGGER.fine("Keeping " + nodeInfo.host + " as DOWN since failed-host-reconnect-delay-secs (" + failedHostTTL + "s) has not elapsed");
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
      LOGGER.warning("Unable to identify set of addresses to use for establishing connections. "
          + "Using private addresses.");
    }
    lastRefreshTime = System.currentTimeMillis();
    return true;
  }

  private static void markAsFailed(String host) {
    NodeInfo info = clusterInfoMap.get(host);
    if (info == null) {
      return; // unexpected
    }
    synchronized (info) {
      String previous = info.isDown ? "DOWN" : "UP";
      info.isDown = true;
      info.isDownSince = System.currentTimeMillis();
      info.connectionCount = 0;
      LOGGER.info("Marked " + host + " as DOWN (was " + previous + " earlier)");
    }
  }

  static int getLoad(String host) {
    NodeInfo info = clusterInfoMap.get(host);
    return info == null ? 0 : info.connectionCount;
  }

  static ArrayList<String> getAllEligibleHosts(LoadBalancer policy, Byte requestFlags) {
    ArrayList<String> list = new ArrayList<>();
    Set<Map.Entry<String, NodeInfo>> set = clusterInfoMap.entrySet();
    for (Map.Entry<String, NodeInfo> e : set) {
      if (policy.isHostEligible(e, requestFlags)) {
        list.add(e.getKey());
      } else {
        LOGGER.finest("Skipping " + e + " because it is not eligible.");
      }
    }
    return list;
  }

  private static ArrayList<String> getAllAvailableHosts(ArrayList<String> attempted) {
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

  private static int getPort(String host) {
    NodeInfo info = clusterInfoMap.get(host);
    return info != null ? info.port : 5433;
  }

  static boolean incrementConnectionCount(String host) {
    NodeInfo info = clusterInfoMap.get(host);
    if (info != null) {
      synchronized (info) {
        if (info.connectionCount < 0) {
          LOGGER.fine("Resetting connection count for " + host + " to zero from " + info.connectionCount);
          info.connectionCount = 0;
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

  public static Connection getConnection(String url, Properties properties,
      LoadBalanceProperties lbProperties, ArrayList<String> timedOutHosts) {
    // Cleanup extra properties used for load balancing?
    if (lbProperties.isLoadBalanceEnabled()) {
      Connection conn = getConnection(lbProperties, properties, timedOutHosts);
      if (conn != null) {
        return conn;
      }
      LOGGER.warning("Failed to apply load balance. Trying normal connection");
      properties.setProperty("PGHOST", lbProperties.getOriginalProperties().getProperty("PGHOST"));
      properties.setProperty("PGPORT", lbProperties.getOriginalProperties().getProperty("PGPORT"));
    }
    return null;
  }

  private static Connection getConnection(LoadBalanceProperties loadBalanceProperties,
      Properties props, ArrayList<String> timedOutHosts) {
    LoadBalancer lb = loadBalanceProperties.getAppropriateLoadBalancer();
    String url = loadBalanceProperties.getStrippedURL();

    if (!checkAndRefresh(loadBalanceProperties, lb)) {
      LOGGER.fine("Attempt to refresh info from yb_servers() failed");
      return null;
    }

    List<String> failedHosts = new ArrayList<>();
    String chosenHost = lb.getLeastLoadedServer(true, failedHosts, timedOutHosts);
    PgConnection newConnection;
    SQLException firstException = null;
    while (chosenHost != null) {
      try {
        props.setProperty("PGHOST", chosenHost);
        props.setProperty("PGPORT", String.valueOf(getPort(chosenHost)));
        if (timedOutHosts != null) {
          timedOutHosts.add(chosenHost);
        }
        newConnection = new PgConnection(hostSpecs(props), props, url);
        newConnection.setLoadBalancer(lb);
        LOGGER.fine("Created connection to " + chosenHost);
        return newConnection;
      } catch (SQLException ex) {
        // Let the refresh be forced the next time it is tried. todo Is this needed?
        forceRefreshOnce = true;
        failedHosts.add(chosenHost);
        decrementConnectionCount(chosenHost);
        if (PSQLState.CONNECTION_UNABLE_TO_CONNECT.getState().equals(ex.getSQLState())) {
          if (firstException == null) {
            firstException = ex;
          }
          LOGGER.fine("couldn't connect to " + chosenHost + ", adding it to failed host list");
          markAsFailed(chosenHost);
        } else {
          // Log the exception. Consider other failures as temporary and not as serious as
          // PSQLState.CONNECTION_UNABLE_TO_CONNECT. So for other failures it will be ignored
          // only in this attempt, instead of adding it in the failed host list of the
          // load balancer itself because it won't be tried till the next refresh happens.
          LOGGER.warning("got exception " + ex.getMessage() + " while connecting to " + chosenHost);
        }
      } catch (Throwable e) {
        LOGGER.fine("Received Throwable: " + e);
        decrementConnectionCount(chosenHost);
        throw e;
      }
      chosenHost = lb.getLeastLoadedServer(false, failedHosts, timedOutHosts);
    }
    LOGGER.fine("No host could be chosen");
    return null;
  }

  /**
   * @param loadBalanceProperties
   * @param lb LoadBalancer instance
   * @return true if the refresh was not required or if it was successful.
   */
  private static synchronized boolean checkAndRefresh(LoadBalanceProperties loadBalanceProperties,
      LoadBalancer lb) {
    if (needsRefresh(lb.getRefreshListSeconds())) {
      Properties props = loadBalanceProperties.getOriginalProperties();
      String url = loadBalanceProperties.getStrippedURL();
      Properties properties = new Properties(props);
      properties.setProperty("socketTimeout", "15");
      HostSpec[] hspec = hostSpecs(properties);

      ArrayList<String> hosts = getAllAvailableHosts(new ArrayList<>());
      while (true) {
        boolean refreshFailed = false;
        try {
          if (controlConnection == null || controlConnection.isClosed()) {
            controlConnection = new PgConnection(hspec, properties, url);
          }
          try {
            refresh(controlConnection, lb.getRefreshListSeconds());
            break;
          } catch (SQLException e) {
            // May fail with "terminating connection due to unexpected postmaster exit", 57P01
            refreshFailed = true;
            throw e;
          }
        } catch (SQLException ex) {
          if (refreshFailed) {
            LOGGER.fine("Exception while refreshing: " + ex + ", " + ex.getSQLState());
            String failed = ((PgConnection) controlConnection).getQueryExecutor().getHostSpec().getHost();
            markAsFailed(failed);
          } else {
            String msg = hspec.length > 1 ? " and others" : "";
            LOGGER.fine("Exception while creating control connection to "
                + hspec[0].getHost() + msg + ": " + ex + ", " + ex.getSQLState());
            for (HostSpec h : hspec) {
              hosts.remove(h.getHost());
            }
          }
          if (PSQLState.UNDEFINED_FUNCTION.getState().equals(ex.getSQLState())) {
            LOGGER.warning("Received UNDEFINED_FUNCTION for yb_servers()" +
                " (SQLState=42883). You may be using an older version of" +
                " YugabyteDB, consider upgrading it.");
            return false;
          }
          // Retry until servers are available
          if (hosts.isEmpty()) {
            LOGGER.fine("Failed to establish control connection to available servers");
            return false;
          } else if (!refreshFailed) {
            // Try the first host in the list (don't have to check least loaded one since it's
            // just for the control connection)
            HostSpec hs = new HostSpec(hosts.get(0), getPort(hosts.get(0)),
                loadBalanceProperties.getOriginalProperties().getProperty("localSocketAddress"));
            hspec = new HostSpec[]{hs};
          }
          controlConnection = null;
        }
      }
    }
    return true;
  }

  private static InetAddress getConnectedInetAddress(Connection conn) throws SQLException {
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

  static boolean isRightNodeType(LoadBalanceType loadBalance, String nodeType, byte requestFlags) {
    LOGGER.fine("loadBalance " + loadBalance + ", nodeType: " + nodeType + ", requestFlags: " + requestFlags);
    switch (loadBalance) {
    case ANY:
      return true;
    case ONLY_PRIMARY:
      return nodeType.equalsIgnoreCase("primary");
    case ONLY_RR:
      return nodeType.equalsIgnoreCase("read_replica");
    case PREFER_PRIMARY:
      if (requestFlags == LoadBalanceService.STRICT_PREFERENCE) {
        return nodeType.equalsIgnoreCase("primary");
      } else {
        return nodeType.equalsIgnoreCase("primary") || nodeType.equalsIgnoreCase("read_replica");
      }
    case PREFER_RR:
      if (requestFlags == LoadBalanceService.STRICT_PREFERENCE) {
        return nodeType.equalsIgnoreCase("read_replica");
      } else {
        return nodeType.equalsIgnoreCase("primary") || nodeType.equalsIgnoreCase("read_replica");
      }
    default:
      return false;
    }
  }

  public static class NodeInfo {

    private String host;
    private int port;
    private CloudPlacement placement;
    private String publicIP;
    private String nodeType;
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

    public String getNodeType() {
      return nodeType;
    }

    public void setNodeType(String nodeType) {
      this.nodeType = nodeType;
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
        equal = this.cloud.equalsIgnoreCase(o.cloud)
            && this.region.equalsIgnoreCase(o.region)
            && this.zone.equalsIgnoreCase(o.zone);
      }
      LOGGER.fine("equals returning: " + equal);
      return equal;
    }

    public String toString() {
      return "CloudPlacement: " + cloud + "." + region + "." + zone;
    }
  }

  public enum LoadBalanceType {
    FALSE, ANY, PREFER_PRIMARY, PREFER_RR, ONLY_PRIMARY, ONLY_RR
  }
}
