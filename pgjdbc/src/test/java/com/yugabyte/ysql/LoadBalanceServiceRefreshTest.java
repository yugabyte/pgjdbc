package com.yugabyte.ysql;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.postgresql.jdbc.PgConnection;
import org.postgresql.util.PSQLException;
import org.postgresql.util.PSQLState;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

class LoadBalanceServiceRefreshTest {

  private PgConnection mockConn;
  private Statement mockStmt;
  private ResultSet mockRs;
  private TestLoadBalancer lb;

  @BeforeEach
  void setUp() throws SQLException {
    LoadBalanceService.uuidToClusterInfoMap.clear();
    LoadBalanceService.lbKeyToUuidMap.clear();

    mockConn = mock(PgConnection.class, RETURNS_DEEP_STUBS);
    when(mockConn.getQueryExecutor().getHostSpec().getHost()).thenReturn("127.0.0.1");

    mockStmt = mock(Statement.class);
    mockRs = mock(ResultSet.class);
    when(mockConn.createStatement()).thenReturn(mockStmt);
    when(mockStmt.executeQuery(anyString())).thenReturn(mockRs);

    lb = new TestLoadBalancer();
  }

  @AfterEach
  void tearDown() throws SQLException {
    LoadBalanceService.clear();
  }

  @Test
  void emptyResultSetReturnsEarlyWithoutNPE() throws SQLException {
    when(mockRs.next()).thenReturn(false);
    lb.setUuid("existing-uuid");

    String result = assertDoesNotThrow(
        () -> LoadBalanceService.refresh(mockConn, 300, lb));
    assertEquals("existing-uuid", result);
    assertTrue(LoadBalanceService.uuidToClusterInfoMap.isEmpty());
  }

  @Test
  void emptyResultSetWithNullUuidReturnsNull() throws SQLException {
    when(mockRs.next()).thenReturn(false);

    String result = assertDoesNotThrow(
        () -> LoadBalanceService.refresh(mockConn, 300, lb));
    assertNull(result);
  }

  @Test
  void removedHostsAreDetected() throws SQLException {
    String uuid = "test-uuid";
    ConcurrentHashMap<String, LoadBalanceService.NodeInfo> hostMap = new ConcurrentHashMap<>();
    addNodeInfo(hostMap, "127.0.0.1", "10.0.0.1", 5433, "aws", "us-west", "us-west-2a");
    addNodeInfo(hostMap, "127.0.0.2", "10.0.0.2", 5433, "aws", "us-west", "us-west-2b");
    addNodeInfo(hostMap, "127.0.0.3", "10.0.0.3", 5433, "aws", "us-west", "us-west-2c");

    LoadBalanceService.ClusterInfo cluster = new LoadBalanceService.ClusterInfo();
    cluster.setHostToNodeInfoMap(hostMap);
    cluster.setUseHostColumn(Boolean.TRUE);
    cluster.setControlConnection(mockConn);
    LoadBalanceService.uuidToClusterInfoMap.put(uuid, cluster);

    setupResultSetRows(
        row("127.0.0.1", "10.0.0.1", "5433", "aws", "us-west", "us-west-2a", "primary", uuid),
        row("127.0.0.2", "10.0.0.2", "5433", "aws", "us-west", "us-west-2b", "primary", uuid)
    );

    lb.setUuid(uuid);
    LoadBalanceService.refresh(mockConn, 300, lb);

    ConcurrentHashMap<String, LoadBalanceService.NodeInfo> updatedMap =
        LoadBalanceService.uuidToClusterInfoMap.get(uuid).getHostToNodeInfoMap();
    assertTrue(updatedMap.containsKey("127.0.0.1"));
    assertTrue(updatedMap.containsKey("127.0.0.2"));
    // 127.0.0.3 is absent because it's not returned by yb_servers() function
    assertFalse(updatedMap.containsKey("127.0.0.3"));
  }

