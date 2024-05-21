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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.logging.Logger;

public class ClusterAwareLoadBalancer implements LoadBalancer {
  protected static final Logger LOGGER = Logger.getLogger("org.postgresql." + ClusterAwareLoadBalancer.class.getName());

  private static volatile ClusterAwareLoadBalancer instance;
  List<String> attempted = new ArrayList<>();

  @Override
  public int getRefreshListSeconds() {
    return refreshListSeconds;
  }

  protected int refreshListSeconds = LoadBalanceProperties.DEFAULT_REFRESH_INTERVAL;

  public ClusterAwareLoadBalancer() {
  }

  public static ClusterAwareLoadBalancer getInstance(int refreshListSeconds) {
    if (instance == null) {
      synchronized (ClusterAwareLoadBalancer.class) {
        if (instance == null) {
          instance = new ClusterAwareLoadBalancer();
          instance.refreshListSeconds =
              refreshListSeconds >= 0 && refreshListSeconds <= LoadBalanceProperties.MAX_REFRESH_INTERVAL ?
                  refreshListSeconds : LoadBalanceProperties.DEFAULT_REFRESH_INTERVAL;
          LOGGER.fine("Created a new cluster-aware LB instance with refresh" +
              " interval " + instance.refreshListSeconds + " seconds");
        }
      }
    }
    return instance;
  }

  @Override
  public boolean isHostEligible(Map.Entry<String, LoadBalanceService.NodeInfo> e) {
    return !attempted.contains(e.getKey()) && !e.getValue().isDown();
  }

  public synchronized String getLeastLoadedServer(boolean newRequest, List<String> failedHosts, ArrayList<String> timedOutHosts) {
    LOGGER.fine("failedHosts: " + failedHosts + ", timedOutHosts: " + timedOutHosts);
    attempted = failedHosts; // todo add timedOutHosts
    ArrayList<String> hosts = LoadBalanceManager.getAllEligibleHosts(this);

    int min = Integer.MAX_VALUE;
    ArrayList<String> minConnectionsHostList = new ArrayList<>();
    for (String h : hosts) {
      boolean wasTimedOutHost = timedOutHosts != null && timedOutHosts.contains(h);
      if (failedHosts.contains(h) || wasTimedOutHost) {
        LOGGER.fine("Skipping failed host " + h + "(was timed out host=" + wasTimedOutHost +")");
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
    String chosenHost = null;
    if (minConnectionsHostList.size() > 0) {
      int idx = ThreadLocalRandom.current().nextInt(0, minConnectionsHostList.size());
      chosenHost = minConnectionsHostList.get(idx);
    }
    if (chosenHost != null) {
      LoadBalanceService.incrementConnectionCount(chosenHost);
    }
    LOGGER.fine("Host chosen for new connection: " + chosenHost);
    return chosenHost;
  }

}
