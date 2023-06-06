package com.yugabyte.ysql;

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

import static com.yugabyte.ysql.ClusterAwareLoadBalancer.DEFAULT_FAILED_HOST_TTL_SECONDS;
import static org.postgresql.Driver.hostSpecs;

public class LoadBalanceManager {

  private static ConcurrentHashMap<String, NodeInfo> clusterInfoMap = new ConcurrentHashMap<>();
  protected static final String GET_SERVERS_QUERY = "select * from yb_servers()";
  protected static final Logger LOGGER = Logger.getLogger(LoadBalanceManager.class.getName());
  private static long lastRefreshTime;
  private static boolean forceRefresh = false;
  private static Boolean useHostColumn = null;

  public static boolean needsRefresh(long refreshInterval) {
    if (forceRefresh) {
      LOGGER.info("forceRefresh is set to true");
      return true;
    }
    long elapsed = (System.currentTimeMillis() - lastRefreshTime) / 1000;
    if (elapsed > refreshInterval) {
      LOGGER.fine("Needs refresh as list of servers may be stale or being fetched for " +
          "the first time");
      return true;
    }
    LOGGER.fine("Refresh not required.");
    return false;
  }

  /**
   * ----> FOR TEST PURPOSE ONLY  <------
   */
  public static synchronized void clearManager() {
    forceRefresh = false;
    clusterInfoMap.clear();
    lastRefreshTime = 0;
    useHostColumn = null;
  }

