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

import com.yugabyte.ysql.LoadBalanceService.LoadBalanceType;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.logging.Logger;

public class ClusterAwareLoadBalancer implements LoadBalancer {
  protected static final Logger LOGGER = Logger.getLogger("org.postgresql." + ClusterAwareLoadBalancer.class.getName());

  private static volatile ClusterAwareLoadBalancer instance;
  private List<String> attempted = new ArrayList<>();
  private final LoadBalanceService.LoadBalanceType loadBalance;
  private byte requestFlags;

  private String uuid;

  @Override
  public int getRefreshListSeconds() {
    return refreshListSeconds;
  }

  @Override
  public void setUuid(String uuid) {
    this.uuid = uuid;
  }

  @Override
  public String getUuid() {
    return uuid;
  }

  protected int refreshListSeconds = LoadBalanceProperties.DEFAULT_REFRESH_INTERVAL;

  public ClusterAwareLoadBalancer(LoadBalanceService.LoadBalanceType lb, int refreshInterval) {
    if (lb != null) {
      this.loadBalance = lb;
    } else {
      this.loadBalance = LoadBalanceType.FALSE;
    }
    this.refreshListSeconds = refreshInterval;
  }

  public static ClusterAwareLoadBalancer getInstance(LoadBalanceService.LoadBalanceType lb,
      int refreshListSeconds) {
    if (instance == null) {
      synchronized (ClusterAwareLoadBalancer.class) {
        if (instance == null) {
          instance = new ClusterAwareLoadBalancer(lb, refreshListSeconds);
          instance.refreshListSeconds =
              refreshListSeconds >= 0 && refreshListSeconds <= LoadBalanceProperties.MAX_REFRESH_INTERVAL ?
                  refreshListSeconds : LoadBalanceProperties.DEFAULT_REFRESH_INTERVAL;
          LOGGER.fine("Created a new cluster-aware LB instance with loadbalance = " +
              instance.loadBalance + " and refresh interval " + instance.refreshListSeconds + " seconds");
        }
      }
    }
    return instance;
  }

  public String toString() {
    return this.getClass().getSimpleName() + ": loadBalance = " +
      loadBalance + ", refreshInterval = " + refreshListSeconds;
  }

  @Override
  public boolean isHostEligible(Map.Entry<String, LoadBalanceService.NodeInfo> e,
      Byte requestFlags) {
    // e.getKey() is the hostname
    return !attempted.contains(e.getKey()) && !e.getValue().isDown()
        && LoadBalanceService.isRightNodeType(loadBalance, e.getValue().getNodeType(), requestFlags);
  }

  public synchronized String getLeastLoadedServer(boolean newRequest, List<String> failedHosts,
      ArrayList<String> timedOutHosts) {
    attempted = failedHosts;
    if (timedOutHosts != null) {
      attempted.addAll(timedOutHosts);
    }
    requestFlags = newRequest ? LoadBalanceService.STRICT_PREFERENCE : requestFlags;
    LOGGER.fine("newRequest: " + newRequest + ", failedHosts: " + failedHosts +
        ", timedOutHosts: " + timedOutHosts + ", requestFlags: " + requestFlags);
    String chosenHost = null;

    while (true) {
      ArrayList<String> hosts = LoadBalanceService.getAllEligibleHosts(this, requestFlags);
      int min = Integer.MAX_VALUE;
      ArrayList<String> minConnectionsHostList = new ArrayList<>();
      for (String h : hosts) {
        boolean wasTimedOutHost = timedOutHosts != null && timedOutHosts.contains(h);
        if (failedHosts.contains(h) || wasTimedOutHost) {
          LOGGER.fine("Skipping failed host " + h + "(was timed out host=" + wasTimedOutHost + ")");
          continue;
        }
        int currLoad = LoadBalanceService.getLoad(h);
        LOGGER.fine("Number of connections to " + h + ": " + currLoad);
        if (currLoad < min) {
          min = currLoad;
          minConnectionsHostList.clear();
          minConnectionsHostList.add(h);
        } else if (currLoad == min) {
          minConnectionsHostList.add(h);
        }
      }
      // Choose a random from the minimum list
      if (!minConnectionsHostList.isEmpty()) {
        int idx = ThreadLocalRandom.current().nextInt(0, minConnectionsHostList.size());
        chosenHost = minConnectionsHostList.get(idx);
      }
      if (chosenHost != null) {
        LoadBalanceService.incrementConnectionCount(chosenHost);
        break; // We got a host
      } else if (requestFlags == LoadBalanceService.STRICT_PREFERENCE) {
        // Relax the STRICT_PREFERENCE condition and consider other node types
        requestFlags = (byte) 0;
      } else {
        break; // No more hosts to try
      }
    }
    LOGGER.fine("Host chosen for new connection: " + chosenHost);
    if (chosenHost == null && (loadBalance == LoadBalanceType.ONLY_PRIMARY ||
        loadBalance == LoadBalanceType.ONLY_RR)) {
      throw new IllegalStateException("No node available in "
        + (loadBalance == LoadBalanceType.ONLY_PRIMARY ? "primary" : "read-replica")
        + " cluster to connect to.");
    }
    return chosenHost;
  }

}
