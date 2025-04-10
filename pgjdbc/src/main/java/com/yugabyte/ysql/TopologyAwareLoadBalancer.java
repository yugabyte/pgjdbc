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

import static com.yugabyte.ysql.LoadBalanceProperties.DEFAULT_REFRESH_INTERVAL;
import static com.yugabyte.ysql.LoadBalanceProperties.LOCATIONS_DELIMITER;
import static com.yugabyte.ysql.LoadBalanceProperties.MAX_PREFERENCE_VALUE;
import static com.yugabyte.ysql.LoadBalanceProperties.PREFERENCE_DELIMITER;
import static com.yugabyte.ysql.LoadBalanceProperties.REFRESH_INTERVAL_KEY;
import static com.yugabyte.ysql.LoadBalanceProperties.TOPOLOGY_AWARE_PROPERTY_KEY;

import com.yugabyte.ysql.LoadBalanceService.LoadBalanceType;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.logging.Logger;

public class TopologyAwareLoadBalancer implements LoadBalancer {
  protected static final Logger LOGGER = Logger.getLogger("org.postgresql." + TopologyAwareLoadBalancer.class.getName());
  /**
   * Holds the value of topology-keys specified.
   */
  private final String placements;
  private String uuid;
  private final LoadBalanceService.LoadBalanceType loadBalance;

  private long lastRequestTime;
  /**
   * Derived from the placements value above.
   */
  private final Map<Integer, Set<LoadBalanceService.CloudPlacement>> allowedPlacements = new HashMap<>();
  private final int PRIMARY_PLACEMENTS_INDEX = 1;
  private final int REST_OF_CLUSTER_INDEX = -1;
  /**
   * The index of placement level currently being used for new connection request. It is always
   * reset to zero for a new connection request.
   */
  private int currentPlacementIndex = 1;
  private List<String> attempted = new ArrayList<>();
  private final int refreshIntervalSeconds;
  private boolean explicitFallbackOnly = false;
  private byte requestFlags;
  private int failedHostReconnectDelaySecs;

  public long getLastRefreshTime() {
    return lastRefreshTime;
  }

  public void setLastRefreshTime(long lastRefreshTime) {
    this.lastRefreshTime = lastRefreshTime;
  }

  private long lastRefreshTime;

  public TopologyAwareLoadBalancer(LoadBalanceProperties.ProcessedProperties processedProperties) {
    LoadBalanceType lb = processedProperties.getLoadBalance();
    if (lb != null) {
      loadBalance = lb;
    } else {
      loadBalance = LoadBalanceType.FALSE;
    }
    placements = processedProperties.getPlacements();
    explicitFallbackOnly = processedProperties.isExplicitFallbackOnly();
    int refreshInterval = processedProperties.getRefreshInterval();
    refreshIntervalSeconds =
        refreshInterval >= 0 && refreshInterval <= LoadBalanceProperties.MAX_REFRESH_INTERVAL ?
            refreshInterval : LoadBalanceProperties.DEFAULT_REFRESH_INTERVAL;
    this.failedHostReconnectDelaySecs = processedProperties.getFailedHostReconnectDelaySecs();
    parseGeoLocations();
  }

  protected String loadBalancingNodes() {
    return placements;
  }

  private void populatePlacementSet(String placement,
      Set<LoadBalanceService.CloudPlacement> allowedPlacements) {
    String[] placementParts = placement.split("\\.");
    if (placementParts.length != 3 || placementParts[0].equals("*") || placementParts[1].equals(
        "*")) {
      // Return an error so the user takes corrective action.
      LOGGER.warning(
          "Malformed " + TOPOLOGY_AWARE_PROPERTY_KEY + " property value: " + placement);
      throw new IllegalArgumentException("Malformed " + TOPOLOGY_AWARE_PROPERTY_KEY
          + " property value: " + placement);
    }
    LoadBalanceService.CloudPlacement cp = new LoadBalanceService.CloudPlacement(
        placementParts[0], placementParts[1], placementParts[2]);
    LOGGER.fine("Adding placement " + cp + " to allowed " + "list");
    allowedPlacements.add(cp);
  }

  private void parseGeoLocations() {
    String[] values = placements.split(LOCATIONS_DELIMITER);
    for (String value : values) {
      String[] v = value.split(PREFERENCE_DELIMITER);
      if (v.length > 2 || value.endsWith(":")) {
        throw new IllegalArgumentException("Invalid value part for property " + TOPOLOGY_AWARE_PROPERTY_KEY + ": " + value);
      }
      if (v.length == 1) {
        Set<LoadBalanceService.CloudPlacement> primary =
            allowedPlacements.computeIfAbsent(PRIMARY_PLACEMENTS_INDEX, k -> new HashSet<>());
        populatePlacementSet(v[0], primary);
      } else {
        int pref = Integer.parseInt(v[1]);
        if (pref > 0 && pref <= MAX_PREFERENCE_VALUE) {
          Set<LoadBalanceService.CloudPlacement> cpSet = allowedPlacements.computeIfAbsent(pref, k -> new HashSet<>());
          populatePlacementSet(v[0], cpSet);
        } else {
          throw new IllegalArgumentException("Invalid preference value for property " + TOPOLOGY_AWARE_PROPERTY_KEY + ": " + value);
        }
      }
    }
    LOGGER.fine("allowedPlacements: " + allowedPlacements);
  }

  @Override
  public int getRefreshListSeconds() {
    return Integer.getInteger(REFRESH_INTERVAL_KEY, refreshIntervalSeconds);
  }

