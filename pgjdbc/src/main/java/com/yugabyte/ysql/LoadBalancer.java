package com.yugabyte.ysql;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * An interface for any load balancing policy to implement. The LoadBalanceService invokes the
 * implemented methods while processing a new connection request.
 *
 * @see LoadBalanceService
 * @see ClusterAwareLoadBalancer
 * @see TopologyAwareLoadBalancer
 */
public interface LoadBalancer {

  /**
   * @param e The {@link LoadBalanceService.NodeInfo} object for the host
   * @param requestFlags The attributes for the load balancer to make use of
   * @return true, if a host is eligible to be considered for a connection request
   */
  boolean isHostEligible(Map.Entry<String, LoadBalanceService.NodeInfo> e, Byte requestFlags);

  /**
   * @param newRequest    whether this invocation is first for a new connection request
   * @param failedHosts   list of host names which have been known to be down
   * @param timedOutHosts list of host names where connections were attempted but timed out
   * @return the name of a host with the least number of connections, as per the driver's stats
   */
  String getLeastLoadedServer(boolean newRequest, List<String> failedHosts, ArrayList<String> timedOutHosts);

  /**
   * @return the value of the property "yb-servers-refresh-interval" specified either in the url or
   * as a property
   */
  int getRefreshListSeconds();

}