  @Test
  void longDownRemovedHostsAreEvicted() throws SQLException {
    String uuid = "test-uuid";
    ConcurrentHashMap<String, LoadBalanceService.NodeInfo> hostMap = new ConcurrentHashMap<>();
    addNodeInfo(hostMap, "127.0.0.1", "10.0.0.1", 5433, "aws", "us-west", "us-west-2a");
    addNodeInfo(hostMap, "127.0.0.2", "10.0.0.2", 5433, "aws", "us-west", "us-west-2b");

    LoadBalanceService.NodeInfo downNode =
        addNodeInfo(hostMap, "127.0.0.3", "10.0.0.3", 5433, "aws", "us-west", "us-west-2c");
    markAsDown(downNode, System.currentTimeMillis() - 400_000);

    LoadBalanceService.ClusterInfo cluster = new LoadBalanceService.ClusterInfo();
    cluster.setHostToNodeInfoMap(hostMap);
    cluster.setUseHostColumn(Boolean.TRUE);
    cluster.setControlConnection(mockConn);
    LoadBalanceService.uuidToClusterInfoMap.put(uuid, cluster);

    setupResultSetRows(
        row("127.0.0.1", "10.0.0.1", "5433", "aws", "us-west", "us-west-2a", "primary", uuid),
        row("127.0.0.2", "10.0.0.2", "5433", "aws", "us-west", "us-west-2b", "primary", uuid)
    );

    lb.setUuid(uuid);
    LoadBalanceService.refresh(mockConn, 300, lb);

    ConcurrentHashMap<String, LoadBalanceService.NodeInfo> updatedMap =
        LoadBalanceService.uuidToClusterInfoMap.get(uuid).getHostToNodeInfoMap();
    assertTrue(updatedMap.containsKey("127.0.0.1"));
    assertTrue(updatedMap.containsKey("127.0.0.2"));
    assertFalse(updatedMap.containsKey("127.0.0.3"),
        "Host down for >300s and absent from yb_servers() should be evicted");
  }

  // SKIP, because we remove stale host immediately
  void recentlyDownRemovedHostsAreNotEvicted() throws SQLException {
    String uuid = "test-uuid";
    ConcurrentHashMap<String, LoadBalanceService.NodeInfo> hostMap = new ConcurrentHashMap<>();
    addNodeInfo(hostMap, "127.0.0.1", "10.0.0.1", 5433, "aws", "us-west", "us-west-2a");

    LoadBalanceService.NodeInfo downNode =
        addNodeInfo(hostMap, "127.0.0.2", "10.0.0.2", 5433, "aws", "us-west", "us-west-2b");
    markAsDown(downNode, System.currentTimeMillis() - 60_000);

    LoadBalanceService.ClusterInfo cluster = new LoadBalanceService.ClusterInfo();
    cluster.setHostToNodeInfoMap(hostMap);
    cluster.setUseHostColumn(Boolean.TRUE);
    cluster.setControlConnection(mockConn);
    LoadBalanceService.uuidToClusterInfoMap.put(uuid, cluster);

    setupResultSetRows(
        row("127.0.0.1", "10.0.0.1", "5433", "aws", "us-west", "us-west-2a", "primary", uuid)
    );

    lb.setUuid(uuid);
    LoadBalanceService.refresh(mockConn, 300, lb);

    ConcurrentHashMap<String, LoadBalanceService.NodeInfo> updatedMap =
        LoadBalanceService.uuidToClusterInfoMap.get(uuid).getHostToNodeInfoMap();
    assertTrue(updatedMap.containsKey("127.0.0.2"),
        "Host down for <300s should NOT be evicted even if absent from yb_servers()");
  }

