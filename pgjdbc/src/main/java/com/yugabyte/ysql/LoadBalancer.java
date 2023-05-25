package com.yugabyte.ysql;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.Set;

public interface LoadBalancer {

  // todo trim this list down
  public String getLoadBalancerType();
  public boolean needsRefresh();
  public boolean refresh(Connection conn) throws SQLException;
  public Connection getConnection(LoadBalanceProperties lbProps, String user, String dbName);

  public Set<String> getUnreachableHosts();
  public String getLeastLoadedServer(List<String> failedHosts);
  public String getPort(String chosenHost);
  public void updateConnectionMap(String chosenHost, int incDec);
  public void setForRefresh();
  public boolean hasMorePreferredNode(String chosenHost);
  public void decrementHostToNumConnCount(String chosenHost);
  public void updateFailedHosts(String chosenHost);

}
