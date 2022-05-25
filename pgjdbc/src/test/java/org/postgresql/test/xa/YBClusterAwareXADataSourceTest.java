package org.postgresql.test.xa;

import com.yugabyte.ysql.ClusterAwareLoadBalancer;
import com.yugabyte.ysql.LoadBalanceProperties;
import com.yugabyte.ysql.YBClusterAwareXADataSource;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;

import org.junit.Test;

import org.postgresql.test.TestUtil;
import org.postgresql.test.jdbc2.optional.BaseDataSourceTest;
import org.postgresql.xa.PGXADataSource;

import javax.sql.XAConnection;
import javax.sql.XADataSource;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Map;

import static org.junit.Assume.assumeTrue;

public class YBClusterAwareXADataSourceTest {

  private XADataSource xaDs;
  private Connection dbConn;
  private boolean connIsSuper;

  public YBClusterAwareXADataSourceTest() {
    xaDs = new YBClusterAwareXADataSource();
    BaseDataSourceTest.setupDataSource((PGXADataSource) xaDs);
  }

  @Before
  public void setUp() throws Exception {
    dbConn = TestUtil.openDB();

    // Check if we're operating as a superuser; some tests require it.
    Statement st = dbConn.createStatement();
    st.executeQuery("SHOW is_superuser;");
    ResultSet rs = st.getResultSet();
    rs.next(); // One row is guaranteed
    connIsSuper = rs.getBoolean(1); // One col is guaranteed
    st.close();

    TestUtil.createTable(dbConn, "testxa1", "foo int primary key");
  }

  @After
  public void tearDown() throws SQLException {
    TestUtil.dropTable(dbConn, "testxa1");
    TestUtil.closeDB(dbConn);
  }

  @Test
  public void testClusterAwareLB() throws Exception {
    XAConnection[] conns = new XAConnection[10];
    ((YBClusterAwareXADataSource)xaDs).setTopologyKeys("");
    try {
      for (int i = 0; i < 9; i++) {
        conns[i] = xaDs.getXAConnection();
      }
      ClusterAwareLoadBalancer lb = LoadBalanceProperties.CONNECTION_MANAGER_MAP.get("simple");
      lb.printHostToConnMap();
      Map<String, Integer> map = lb.getHostToConnMapCopy();
      Assert.assertTrue("Found " + map.size() + " servers instead of 3!", map.size() == 3);
      for (Map.Entry<String, Integer> e : map.entrySet()) {
        assumeTrue("Server " + e.getKey() + " has " + e.getValue() + " connections (3 expected)!", e.getValue() == 3);
      }
    } finally {
      for (int i = 0; i < 10; i++) {
        if (conns[i] != null) conns[i].close();
      }
    }
  }

  @Test
  public void testTopologyAwareLB() throws Exception {
    String tk = "cloud1.region1.zone2,cloud1.region1.zone3";
    ((YBClusterAwareXADataSource)xaDs).setTopologyKeys(tk);
    XAConnection[] conns = new XAConnection[10];
    try {
      for (int i = 0; i < 10; i++) {
        conns[i] = xaDs.getXAConnection();
      }
      ClusterAwareLoadBalancer lb = LoadBalanceProperties.CONNECTION_MANAGER_MAP.get(tk);
      lb.printHostToConnMap();
      Map<String, Integer> map = lb.getHostToConnMapCopy();
      Assert.assertTrue("Found " + map.size() + " servers instead of 2!",map.size() == 2);
      for (Map.Entry<String, Integer> e : map.entrySet()) {
        assumeTrue("Server " + e.getKey() + " has " + e.getValue() + " connections (3 expected)!", e.getValue() == 5);
      }
    } finally {
      for (int i = 0; i < 10; i++) {
        if (conns[i] != null) conns[i].close();
      }
    }
  }
}
