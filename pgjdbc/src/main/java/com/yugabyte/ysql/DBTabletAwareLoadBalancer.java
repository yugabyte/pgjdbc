package com.yugabyte.ysql;

import java.sql.*;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

public class DBTabletAwareLoadBalancer implements LoadBalancer {

  private static final String GET_TABLET_INFO_QUERY = "SELECT * FROM yb_tablets()";
  private static String COL_DB = "database";
  private static String COL_LEADER = "leader";
  private static String COL_FOLLOWER = "follower";
  private static String COL_OTHER_INFO = "other_info";
  protected static final Logger LOGGER = Logger.getLogger("org.postgresql.Driver");
  private static DBTabletAwareLoadBalancer instance;

  private DBTabletAwareLoadBalancer() {
  }

  // Accessed under instance lock
  private final Map<String, DBTabletInfo> dbTabletInfoMap = new ConcurrentHashMap<String,
      DBTabletInfo>();
  private long refreshIntervalSeconds = 60; // todo Make it configurable.
  private long lastMetadataFetchTime;
  private boolean forceRefresh = false;

  public static DBTabletAwareLoadBalancer getInstance() {
    if (instance == null) {
      synchronized (DBTabletAwareLoadBalancer.class) {
        if (instance == null) {
          instance = new DBTabletAwareLoadBalancer();
        }
      }
    }
    return instance;
  }

  @Override
  public String getLoadBalancerType() {
    return this.getClass().getSimpleName();
  }

  @Override
  public boolean needsRefresh() {
    if (forceRefresh) {
      forceRefresh = false;
      LOGGER.log(Level.FINE, getLoadBalancerType() + ": Force Refresh is set to true.");
      return true;
    }
    long currentTimeInMillis = System.currentTimeMillis();
    long elapsedTime = (currentTimeInMillis - lastMetadataFetchTime) / 1000;
    if (elapsedTime > refreshIntervalSeconds) {
      LOGGER.log(Level.FINE, getLoadBalancerType() + ": "
          + "Needs refresh as tablet details may be stale or being fetched for the first time.");
      return true;
    }
    LOGGER.log(Level.FINE, getLoadBalancerType() + ": Refresh not required.");
    return false;
  }

  @Override
  public boolean refresh(Connection conn) throws SQLException {
    if (!needsRefresh()) {
      return true;
    }
    // else clear server list
    long currTime = System.currentTimeMillis();
    if (!updateTabletInfo(conn)) {
      return false;
    }
    lastMetadataFetchTime = currTime;
    return true;
  }

  public void setForceRefresh() {
    forceRefresh = true;
  }

  private boolean  updateTabletInfo(Connection conn) throws SQLException {
    if (1 == 1) {
      updateDummyInfo();
      return true;
    }
    try {
      Statement st = conn.createStatement();
      ResultSet rs = st.executeQuery(GET_TABLET_INFO_QUERY);

      dbTabletInfoMap.clear();

      while (rs.next()) {
        String dbName = rs.getString(COL_DB);
        String leader = rs.getString(COL_LEADER);
        String[] followers = (String[]) rs.getArray(COL_FOLLOWER).getArray();
        String otherInfo = rs.getString(COL_OTHER_INFO);

        dbTabletInfoMap.put(dbName, new DBTabletInfo(dbName, leader, followers, otherInfo));
      }
    } catch (SQLException sqle) {
      LOGGER.warning("Failed to fetch and update tablet information" + sqle);
      return false;
    }
    return true;
  }

  private void updateDummyInfo() {
    dbTabletInfoMap.clear();

    dbTabletInfoMap.put("yugabyte", new DBTabletInfo("yugabyte", "127.0.0.1", new String[]{"127.0" +
        ".0.2", "127.0.0.3"}, null));
    dbTabletInfoMap.put("db1", new DBTabletInfo("yugabyte", "127.0.0.2", new String[]{"127.0.0.1"
        , "127.0.0.3"}, null));
    dbTabletInfoMap.put("db2", new DBTabletInfo("yugabyte", "127.0.0.3", new String[]{"127.0.0.1"
        , "127.0.0.2"}, null));
    dbTabletInfoMap.put("db3", new DBTabletInfo("yugabyte", "127.0.0.1", new String[]{"127.0.0.3"
        , "127.0.0.2"}, null));
  }

  public String getLeaderNode(String db) {
    return dbTabletInfoMap.get(db).getLeader();
  }
}

class DBTabletInfo {
  private String databaseName;
  private String leader;
  private String[] followers;
  private Object otherInfo;

  public String getDatabaseName() {
    return databaseName;
  }

  public void setDatabaseName(String databaseName) {
    this.databaseName = databaseName;
  }

  public String getLeader() {
    return leader;
  }

  public void setLeader(String leader) {
    this.leader = leader;
  }

  public String[] getFollowers() {
    return followers;
  }

  public void setFollowers(String[] followers) {
    this.followers = followers;
  }

  public Object getOtherInfo() {
    return otherInfo;
  }

  public void setOtherInfo(Object otherInfo) {
    this.otherInfo = otherInfo;
  }

  public DBTabletInfo() {
  }
  public DBTabletInfo(String db, String leader, String[] followers, Object otherInfo) {
    this.databaseName = db;
    this.leader = leader;
    this.followers = followers;
    this.otherInfo = otherInfo;
  }
}
