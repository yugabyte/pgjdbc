package com.yugabyte.ysql;

import static com.yugabyte.ysql.LoadBalanceProperties.DEFAULT_FAILED_HOST_TTL_SECONDS;
import static com.yugabyte.ysql.LoadBalanceProperties.FAILED_HOST_RECONNECT_DELAY_SECS_KEY;
import static org.postgresql.Driver.hostSpecs;

import org.postgresql.jdbc.PgConnection;
import org.postgresql.util.*;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

public class LoadBalanceService {
  static final byte STRICT_PREFERENCE = 0b00000001;
  protected static final String GET_SERVERS_QUERY = "select * from yb_servers()";
  protected static final Logger LOGGER =
      Logger.getLogger("org.postgresql." + LoadBalanceService.class.getName());
  private static boolean forceRefreshOnce = false;
  public static Map<String, ClusterInfo> uuidToClusterInfoMap = new ConcurrentHashMap<>();
  public static Map<LoadBalanceProperties.LoadBalancerKey, String> lbKeyToUuidMap =
      new ConcurrentHashMap<>();

  /**
   * FOR TEST PURPOSE ONLY
   */
  public static void printHostToConnectionMap() {
    System.out.println("Current load on servers");
    System.out.println("-------------------");
    for (Map.Entry<String, ClusterInfo> c : uuidToClusterInfoMap.entrySet()) {
      System.out.println("Cluster uuid: " + c.getKey());
      for (Map.Entry<String, NodeInfo> e : c.getValue().hostToNodeInfoMap.entrySet()) {
        System.out.println(e.getKey() + " - " + e.getValue().connectionCount);
      }
    }
  }

  /**
   * FOR TEST PURPOSE ONLY
   */
  static synchronized void clear() throws SQLException {
    LOGGER.warning("Clearing LoadBalanceService state for testing purposes");
    uuidToClusterInfoMap.clear();
    lbKeyToUuidMap.clear();
    for (Map.Entry<String, ClusterInfo> c : uuidToClusterInfoMap.entrySet()) {
      if (c.getValue().controlConnection != null) {
        c.getValue().controlConnection.close();
        c.getValue().controlConnection = null;
      }
      c.getValue().setUseHostColumn(null);
      for (Map.Entry<LoadBalanceProperties.LoadBalancerKey, LoadBalancer> l :
          c.getValue().lbKeyToLBMap.entrySet()) {
        l.getValue().setLastRefreshTime(0);
      }
    }
    forceRefreshOnce = false;
  }

  static long getLastRefreshTime(LoadBalancer lb) {
    return lb.getLastRefreshTime();
  }

  private static boolean needsRefresh(long refreshInterval, LoadBalancer lb) {
    if (forceRefreshOnce) {
      LOGGER.fine("forceRefreshOnce is set to true");
      return true;
    }
    long elapsed = (System.currentTimeMillis() - lb.getLastRefreshTime()) / 1000;
    if (elapsed >= refreshInterval) {
      LOGGER.fine("Needs refresh as list of servers may be stale or being fetched for "
          + "the first time, refreshInterval: " + refreshInterval);
      return true;
    }
    LOGGER.fine("Refresh not required, refreshInterval: " + refreshInterval);
    return false;
  }

