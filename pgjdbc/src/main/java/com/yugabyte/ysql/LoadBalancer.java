package com.yugabyte.ysql;

import java.util.List;
import java.util.Map;

/**
 * An interface for any load balancing policy to implement. The LoadBalanceManager invokes the
 * implemented methods while processing a new connection request.
 *
 * @see LoadBalanceManager
 * @see ClusterAwareLoadBalancer
 * @see TopologyAwareLoadBalancer
 */
public interface LoadBalancer {

  /**
   * @param e The {@link com.yugabyte.ysql.LoadBalanceManager.NodeInfo} object for the host
   * @return true, if a host is eligible to be considered for a connection request
   */
  public boolean isHostEligible(Map.Entry<String, LoadBalanceManager.NodeInfo> e);

  /**
   * @param newRequest  whether this invocation is first for a new connection request
   * @param failedHosts list of host names which have been known to be down
   * @return the name of a host with the least number of connections, as per the driver's stats
   */
  public String getLeastLoadedServer(boolean newRequest, List<String> failedHosts);

  /**
   * @return the value of the property "yb-servers-refresh-interval" specified either in the url or
   * as a property
   */
  int getRefreshListSeconds();

}
