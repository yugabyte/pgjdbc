package com.yugabyte.ysql;

import java.util.List;
import java.util.Map;

public interface LoadBalancer {

  // todo trim this list down
  public String getLoadBalancerType();
//   public Connection getConnection(LoadBalanceProperties lbProps, String user, String dbName);
  public boolean isHostEligible(Map.Entry<String, LoadBalanceManager.NodeInfo> e);
  public String getLeastLoadedServer(boolean newRequest, List<String> failedHosts);
  int getRefreshListSeconds();
//   public void setForRefresh();

}
