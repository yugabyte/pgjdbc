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

  @Test
  void removedHostDetectionIsCaseInsensitive() throws SQLException {
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

    // yb_servers() returns "hosta" (lowercase) — should match "HostA" case-insensitively
    // "HostB" is missing, so it should be detected as removed
    setupResultSetRows(
        row("hosta", "10.0.0.1", "5433", "aws", "us-west", "us-west-2a", "primary", uuid)
    );

    lb.setUuid(uuid);
    LoadBalanceService.refresh(mockConn, 300, lb);

    ConcurrentHashMap<String, LoadBalanceService.NodeInfo> updatedMap =
        LoadBalanceService.uuidToClusterInfoMap.get(uuid).getHostToNodeInfoMap();
    // "HostA" matched "hosta" case-insensitively, so it should NOT appear in removed set.
    // It stays in the map (keyed as "HostA" since putIfAbsent won't overwrite).
    assertTrue(updatedMap.containsKey("HostA"));
    // "HostB" was removed and down >300s, so it should be evicted
    assertFalse(updatedMap.containsKey("HostB"),
        "Case-insensitive match should detect 'HostB' as removed and evict it");
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