  /**
   * hostToNodeInfoMap is case-sensitive, so a host returned under a different spelling is a
   * different key. It replaces the old entry rather than being matched to it: comparing
   * case-insensitively while keying case-sensitively would insert the new spelling and leave
   * the old one looking "still present", so it could never be evicted.
   */
  @Test
  void hostReturnedWithDifferentCaseReplacesStaleEntry() throws SQLException {
    String uuid = "test-uuid";
    ConcurrentHashMap<String, LoadBalanceService.NodeInfo> hostMap = new ConcurrentHashMap<>();
    addNodeInfo(hostMap, "HostA", "10.0.0.1", 5433, "aws", "us-west", "us-west-2a");

    LoadBalanceService.NodeInfo downNode =
        addNodeInfo(hostMap, "HostB", "10.0.0.2", 5433, "aws", "us-west", "us-west-2b");
    markAsDown(downNode, System.currentTimeMillis() - 400_000);

    LoadBalanceService.ClusterInfo cluster = new LoadBalanceService.ClusterInfo();
    cluster.setHostToNodeInfoMap(hostMap);
    cluster.setUseHostColumn(Boolean.TRUE);
    cluster.setControlConnection(mockConn);
    LoadBalanceService.uuidToClusterInfoMap.put(uuid, cluster);

    // yb_servers() returns "hosta" (lowercase); "HostB" is gone from the cluster entirely.
    setupResultSetRows(
        row("hosta", "10.0.0.1", "5433", "aws", "us-west", "us-west-2a", "primary", uuid)
    );

    lb.setUuid(uuid);
    LoadBalanceService.refresh(mockConn, 300, lb);

    ConcurrentHashMap<String, LoadBalanceService.NodeInfo> updatedMap =
        LoadBalanceService.uuidToClusterInfoMap.get(uuid).getHostToNodeInfoMap();
    assertTrue(updatedMap.containsKey("hosta"), "the spelling yb_servers() returned is kept");
    assertFalse(updatedMap.containsKey("HostA"),
        "the stale spelling must be evicted, not left behind as a duplicate of the same node");
    assertFalse(updatedMap.containsKey("HostB"), "a host no longer returned must be evicted");
    assertEquals(1, updatedMap.size(), "one entry per node returned by yb_servers()");
  }

  /**
   * Public-IP cluster: yb_servers() reports the private address in "host" and the routable
   * address in "public_ip", so the previous refresh left the map keyed by public_ip. A refresh
   * that still reports the node must not reset its connection count.
   */
  @Test
  void publicIpKeyedMapRetainsConnectionCountAcrossRefresh() throws SQLException {
    String uuid = "test-uuid";
    seedPublicIpKeyedCluster(uuid);

    LoadBalanceService.refresh(mockConn, 300, lb);

    ConcurrentHashMap<String, LoadBalanceService.NodeInfo> updatedMap =
        LoadBalanceService.uuidToClusterInfoMap.get(uuid).getHostToNodeInfoMap();
    assertEquals(2, updatedMap.size(), "map should hold one entry per node, keyed by public_ip");
    assertTrue(updatedMap.containsKey("node-a.invalid"));
    assertEquals(5, updatedMap.get("node-a.invalid").getConnectionCount(),
        "connection count must survive a refresh that still reports the node");
  }

  /**
   * Same setup, for the DOWN state: a node marked down stays down until
   * failed-host-reconnect-delay-secs elapses, otherwise the balancer immediately re-picks a node
   * it just failed to reach.
   */
  @Test
  void publicIpKeyedMapRetainsDownStateAcrossRefresh() throws SQLException {
    String uuid = "test-uuid";
    seedPublicIpKeyedCluster(uuid);

    LoadBalanceService.refresh(mockConn, 300, lb);

    ConcurrentHashMap<String, LoadBalanceService.NodeInfo> updatedMap =
        LoadBalanceService.uuidToClusterInfoMap.get(uuid).getHostToNodeInfoMap();
    assertTrue(updatedMap.containsKey("node-b.invalid"));
    assertTrue(updatedMap.get("node-b.invalid").isDown(),
        "a host marked DOWN must stay DOWN until failed-host-reconnect-delay-secs elapses");
  }