  public static synchronized boolean refresh(Connection conn, long refreshInterval) throws SQLException {
    if (!needsRefresh(refreshInterval)) {
      return true;
    }
    forceRefresh = false;
    Statement st = conn.createStatement();
    LOGGER.info("Executing query: " + GET_SERVERS_QUERY + " to fetch list of servers");
    ResultSet rs = st.executeQuery(GET_SERVERS_QUERY);
    InetAddress hostConnectedInetAddr = getConnectedInetAddr(conn);

    // todo clear clusterInfoMap first or populate a temp map and then replace it?
    while (rs.next()) {
      String host = rs.getString("host");
      LOGGER.info("Received entry for host " + host);
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
        nodeInfo.placement = new TopologyAwareLoadBalancer.CloudPlacement(cloud, region, zone);
        try {
          nodeInfo.port = Integer.valueOf(port);
        } catch (NumberFormatException nfe) {
          LOGGER.warning("Could not parse port " + port + " for host " + host + ", using 5433 instead.");
          nodeInfo.port = 5433;
        }
        long failedHostTTL = Long.getLong("failed-host-ttl-seconds", DEFAULT_FAILED_HOST_TTL_SECONDS);
        if (nodeInfo.isDown) {
          if (System.currentTimeMillis() - nodeInfo.isDownSince > (failedHostTTL * 1000)) {
            LOGGER.info("Marking " + nodeInfo.host + " as UP since failed-host-ttl has elapsed");
            nodeInfo.isDown = false;
          } else {
            LOGGER.info("Keeping " + nodeInfo.host + " marked as DOWN since failed-host-ttl not elapsed");
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
        if (hostConnectedInetAddr.equals(hostInetAddr)) {
          useHostColumn = Boolean.TRUE;
        } else if (hostConnectedInetAddr.equals(publicHostInetAddr)) {
          useHostColumn = Boolean.FALSE;
        }
      }
    }
    if (useHostColumn != null && !useHostColumn) {
      LOGGER.info("Will be using publicIPs for establishing connections");
      Enumeration<String> hosts = clusterInfoMap.keys();
      while (hosts.hasMoreElements()) {
        NodeInfo info = clusterInfoMap.get(hosts.nextElement());
        clusterInfoMap.put(info.publicIP, info);
        clusterInfoMap.remove(info.host);
      }
    } else if (useHostColumn == null) {
      LOGGER.warning("Unable to identify set of addresses to use for establishing connections");
    }
    lastRefreshTime = System.currentTimeMillis();
    return true;
  }

  public static void markAsFailed(String host) {
    NodeInfo info = clusterInfoMap.get(host);
    if (info == null) return; // unexpected
    synchronized (info) {
      info.isDown = true;
      info.isDownSince = System.currentTimeMillis();
      info.connectionCount = 0;
      LOGGER.info("Marked " + host + " as DOWN");
    }
  }

  public static int getLoad(String host) {
    return clusterInfoMap.get(host).connectionCount;
  }

  public static ArrayList<String> getAllEligibleHosts(LoadBalancer policy) {
    ArrayList<String> list = new ArrayList<>();
    Set<Map.Entry<String, NodeInfo>> set = clusterInfoMap.entrySet();
    for (Map.Entry<String, NodeInfo> e : set) {
      if (policy.isHostEligible(e)) {
        list.add(e.getKey());
      } else {
        LOGGER.info("Skipping " + e + " because it is not eligible.");
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
    LoadBalanceProperties lbProperties = new LoadBalanceProperties(url, properties);
    // Cleanup extra properties used for load balancing?
    if (lbProperties.hasLoadBalance()) {
      Connection conn = getConnection(lbProperties,  user, database);
      if (conn != null) {
        return conn;
      }
      LOGGER.warning("Failed to apply load balance. Trying normal connection");
    } else {
      LOGGER.info("load balance is false");
    }
    return null;
  }

  public static Connection getConnection(LoadBalanceProperties loadBalanceProperties, String user,
      String dbName) {
    LoadBalancer lb = loadBalanceProperties.getAppropriateLoadBalancer();
    Properties props = loadBalanceProperties.getOriginalProperties();
    String url = loadBalanceProperties.getStrippedURL();
    Connection controlConnection = null;
    boolean connectionCreated = false;
    boolean gotException = false;

    if (LoadBalanceManager.needsRefresh(lb.getRefreshListSeconds())) {
      HostSpec[] hspec = hostSpecs(loadBalanceProperties.getOriginalProperties());
      ArrayList<String> hosts = LoadBalanceManager.getAllAvailableHosts(new ArrayList<>());
      while (true) {
        try {
          controlConnection = new PgConnection(hspec, user, dbName, props, url);
          // ((PgConnection)controlConnection).getQueryExecutor().getHostSpec().getHost();
          connectionCreated = true;
          LoadBalanceManager.refresh(controlConnection, lb.getRefreshListSeconds());
          controlConnection.close();
          break;
        } catch (SQLException ex) {
          if (PSQLState.UNDEFINED_FUNCTION.getState().equals(ex.getSQLState())) {
            return null;
          }
          gotException = true;
          if (PSQLState.CONNECTION_UNABLE_TO_CONNECT.getState().equals(ex.getSQLState())) {
            if (hspec.length == 1) {
              LoadBalanceManager.markAsFailed(hspec[0].getHost());
            }
          }
          // todo differentiate between connection creation failure and refresh failure (what can cause refresh failure other than network error?)
          // todo mark hspec's host as failed
          // Retry until servers are available
          for (HostSpec h : hspec) {
            hosts.remove(h.getHost());
          }
          if (hosts.isEmpty()) {
            return null;
          } else {
            // Try the first host in the list (don't have to check least loaded one since it's
            // just for the control connection)
            HostSpec hs = new HostSpec(hosts.get(0), LoadBalanceManager.getPort(hosts.get(0)),
                loadBalanceProperties.getOriginalProperties().getProperty("localSocketAddress"));
            hspec = new HostSpec[]{hs};
          }
        } finally {
          if (gotException && connectionCreated) {
            try {
              controlConnection.close();
            } catch (SQLException throwables) {
              // ignore it was to be closed
            }
          }
        }
      }
    }

    List<String> failedHosts = new ArrayList<>();
    String chosenHost = lb.getLeastLoadedServer(true, failedHosts);
    PgConnection newConnection;
    SQLException firstException = null;
    // refresh can also fail on a particular server so try in loop till
    // options are exhausted
    while (chosenHost != null) {
      try {
        props.setProperty("PGHOST", chosenHost);
        props.setProperty("PGPORT", String.valueOf(LoadBalanceManager.getPort(chosenHost)));
        newConnection = new PgConnection(hostSpecs(props), user, dbName, props, url);
        newConnection.setLoadBalancer(lb);
        LOGGER.info("Created connection to " + chosenHost);
        return newConnection;
      } catch (SQLException ex) {
        // Let the refresh be forced the next time it is tried.
        forceRefresh = true; //  lb.setForRefresh(); // todo why? not needed in new code?
        failedHosts.add(chosenHost);
        LoadBalanceManager.decrementConnectionCount(chosenHost);
        if (PSQLState.CONNECTION_UNABLE_TO_CONNECT.getState().equals(ex.getSQLState())) {
          if (firstException == null) {
            firstException = ex;
          }
          // TODO log exception go to the next one after adding to failed list
          LOGGER.fine("couldn't connect to " + chosenHost + ", adding it to failed host list");
          LoadBalanceManager.markAsFailed(chosenHost);
        } else {
          // Log the exception. Consider other failures as temporary and not as serious as
          // PSQLState.CONNECTION_UNABLE_TO_CONNECT. So for other failures it will be ignored
          // only in this attempt, instead of adding it in the failed host list of the
          // load balancer itself because it won't be tried till the next refresh happens.
          LOGGER.warning("got exception " + ex.getMessage() + " while connecting to " + chosenHost);
          // how will it not be tried in the next iteration? -> Added in failedHosts above
        }
      } catch (Throwable e) {
        LoadBalanceManager.decrementConnectionCount(chosenHost);
        throw e;
      }
      chosenHost = lb.getLeastLoadedServer(false, failedHosts);
    }
    return null;
  }

  public static InetAddress getConnectedInetAddr(Connection conn) throws SQLException {
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
    private TopologyAwareLoadBalancer.CloudPlacement placement;
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

    public TopologyAwareLoadBalancer.CloudPlacement getPlacement() {
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

}
