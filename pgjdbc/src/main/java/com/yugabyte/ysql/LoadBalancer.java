package com.yugabyte.ysql;

import java.util.List;
import java.util.Map;

public interface LoadBalancer {

  public boolean isHostEligible(Map.Entry<String, LoadBalanceManager.NodeInfo> e);
  public String getLeastLoadedServer(boolean newRequest, List<String> failedHosts);
  int getRefreshListSeconds();

}