  /**
   * The driver could not decide which address set to use: the control connection is to an
   * endpoint that is neither the node's host nor its public_ip (a k8s service or load balancer).
   * Every node gives a public_ip and every one resolves, so public addresses are used and node
   * state survives the refresh.
   */
  @Test
  void undeterminedHostColumnWithResolvablePublicIpsKeepsPublicIpKeys() throws SQLException {
    String uuid = "test-uuid";
    seedPublicIpKeyedCluster(uuid, "10.1.0.1", "10.1.0.2");
    LoadBalanceService.uuidToClusterInfoMap.get(uuid).setUseHostColumn(null);

    LoadBalanceService.refresh(mockConn, 300, lb);

    LoadBalanceService.ClusterInfo cluster = LoadBalanceService.uuidToClusterInfoMap.get(uuid);
    ConcurrentHashMap<String, LoadBalanceService.NodeInfo> updatedMap =
        cluster.getHostToNodeInfoMap();
    assertTrue(cluster.isKeyedByPublicIp());
    assertTrue(updatedMap.containsKey("10.1.0.1"), "map should stay keyed by public_ip");
    assertEquals(5, updatedMap.get("10.1.0.1").getConnectionCount(),
        "connection count must survive a refresh that still reports the node");
  }

  /**
   * Same, except the public_ip values are set but do not resolve (k8s-internal names seen from
   * outside the cluster). Guessing public addresses there hands the balancer targets it cannot
   * connect to, so the map falls back to host addresses -- carrying node state with it.
   */
  @Test
  void undeterminedHostColumnWithUnresolvablePublicIpsFallsBackToHost() throws SQLException {
    String uuid = "test-uuid";
    seedPublicIpKeyedCluster(uuid);
    LoadBalanceService.uuidToClusterInfoMap.get(uuid).setUseHostColumn(null);

    LoadBalanceService.refresh(mockConn, 300, lb);

    LoadBalanceService.ClusterInfo cluster = LoadBalanceService.uuidToClusterInfoMap.get(uuid);
    ConcurrentHashMap<String, LoadBalanceService.NodeInfo> updatedMap =
        cluster.getHostToNodeInfoMap();
    assertFalse(cluster.isKeyedByPublicIp());
    assertTrue(updatedMap.containsKey("10.0.0.1"), "map should fall back to host addresses");
    assertFalse(updatedMap.containsKey("node-a.invalid"),
        "unresolvable public_ip must not be left as a key");
    assertEquals(2, updatedMap.size(), "one entry per node");
    assertEquals(5, updatedMap.get("10.0.0.1").getConnectionCount(),
        "connection count must survive the fallback re-key");
  }

  @Test
  void missingUniverseUuidFallsBackToDefault() throws SQLException {
    when(mockRs.next()).thenReturn(true, false);
    when(mockRs.getString("host")).thenReturn("127.0.0.1");
    when(mockRs.getString("public_ip")).thenReturn("");
    when(mockRs.getString("port")).thenReturn("5433");
    when(mockRs.getString("cloud")).thenReturn("aws");
    when(mockRs.getString("region")).thenReturn("us-west");
    when(mockRs.getString("zone")).thenReturn("us-west-2a");
    when(mockRs.getString("node_type")).thenReturn("primary");
    when(mockRs.getString("universe_uuid")).thenThrow(
        new PSQLException("column not found", PSQLState.UNDEFINED_COLUMN));

    String result = LoadBalanceService.refresh(mockConn, 300, lb);
    assertEquals("default", result);
    assertTrue(LoadBalanceService.uuidToClusterInfoMap.containsKey("default"));
  }

  // ---- helpers ----

