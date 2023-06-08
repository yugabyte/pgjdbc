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

import static com.yugabyte.ysql.LoadBalanceProperties.*;

import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
import java.util.logging.Level;
import java.util.logging.Logger;

public class TopologyAwareLoadBalancer implements LoadBalancer {
  protected static final Logger LOGGER = Logger.getLogger(TopologyAwareLoadBalancer.class.getName());
  private final String placements;
  private final Map<Integer, Set<LoadBalanceManager.CloudPlacement>> allowedPlacements = new HashMap<>();
  private final int PRIMARY_PLACEMENTS = 1;
  private final int REST_OF_CLUSTER = -1;
  private int currentPlacementIndex = 1;
  List<String> attempted = new ArrayList<>();
  private int refreshIntervalSeconds;


  public TopologyAwareLoadBalancer(String placementValues) {
    placements = placementValues;
    refreshIntervalSeconds = Integer.getInteger(REFRESH_INTERVAL_KEY, DEFAULT_REFRESH_INTERVAL);
    parseGeoLocations();
  }

  protected String loadBalancingNodes() {
    return placements;
  }

  private void populatePlacementSet(String placement,
      Set<LoadBalanceManager.CloudPlacement> allowedPlacements) {
    String[] placementParts = placement.split("\\.");
    if (placementParts.length != 3 || placementParts[0].equals("*") || placementParts[1].equals(
        "*")) {
      // Return an error so the user takes corrective action.
      LOGGER.log(Level.WARNING,
          "Malformed " + TOPOLOGY_AWARE_PROPERTY_KEY + " property value: " + placement);
      throw new IllegalArgumentException("Malformed " + TOPOLOGY_AWARE_PROPERTY_KEY + " property " +
          "value: " + placement);
    }
    LoadBalanceManager.CloudPlacement cp = new LoadBalanceManager.CloudPlacement(
        placementParts[0], placementParts[1], placementParts[2]);
    LOGGER.fine(getLoadBalancerType() + ": Adding placement " + cp + " to allowed " + "list");
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
        Set<LoadBalanceManager.CloudPlacement> primary = allowedPlacements.computeIfAbsent(PRIMARY_PLACEMENTS, k -> new HashSet<>());
        populatePlacementSet(v[0], primary);
      } else {
        int pref = Integer.valueOf(v[1]);
        if (pref > 0 && pref <= MAX_PREFERENCE_VALUE) {
          Set<LoadBalanceManager.CloudPlacement> cpSet = allowedPlacements.computeIfAbsent(pref, k -> new HashSet<>());
          populatePlacementSet(v[0], cpSet);
        } else {
          throw new IllegalArgumentException("Invalid preference value for property " + TOPOLOGY_AWARE_PROPERTY_KEY + ": " + value);
        }
      }
    }
  }

  @Override
  public int getRefreshListSeconds() {
    return Integer.getInteger(REFRESH_INTERVAL_KEY, refreshIntervalSeconds);
  }

  @Override
  public boolean isHostEligible(Map.Entry<String, LoadBalanceManager.NodeInfo> e) {
    Set<LoadBalanceManager.CloudPlacement> set = allowedPlacements.get(currentPlacementIndex);
    boolean onlyExplicitFallback = Boolean.getBoolean(EXPLICIT_FALLBACK_ONLY_KEY);
    boolean found = (currentPlacementIndex == REST_OF_CLUSTER && !onlyExplicitFallback)
        || (set != null && e.getValue().getPlacement().isContainedIn(set));
    boolean isAttempted = attempted.contains(e.getKey());
    boolean isDown = e.getValue().isDown();
    LOGGER.info(e.getKey() + " has required placement? " + found + ", isDown? " + isDown + ", attempted? " + isAttempted);
    return found
        && !isAttempted
        && !isDown;
  }

  public synchronized String getLeastLoadedServer(boolean newRequest, List<String> failedHosts) {
    LOGGER.info("failedHosts: " + failedHosts);
    if (newRequest) {
      LOGGER.info("new request ");
      currentPlacementIndex = PRIMARY_PLACEMENTS;
    } else {
      LOGGER.info("Placements: [" + placements
          + "]. Attempting to connect to servers in fallback level-"
          + (currentPlacementIndex-1) + " ...");
    }
    ArrayList<String> hosts;
    String chosenHost = null;
    while (chosenHost == null && currentPlacementIndex <= MAX_PREFERENCE_VALUE) {
      attempted = failedHosts;
      hosts = LoadBalanceManager.getAllEligibleHosts(this);

      int min = Integer.MAX_VALUE;
      ArrayList<String> minConnectionsHostList = new ArrayList<>();
      for (String h : hosts) {
        if (failedHosts.contains(h)) {
          LOGGER.info("Skipping failed host " + h);
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
      if (minConnectionsHostList.size() > 0) {
        int idx = ThreadLocalRandom.current().nextInt(0, minConnectionsHostList.size());
        chosenHost = minConnectionsHostList.get(idx);
      }
      if (chosenHost != null) {
        LOGGER.fine("Host chosen for new connection: " + chosenHost);
        LoadBalanceManager.incrementConnectionCount(chosenHost);
//     } else if (useHostColumn == null) {
        // Current host inet addr did not match with either host inet or public_ip inet addr AND
        // Now we have exhausted the servers list which was populated with (private) host values.
        // So try connecting to the public_ips.
//       return null;
      } else {
        LOGGER.info("chosenHost is null for placement level " + currentPlacementIndex);
        LOGGER.info("allowedPlacements " + allowedPlacements);
        currentPlacementIndex += 1;
        while (allowedPlacements.get(currentPlacementIndex) == null && currentPlacementIndex > 0) {
          currentPlacementIndex += 1;
          if (currentPlacementIndex > MAX_PREFERENCE_VALUE) {
            // All explicit fallbacks are done with no luck. Now try rest-of-cluster
            currentPlacementIndex = REST_OF_CLUSTER;
          } else if (currentPlacementIndex == 0) {
            // Even rest-of-cluster did not help. Quit
            break;
          }
        }
        if (currentPlacementIndex == 0) {
          break;
        }
        LOGGER.info("Will be trying placement level " + currentPlacementIndex + " next.");
      }
    }
    LOGGER.info("Host chosen for new connection: " + chosenHost);
    return chosenHost;
  }

  public String getLoadBalancerType() {
    return "TopologyAwareLoadBalancer";
  }

}
