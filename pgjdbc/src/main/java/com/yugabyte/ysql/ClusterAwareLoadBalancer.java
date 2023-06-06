// Copyright (c) YugaByte, Inc.
//
// Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
// in compliance with the License.  You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software distributed under the License
// is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
// or implied.  See the License for the specific language governing permissions and limitations
// under the License.
//

package com.yugabyte.ysql;

import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
import java.util.logging.Logger;

public class ClusterAwareLoadBalancer implements LoadBalancer {
  protected static final Logger LOGGER = Logger.getLogger(ClusterAwareLoadBalancer.class.getName());
  /**
   * The default value should ideally match the interval at which the server-list is updated at
   * cluster side for yb_servers() function. Here, kept it 5 seconds which is not too high (30s) and
   * not too low (1s).
   */
  static final int DEFAULT_FAILED_HOST_TTL_SECONDS = 5;

  private static volatile ClusterAwareLoadBalancer instance;
  private long lastServerListFetchTime = 0L;
  private volatile ArrayList<String> servers = null;
  Map<String, Integer> hostToNumConnMap = new HashMap<>();
  List<String> attempted = new ArrayList<>();
  protected Map<String, String> hostPortMap = new HashMap<>();
  protected Map<String, String> hostPortMapPublic = new HashMap<>();

  @Override
  public int getRefreshListSeconds() {
    return refreshListSeconds;
  }

  protected int refreshListSeconds = LoadBalanceProperties.DEFAULT_REFRESH_INTERVAL;

  public static ClusterAwareLoadBalancer instance() {
    return instance;
  }

  public ClusterAwareLoadBalancer() {
  }

  public static ClusterAwareLoadBalancer getInstance(int refreshListSeconds) {
    if (instance == null) {
      synchronized (ClusterAwareLoadBalancer.class) {
        if (instance == null) {
          instance = new ClusterAwareLoadBalancer();
          instance.refreshListSeconds =
              refreshListSeconds > 0 && refreshListSeconds <= LoadBalanceProperties.MAX_REFRESH_INTERVAL ?
                  refreshListSeconds : LoadBalanceProperties.DEFAULT_REFRESH_INTERVAL;
        }
      }
    }
    return instance;
  }

  public String getPort(String host) {
    String port = hostPortMap.get(host);
    if (port == null) {
      port = hostPortMapPublic.get(host);
    }
    return port;
  }

  @Override
  public boolean isHostEligible(Map.Entry<String, LoadBalanceManager.NodeInfo> e) {
    return !attempted.contains(e.getKey()) && !e.getValue().isDown();
  }

    public synchronized String getLeastLoadedServer(boolean newRequest, List<String> failedHosts) {
    LOGGER.fine("failedHosts: " + failedHosts);
    attempted = failedHosts;
    ArrayList<String> hosts = LoadBalanceManager.getAllEligibleHosts(this);

    int min = Integer.MAX_VALUE;
    ArrayList<String> minConnectionsHostList = new ArrayList<>();
    for (String h : hosts) {
      if (failedHosts.contains(h)) {
        LOGGER.fine("Skipping failed host " + h);
        continue;
      }
      int currLoad = LoadBalanceManager.getLoad(h);
      LOGGER.info("Number of connections to " + h + ": " + currLoad);
      if (currLoad < min) {
        min = currLoad;
        minConnectionsHostList.clear();
        minConnectionsHostList.add(h);
      } else if (currLoad == min) {
        minConnectionsHostList.add(h);
      }
    }
    // Choose a random from the minimum list
    String chosenHost = null;
    if (minConnectionsHostList.size() > 0) {
      int idx = ThreadLocalRandom.current().nextInt(0, minConnectionsHostList.size());
      chosenHost = minConnectionsHostList.get(idx);
    }
    if (chosenHost != null) {
      LOGGER.fine("Host chosen for new connection: " + chosenHost);
//       updateConnectionMap(chosenHost, 1);
      LoadBalanceManager.incrementConnectionCount(chosenHost);
//     } else if (useHostColumn == null) {
      // Current host inet addr did not match with either host inet or public_ip inet addr AND
      // Now we have exhausted the servers list which was populated with (private) host values.
      // So try connecting to the public_ips.
//       return null;
    }
    LOGGER.info("Host chosen for new connection: " + chosenHost);
    return chosenHost;
  }

  public static boolean forceRefresh = false;

  public String getLoadBalancerType() {
    return "ClusterAwareLoadBalancer";
  }

  public List<String> getServers() {
    return Collections.unmodifiableList(servers);
  }

  protected String loadBalancingNodes() {
    return "all";
  }

  public void printHostToConnMap() {
    System.out.println("Current load on " + loadBalancingNodes() + " servers");
    System.out.println("-------------------");
    for (Map.Entry<String, Integer> e : hostToNumConnMap.entrySet()) {
      System.out.println(e.getKey() + " - " + e.getValue());
    }
  }

}