  private void setupResultSetRows(String[]... rows) throws SQLException {
    if (rows.length == 0) {
      when(mockRs.next()).thenReturn(false);
      return;
    }
    Boolean[] nextReturns = new Boolean[rows.length + 1];
    for (int i = 0; i < rows.length; i++) {
      nextReturns[i] = true;
    }
    nextReturns[rows.length] = false;
    when(mockRs.next()).thenReturn(nextReturns[0],
        java.util.Arrays.copyOfRange(nextReturns, 1, nextReturns.length));

    String[] hosts = new String[rows.length];
    String[] publicIps = new String[rows.length];
    String[] ports = new String[rows.length];
    String[] clouds = new String[rows.length];
    String[] regions = new String[rows.length];
    String[] zones = new String[rows.length];
    String[] nodeTypes = new String[rows.length];
    String[] uuids = new String[rows.length];
    for (int i = 0; i < rows.length; i++) {
      hosts[i] = rows[i][0];
      publicIps[i] = rows[i][1];
      ports[i] = rows[i][2];
      clouds[i] = rows[i][3];
      regions[i] = rows[i][4];
      zones[i] = rows[i][5];
      nodeTypes[i] = rows[i][6];
      uuids[i] = rows[i][7];
    }
    setupGetString("host", hosts);
    setupGetString("public_ip", publicIps);
    setupGetString("port", ports);
    setupGetString("cloud", clouds);
    setupGetString("region", regions);
    setupGetString("zone", zones);
    setupGetString("node_type", nodeTypes);
    setupGetString("universe_uuid", uuids);
  }

  private void setupGetString(String column, String[] values) throws SQLException {
    if (values.length == 1) {
      when(mockRs.getString(column)).thenReturn(values[0]);
    } else {
      when(mockRs.getString(column)).thenReturn(values[0],
          java.util.Arrays.copyOfRange(values, 1, values.length));
    }
  }

  private static String[] row(String host, String publicIp, String port, String cloud,
      String region, String zone, String nodeType, String uuid) {
    return new String[]{host, publicIp, port, cloud, region, zone, nodeType, uuid};
  }

  private static LoadBalanceService.NodeInfo addNodeInfo(
      ConcurrentHashMap<String, LoadBalanceService.NodeInfo> map,
      String host, String publicIp, int port, String cloud, String region, String zone) {
    LoadBalanceService.NodeInfo info = new LoadBalanceService.NodeInfo();
    try {
      java.lang.reflect.Field hostField = LoadBalanceService.NodeInfo.class.getDeclaredField("host");
      hostField.setAccessible(true);
      hostField.set(info, host);
      java.lang.reflect.Field publicIPField = LoadBalanceService.NodeInfo.class.getDeclaredField("publicIP");
      publicIPField.setAccessible(true);
      publicIPField.set(info, publicIp);
      java.lang.reflect.Field portField = LoadBalanceService.NodeInfo.class.getDeclaredField("port");
      portField.setAccessible(true);
      portField.setInt(info, port);
      java.lang.reflect.Field placementField = LoadBalanceService.NodeInfo.class.getDeclaredField("placement");
      placementField.setAccessible(true);
      placementField.set(info, new LoadBalanceService.CloudPlacement(cloud, region, zone));
      java.lang.reflect.Field nodeTypeField = LoadBalanceService.NodeInfo.class.getDeclaredField("nodeType");
      nodeTypeField.setAccessible(true);
      nodeTypeField.set(info, "primary");
    } catch (ReflectiveOperationException e) {
      throw new RuntimeException(e);
    }
    map.put(host, info);
    return info;
  }

  private static void markAsDown(LoadBalanceService.NodeInfo info, long downSince) {
    try {
      java.lang.reflect.Field isDownField = LoadBalanceService.NodeInfo.class.getDeclaredField("isDown");
      isDownField.setAccessible(true);
      isDownField.setBoolean(info, true);
      java.lang.reflect.Field isDownSinceField = LoadBalanceService.NodeInfo.class.getDeclaredField("isDownSince");
      isDownSinceField.setAccessible(true);
      isDownSinceField.setLong(info, downSince);
    } catch (ReflectiveOperationException e) {
      throw new RuntimeException(e);
    }
  }