  /**
   * @param conn            Control connection to the cluster
   * @param refreshInterval The interval at which refresh is done
   * @param lb              The load balancer for this connection
   * @return the value of the universe_uuid of the cluster
   * @throws SQLException
   */
  private static synchronized String refresh(Connection conn, long refreshInterval,
      LoadBalancer lb) throws SQLException {
    forceRefreshOnce = false;
    Statement st = conn.createStatement();
    LOGGER.fine("Executing query: " + GET_SERVERS_QUERY + " to fetch list of servers");
    ResultSet rs = st.executeQuery(GET_SERVERS_QUERY);
    InetAddress hostConnectedInetAddress = getConnectedInetAddress(conn);
    ConcurrentHashMap<String, LoadBalanceService.NodeInfo> hostToNodeInfoMap = null;
    boolean publicIPsGivenForAll = true;
    String uuid = null;
    ClusterInfo cluster = null;
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
      try {
        uuid = rs.getString("universe_uuid");
      } catch (PSQLException e) {
        LOGGER.info("Found a version of YugabyteDB which does not send universe_uuid in its "
            + "response of yb_servers() function. Connecting to more than one such clusters is not "
            + "supported.");
        uuid = "default";
      }

      if (cluster == null) {
        if (uuidToClusterInfoMap.containsKey(uuid)) {
          cluster = uuidToClusterInfoMap.get(uuid);
        } else {
          cluster = new ClusterInfo();
          cluster.setControlConnection(conn);
        }
      }
      if (hostToNodeInfoMap == null) {
        hostToNodeInfoMap = cluster.getHostToNodeInfoMap() != null ?
            cluster.getHostToNodeInfoMap() : new ConcurrentHashMap<>();
      }
      NodeInfo nodeInfo = hostToNodeInfoMap.containsKey(host) ? hostToNodeInfoMap.get(host) :
          new NodeInfo();
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
          LOGGER.warning("Could not parse port " + port + " for host " + host + ", using 5433 "
              + "instead.");
          nodeInfo.port = 5433;
        }
        long failedHostTTL = Long.getLong(FAILED_HOST_RECONNECT_DELAY_SECS_KEY,
            DEFAULT_FAILED_HOST_TTL_SECONDS);
        if (nodeInfo.isDown) {
          if (System.currentTimeMillis() - nodeInfo.isDownSince > (failedHostTTL * 1000)) {
            LOGGER.fine("Marking " + nodeInfo.host + " as UP since failed-host-reconnect-delay"
                + "-secs (" + failedHostTTL + "s) has elapsed");
            nodeInfo.isDown = false;
          } else {
            LOGGER.fine("Keeping " + nodeInfo.host + " as DOWN since failed-host-reconnect-delay"
                + "-secs (" + failedHostTTL + "s) has not elapsed");
          }
        }
      }
      hostToNodeInfoMap.putIfAbsent(host, nodeInfo);

      InetAddress hostInetAddr;
      InetAddress publicHostInetAddr;
      try {
        hostInetAddr = InetAddress.getByName(host);
      } catch (UnknownHostException e) {
        LOGGER.fine("Failed to get host '" + host + "' by name");
        hostInetAddr = null;
      }
      try {
        publicHostInetAddr = !publicHost.isEmpty()
            ? InetAddress.getByName(publicHost) : null;
      } catch (UnknownHostException e) {
        LOGGER.fine("Failed to get public_ip '" + publicHost + "' by name");
        publicHostInetAddr = null;
      }
      if (cluster.getUseHostColumn() == null) {
        if (hostConnectedInetAddress.equals(hostInetAddr)) {
          cluster.setUseHostColumn(Boolean.TRUE);
        } else if (hostConnectedInetAddress.equals(publicHostInetAddr)) {
          cluster.setUseHostColumn(Boolean.FALSE);
        } else if (hostInetAddr != null && hostInetAddr.equals(publicHostInetAddr)) {
          // Both host and public_ip are same
          cluster.setUseHostColumn(Boolean.TRUE);
        }
      }
    }
    
    if (lb.getUuid() == null) {
      if (uuidToClusterInfoMap.containsKey(uuid)) {
        conn.close();
      }
      lb.setUuid(uuid);
    }
    if ((cluster.getUseHostColumn() != null && !cluster.getUseHostColumn()) || (cluster.getUseHostColumn() == null && publicIPsGivenForAll)) {
      LOGGER.info("Will be using publicIPs for establishing connections");
      ArrayList<String> hosts = Collections.list(hostToNodeInfoMap.keys());
      for (String host : hosts) {
        NodeInfo info = hostToNodeInfoMap.get(host);
        hostToNodeInfoMap.remove(info.host);
        hostToNodeInfoMap.put(info.publicIP, info);
      }
    } else if (cluster.getUseHostColumn() == null) {
      LOGGER.warning("Unable to identify set of addresses to use for establishing connections. "
          + "Using private addresses.");
    }
    lb.setLastRefreshTime(System.currentTimeMillis());
    if (cluster != null) {
      cluster.setHostToNodeInfoMap(hostToNodeInfoMap);
    }
    uuidToClusterInfoMap.putIfAbsent(uuid, cluster);
    return uuid;
  }

  private static void markAsFailed(String uuid, String host) {
    if (uuid == null) {
      LOGGER.fine("Could not mark down host: " + host + ". UUID not present");
      return;
    }
    NodeInfo info = uuidToClusterInfoMap.get(uuid).getHostToNodeInfoMap().get(host);
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

  public static int getLoad(String host) {
    int load = 0;
    for (String uuid : uuidToClusterInfoMap.keySet()) {
      if (uuidToClusterInfoMap.get(uuid).getHostToNodeInfoMap().containsKey(host)) {
        return getLoad(uuid, host);
      }
    }
    return load;
  }

  static int getLoad(String uuid, String host) {
    if (uuid == null) {
      LOGGER.fine("Could not get the load on host:" + host + ". UUID not present");
      return -1;
    }
    NodeInfo info = uuidToClusterInfoMap.get(uuid).getHostToNodeInfoMap().get(host);
    return info == null ? 0 : info.connectionCount;
  }

  static ArrayList<String> getAllEligibleHosts(String uuid, LoadBalancer policy,
      Byte requestFlags) {
    ArrayList<String> list = new ArrayList<>();
    if (uuid == null) {
      LOGGER.fine("Could not retrieve the eligible hosts for the loadbalancer. UUID not present");
      return list;
    }
    Set<Map.Entry<String, NodeInfo>> set =
        uuidToClusterInfoMap.get(uuid).getHostToNodeInfoMap().entrySet();
    for (Map.Entry<String, NodeInfo> e : set) {
      if (policy.isHostEligible(e, requestFlags)) {
        list.add(e.getKey());
      } else {
        LOGGER.finest("Skipping " + e + " because it is not eligible.");
      }
    }
    return list;
  }

  private static ArrayList<String> getAllAvailableHosts(String uuid, ArrayList<String> attempted) {
    ArrayList<String> list = new ArrayList<>();
    if (uuid == null) {
      return list;
    }
    ConcurrentHashMap<String, NodeInfo> hostToNodeInfoMap =
        uuidToClusterInfoMap.get(uuid).getHostToNodeInfoMap();
    Enumeration<String> hosts = hostToNodeInfoMap.keys();
    while (hosts.hasMoreElements()) {
      String h = hosts.nextElement();
      if (!attempted.contains(h) && !hostToNodeInfoMap.get(h).isDown) {
        list.add(h);
      }
    }
    return list;
  }

  private static int getPort(String uuid, String host) {
    if (uuid == null) {
      LOGGER.fine("Could not get the port for host:" + host + ". UUID not present");
      return -1;
    }
    NodeInfo info = uuidToClusterInfoMap.get(uuid).getHostToNodeInfoMap().get(host);
    return info != null ? info.port : 5433;
  }

  static boolean incrementConnectionCount(String uuid, String host) {
    if (uuid == null) {
      LOGGER.fine("Could not increment connection count for host:" + host + ". UUID not present");
      return false;
    }
    NodeInfo info = uuidToClusterInfoMap.get(uuid).getHostToNodeInfoMap().get(host);
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
    for (String uuid : uuidToClusterInfoMap.keySet()) {
      if (decrementConnectionCount(uuid, host)) {
        return true;
      }
    }
    return false;
  }

  public static boolean decrementConnectionCount(String uuid, String host) {
    if (uuid == null) {
      LOGGER.fine("Could not decrement connection count for host:" + host + ". UUID not present");
      return false;
    }
    NodeInfo info = uuidToClusterInfoMap.get(uuid).getHostToNodeInfoMap().get(host);
    if (info != null) {
      synchronized (info) {
        info.connectionCount -= 1;
        LOGGER.fine("Decremented connection count for " + host + " in uuid: " + uuid + " by one: "
            + info.connectionCount);
        if (info.connectionCount < 0) {
          info.connectionCount = 0;
          LOGGER.fine("Resetting connection count for " + host + " to zero in uuid: " + uuid);
        }
        return true;
      }
    }
    return false;
  }

  private static LoadBalancer getLB(LoadBalanceProperties.LoadBalancerKey key) {
    LoadBalancer lb = null;
    if (lbKeyToUuidMap.get(key) != null) {
      lb = uuidToClusterInfoMap.get(lbKeyToUuidMap.get(key)).getLbKeyToLBMap().get(key);
    }
    if (lb == null) {
      // 1. process the url and properties and create a new LoadBalancer instance -- refer to
      // LBProperties.processURLAndProperties()
      LoadBalanceProperties.ProcessedProperties processedProperties =
          LoadBalanceProperties.processURLAndProperties(key.getUrl(), key.getProperties());
      if (processedProperties.getPlacements() == null) {
        lb = new ClusterAwareLoadBalancer(processedProperties);
      } else {
        lb = new TopologyAwareLoadBalancer(processedProperties);
      }
      // 2. create control connection and fetch yb_servers() -- refer to LBProperties.checkAndRefresh()
      String uuid = checkAndRefresh(key, lb);
      if (uuid == null) {
        LOGGER.fine("Attempt to refresh info from yb_servers() failed");
        return null;
      }

      // 3. Set the loadbalancer against the lbkey in the ClusterInfo
      ClusterInfo cluster = uuidToClusterInfoMap.get(uuid);
      cluster.getLbKeyToLBMap().put(key, lb);

      // 4. Put the lbkey in lbKeyToUuidMap if absent
      lbKeyToUuidMap.putIfAbsent(key, uuid);
    }
    return lb;
  }

  public static Connection getConnection(LoadBalanceProperties.LoadBalancerKey key,
      ArrayList<String> timedOutHosts) {
    // Cleanup extra properties used for load balancing?
    if (LoadBalanceProperties.isLoadBalanceEnabled(key)) {
      Connection conn = getConnection(key, (Properties) key.getProperties().clone(), timedOutHosts);
      if (conn != null) {
        return conn;
      }
      LOGGER.warning("Failed to apply load balance. Trying normal connection");
    }
    return null;
  }

  private static Connection getConnection(LoadBalanceProperties.LoadBalancerKey key,
      Properties props, ArrayList<String> timedOutHosts) {
    LoadBalancer lb = getLB(key);
    if (lb == null) {
      LOGGER.fine("No loadbalancer found for lbkey: " + key);
      return null;
    }
    String uuid = lbKeyToUuidMap.get(key);
    String url = key.getUrl();

    if (checkAndRefresh(key, lb) == null) {
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
        props.setProperty("PGPORT", String.valueOf(getPort(uuid, chosenHost)));
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
        decrementConnectionCount(uuid, chosenHost);
        if (PSQLState.CONNECTION_UNABLE_TO_CONNECT.getState().equals(ex.getSQLState())) {
          if (firstException == null) {
            firstException = ex;
          }
          LOGGER.fine("couldn't connect to " + chosenHost + ", adding it to failed host list");
          markAsFailed(uuid, chosenHost);
        } else {
          // Log the exception. Consider other failures as temporary and not as serious as
          // PSQLState.CONNECTION_UNABLE_TO_CONNECT. So for other failures it will be ignored
          // only in this attempt, instead of adding it in the failed host list of the
          // load balancer itself because it won't be tried till the next refresh happens.
          LOGGER.warning("got exception " + ex.getMessage() + " while connecting to " + chosenHost);
        }
      } catch (Throwable e) {
        LOGGER.fine("Received Throwable: " + e);
        decrementConnectionCount(uuid, chosenHost);
        throw e;
      }
      chosenHost = lb.getLeastLoadedServer(false, failedHosts, timedOutHosts);
    }
    LOGGER.fine("No host could be chosen");
    return null;
  }

  /**
   * @param key
   * @param lb  LoadBalancer instance
   * @return universe_uuid if the refresh was not required or if it was successful.
   */
  private static synchronized String checkAndRefresh(LoadBalanceProperties.LoadBalancerKey key,
      LoadBalancer lb) {
    String uuid = null;
    if (lb != null) {
      uuid = lb.getUuid();
    }
    if (needsRefresh(lb.getRefreshListSeconds(), lb)) {
      String url = key.getUrl();
      Properties properties = new Properties(key.getProperties());
      properties.setProperty("socketTimeout", "15");
      HostSpec[] hspec = hostSpecs(properties);
      Connection controlConnection = null;
      ArrayList<String> hosts = getAllAvailableHosts(lbKeyToUuidMap.get(key), new ArrayList<>());
      while (true) {
        boolean refreshFailed = false;
        try {
          if (uuid != null) {
            controlConnection = uuidToClusterInfoMap.get(uuid).getControlConnection();
          }
          if (controlConnection == null){
            controlConnection = new PgConnection(hspec, properties, url);
            if (uuid != null) {
              uuidToClusterInfoMap.get(uuid).setControlConnection(controlConnection);
            }
          }
          try {
            uuid = refresh(controlConnection, lb.getRefreshListSeconds(), lb);
            break;
          } catch (SQLException e) {
            // May fail with "terminating connection due to unexpected postmaster exit", 57P01
            refreshFailed = true;
            throw e;
          }
        } catch (SQLException ex) {
          if (refreshFailed) {
            LOGGER.fine("Exception while refreshing: " + ex + ", " + ex.getSQLState());
            String failed =
                ((PgConnection) controlConnection).getQueryExecutor().getHostSpec().getHost();
            markAsFailed(uuid, failed);
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
            return null;
          }
          // Retry until servers are available
          if (hosts.isEmpty()) {
            LOGGER.fine("Failed to establish control connection to available servers");
            return null;
          } else if (!refreshFailed) {
            // Try the first host in the list (don't have to check least loaded one since it's
            // just for the control connection)
            HostSpec hs = new HostSpec(hosts.get(0), getPort(uuid, hosts.get(0)),
                key.getProperties().getProperty("localSocketAddress"));
            hspec = new HostSpec[]{hs};
          }
          if (uuid != null) {
            uuidToClusterInfoMap.get(uuid).setControlConnection(null);
          }
          controlConnection = null;
        }
      }
    }
    return uuid;
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

  public static class ClusterInfo {
    private Connection controlConnection;
    private ConcurrentHashMap<String, LoadBalanceService.NodeInfo> hostToNodeInfoMap;
    private Map<LoadBalanceProperties.LoadBalancerKey, LoadBalancer> lbKeyToLBMap =
        new ConcurrentHashMap<>();
    private Boolean useHostColumn = null;

    public Connection getControlConnection() {
      return controlConnection;
    }

    public void setControlConnection(Connection controlConnection) {
      this.controlConnection = controlConnection;
    }

    public ConcurrentHashMap<String, NodeInfo> getHostToNodeInfoMap() {
      return hostToNodeInfoMap;
    }

    public void setHostToNodeInfoMap(ConcurrentHashMap<String, NodeInfo> hostToNodeInfoMap) {
      this.hostToNodeInfoMap = hostToNodeInfoMap;
    }

    public Map<LoadBalanceProperties.LoadBalancerKey, LoadBalancer> getLbKeyToLBMap() {
      return lbKeyToLBMap;
    }

    public Boolean getUseHostColumn() {
      return useHostColumn;
    }

    public void setUseHostColumn(Boolean useHostColumn) {
      this.useHostColumn = useHostColumn;
    }

  }
}