  @Override
  public void setUuid(String uuid) {
    this.uuid = uuid;
  }

  @Override
  public String getUuid() {
    return this.uuid;
  }

  public boolean isExplicitFallbackOnly() {
    return explicitFallbackOnly;
  }

  @Override
  public boolean isHostEligible(Map.Entry<String, LoadBalanceService.NodeInfo> e,
      Byte requestFlags) {
    Set<LoadBalanceService.CloudPlacement> set = allowedPlacements.get(currentPlacementIndex);
    // found is true when:
    // we are searching for nodes in entire cluster AND fallback-to-topology-keys-only is false
    // OR
    // we are searching for nodes in entire cluster AND load-balance is set to prefer-*
    // OR
    // allowed placements contain the node's placement
    boolean found = (currentPlacementIndex == REST_OF_CLUSTER_INDEX
        && (!explicitFallbackOnly || loadBalance == LoadBalanceType.PREFER_PRIMARY || loadBalance == LoadBalanceType.PREFER_RR))
        || (set != null && e.getValue().getPlacement().isContainedIn(set));
    boolean isRightNode = LoadBalanceService.isRightNodeType(loadBalance, e.getValue().getNodeType(), requestFlags);
    boolean isAttempted = attempted.contains(e.getKey());
    boolean isDown = e.getValue().isDown();
    LOGGER.fine(e.getKey() + " has currentPlacementIndex " + currentPlacementIndex + ", required placement? "
        + found + ", isDown? " + isDown + ", attempted? " + isAttempted + ", isRightNodeType? " + isRightNode);
    return found
        && !isAttempted
        && !isDown
        && isRightNode;
  }

  public synchronized String getLeastLoadedServer(boolean newRequest, List<String> failedHosts, ArrayList<String> timedOutHosts) {
    LOGGER.fine("newRequest: " + newRequest + ", failedHosts: " + failedHosts);
    // Reset currentPlacementIndex if it's a new request AND refresh() happened after the
    // last request was processed
    if (newRequest && (LoadBalanceService.getLastRefreshTime(this) - lastRequestTime >= 0)) {
      currentPlacementIndex = PRIMARY_PLACEMENTS_INDEX;
    } else {
      LOGGER.fine("Placements: [" + placements
          + "]. Attempting to connect to servers in fallback level-"
          + (currentPlacementIndex - 1) + " ...");
    }
    ArrayList<String> hosts;
    String chosenHost = null;
    requestFlags = newRequest ? LoadBalanceService.STRICT_PREFERENCE : requestFlags;
    while (chosenHost == null && currentPlacementIndex <= MAX_PREFERENCE_VALUE) {
      attempted = failedHosts;
      if (timedOutHosts != null) {
        attempted.addAll(timedOutHosts);
      }
      hosts = LoadBalanceService.getAllEligibleHosts(getUuid(), this, requestFlags);

      int min = Integer.MAX_VALUE;
      ArrayList<String> minConnectionsHostList = new ArrayList<>();
      for (String h : hosts) {
        if (failedHosts.contains(h)) {
          LOGGER.fine("Skipping failed host " + h);
          continue;
        }
        int currLoad = LoadBalanceService.getLoad(getUuid(), h);
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
        LoadBalanceService.incrementConnectionCount(getUuid(), chosenHost);
      } else {
        LOGGER.fine("chosenHost is null for placement level " + currentPlacementIndex
            + ", allowedPlacements: " + allowedPlacements);
        // No host found. Go to the next placement level.
        currentPlacementIndex += 1;
        while (allowedPlacements.get(currentPlacementIndex) == null && currentPlacementIndex > 0) {
          currentPlacementIndex += 1;
          if (currentPlacementIndex > MAX_PREFERENCE_VALUE) {
            // All explicit fallbacks are done with no luck. Now try rest-of-cluster
            currentPlacementIndex = REST_OF_CLUSTER_INDEX;
          }
        }
        if (currentPlacementIndex == 0) {
          // No host found in entire cluster. Relax the STRICT_PREFERENCE if load-balance is prefer-*
          if (requestFlags == LoadBalanceService.STRICT_PREFERENCE &&
              (loadBalance == LoadBalanceType.PREFER_PRIMARY || loadBalance == LoadBalanceType.PREFER_RR)) {
            LOGGER.fine("Even rest of cluster did not have a host for us." +
                " So relax the node type condition for prefer-* and try again once");
            currentPlacementIndex = REST_OF_CLUSTER_INDEX;
            requestFlags = (byte) 0;
          } else {
            break;
          }
        }
        LOGGER.fine("Next, attempting to connect to hosts from placement level " + currentPlacementIndex);
      }
    }
    lastRequestTime = System.currentTimeMillis();
    LOGGER.fine("Host chosen for new connection: " + chosenHost);
    // Throw error if no host is found AND load-balance=only-* OR
    // load-balance=any AND fallback-to-topology-keys-only is true
    if (chosenHost == null &&
        (loadBalance == LoadBalanceType.ONLY_PRIMARY || loadBalance == LoadBalanceType.ONLY_RR ||
            (loadBalance == LoadBalanceType.ANY && explicitFallbackOnly))) {
      throw new IllegalStateException("No node available in the given placements for the " +
          (loadBalance == LoadBalanceType.ONLY_PRIMARY ? "primary" :
              (loadBalance == LoadBalanceType.ONLY_RR ? "read-replica" : "entire")) +
          " cluster to connect to.");
    }
    return chosenHost;
  }

}