  /**
   * Seeds a two-node cluster in the state a previous refresh leaves behind on a public-IP
   * cluster: map keyed by public_ip, useHostColumn FALSE. Node A carries 5 connections, node B
   * was just marked DOWN. yb_servers() then reports both nodes as still present.
   */
  private void seedPublicIpKeyedCluster(String uuid) throws SQLException {
    seedPublicIpKeyedCluster(uuid, "node-a.invalid", "node-b.invalid");
  }

  private void seedPublicIpKeyedCluster(String uuid, String publicIpA, String publicIpB)
      throws SQLException {
    ConcurrentHashMap<String, LoadBalanceService.NodeInfo> hostMap = new ConcurrentHashMap<>();
    LoadBalanceService.NodeInfo nodeA =
        addNodeInfo(hostMap, "10.0.0.1", publicIpA, 5433, "aws", "us-west", "us-west-2a");
    LoadBalanceService.NodeInfo nodeB =
        addNodeInfo(hostMap, "10.0.0.2", publicIpB, 5433, "aws", "us-west", "us-west-2b");
    keyByPublicIp(hostMap, nodeA);
    keyByPublicIp(hostMap, nodeB);

    setConnectionCount(nodeA, 5);
    markAsDown(nodeB, System.currentTimeMillis());

    LoadBalanceService.ClusterInfo cluster = new LoadBalanceService.ClusterInfo();
    cluster.setHostToNodeInfoMap(hostMap);
    cluster.setUseHostColumn(Boolean.FALSE);
    // The previous refresh re-keyed the map by public_ip and recorded that on the cluster.
    cluster.setKeyedByPublicIp(true);
    cluster.setControlConnection(mockConn);
    LoadBalanceService.uuidToClusterInfoMap.put(uuid, cluster);

    setupResultSetRows(
        row("10.0.0.1", publicIpA, "5433", "aws", "us-west", "us-west-2a", "primary", uuid),
        row("10.0.0.2", publicIpB, "5433", "aws", "us-west", "us-west-2b", "primary", uuid)
    );
    lb.setUuid(uuid);
  }

  /** Re-keys an entry by its public_ip, as the tail of refresh() does for public-IP clusters. */
  private static void keyByPublicIp(ConcurrentHashMap<String, LoadBalanceService.NodeInfo> map,
      LoadBalanceService.NodeInfo info) {
    map.remove(info.getHost());
    map.put(info.getPublicIP(), info);
  }

  private static void setConnectionCount(LoadBalanceService.NodeInfo info, int count) {
    try {
      java.lang.reflect.Field field =
          LoadBalanceService.NodeInfo.class.getDeclaredField("connectionCount");
      field.setAccessible(true);
      field.setInt(info, count);
    } catch (ReflectiveOperationException e) {
      throw new RuntimeException(e);
    }
  }

  private static class TestLoadBalancer implements LoadBalancer {
    private String uuid;
    private long lastRefreshTime;

    @Override
    public boolean isHostEligible(java.util.Map.Entry<String, LoadBalanceService.NodeInfo> e,
        Byte requestFlags) {
      return true;
    }

    @Override
    public String getLeastLoadedServer(boolean newRequest, List<String> failedHosts,
        ArrayList<String> timedOutHosts) {
      return null;
    }

    @Override
    public int getRefreshListSeconds() {
      return 300;
    }

    @Override
    public void setUuid(String uuid) {
      this.uuid = uuid;
    }

    @Override
    public String getUuid() {
      return uuid;
    }

    @Override
    public long getLastRefreshTime() {
      return lastRefreshTime;
    }

    @Override
    public void setLastRefreshTime(long lastRefreshTime) {
      this.lastRefreshTime = lastRefreshTime;
    }
  }
}
