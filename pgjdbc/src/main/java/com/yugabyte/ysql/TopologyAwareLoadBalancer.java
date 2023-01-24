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
import java.util.logging.Level;

public class TopologyAwareLoadBalancer extends ClusterAwareLoadBalancer {
  private final String placements;
  private final Map<Integer, Set<CloudPlacement>> allowedPlacements = new HashMap<>();
  private final Map<Integer, ArrayList<String>> fallbackPrivateIPs = new HashMap<>();
  private final Map<Integer, ArrayList<String>> fallbackPublicIPs = new HashMap<>();
  private final int PRIMARY_PLACEMENTS = 1;
  private final int FIRST_FALLBACK = 2;
  private final int REST_OF_CLUSTER = -1;

  public TopologyAwareLoadBalancer(String placementValues) {
    placements = placementValues;
    parseGeoLocations();
  }

  protected String loadBalancingNodes() {
    return placements;
  }

  private void populatePlacementSet(String placements, Set<TopologyAwareLoadBalancer.CloudPlacement> allowedPlacements) {
    String[] pStrings = placements.split(LOCATIONS_DELIMITER);
    for (String pl : pStrings) {
      String[] placementParts = pl.split("\\.");
      if (placementParts.length != 3 || placementParts[0].equals("*") || placementParts[1].equals("*")) {
        // Return an error so the user takes corrective action.
        LOGGER.log(Level.WARNING, "Malformed " + TOPOLOGY_AWARE_PROPERTY_KEY + " property value: " + pl);
        throw new IllegalArgumentException("Malformed " + TOPOLOGY_AWARE_PROPERTY_KEY + " property value: " + pl);
      }
      TopologyAwareLoadBalancer.CloudPlacement cp = new TopologyAwareLoadBalancer.CloudPlacement(
          placementParts[0], placementParts[1], placementParts[2]);
      LOGGER.log(Level.FINE, "Adding placement " + cp + " to allowed list");
      allowedPlacements.add(cp);
    }
  }

  private void parseGeoLocations() {
    String[] values = placements.split(LOCATIONS_DELIMITER);
    for (String value : values) {
      String[] v = value.split(PREFERENCE_DELIMITER);
      if (v.length > 2 || value.endsWith(":")) {
        throw new IllegalArgumentException("Invalid value part for property " + TOPOLOGY_AWARE_PROPERTY_KEY + ": " + value);
      }
      if (v.length == 1) {
        Set<TopologyAwareLoadBalancer.CloudPlacement> primary = allowedPlacements.computeIfAbsent(PRIMARY_PLACEMENTS, k -> new HashSet<>());
        populatePlacementSet(v[0], primary);
      } else {
        int pref = Integer.valueOf(v[1]);
        if (pref == 1) {
          Set<TopologyAwareLoadBalancer.CloudPlacement> primary = allowedPlacements.get(PRIMARY_PLACEMENTS);
          if (primary == null) {
            primary = new HashSet<>();
            allowedPlacements.put(PRIMARY_PLACEMENTS, primary);
          }
          populatePlacementSet(v[0], primary);
        } else if (pref > 1 && pref <= MAX_PREFERENCE_VALUE) {
          Set<TopologyAwareLoadBalancer.CloudPlacement> fallbackPlacements = allowedPlacements.get(pref);
          if (fallbackPlacements == null) {
            fallbackPlacements = new HashSet<>();
            allowedPlacements.put(pref, fallbackPlacements);
          }
          populatePlacementSet(v[0], fallbackPlacements);
        } else {
          throw new IllegalArgumentException("Invalid preference value for property " + TOPOLOGY_AWARE_PROPERTY_KEY + ": " + value);
        }
      }
    }
  }

  @Override
  protected void clearHostIPLists() {
    super.clearHostIPLists();
    for (ArrayList<String> hosts : fallbackPrivateIPs.values()) {
      hosts.clear();
    }
    for (ArrayList<String> publicIPs : fallbackPublicIPs.values()) {
      publicIPs.clear();
    }
  }

  @Override
  protected void updateCurrentHostList(ArrayList<String> currentPrivateIps, String host,
      String publicIp, String cloud, String region, String zone) {
    CloudPlacement cp = new CloudPlacement(cloud, region, zone);
    if (cp.isContainedIn(allowedPlacements.get(PRIMARY_PLACEMENTS))) {
      LOGGER.log(Level.FINE,
          getLoadBalancerType() + ": allowedPlacements set: "
              + allowedPlacements + " returned contains true for cp: " + cp);
      currentPrivateIps.add(host);
      if (!publicIp.trim().isEmpty()) {
        currentPublicIps.add(publicIp);
      }
    } else {
      for (Map.Entry<Integer, Set<CloudPlacement>> allowedCPs : allowedPlacements.entrySet()) {
        if (cp.isContainedIn(allowedCPs.getValue())) {
          LOGGER.fine("CloudPlacement " + cp + " is part of fallback level "
              + (allowedCPs.getKey() - 1));
          ArrayList<String> hosts = fallbackPrivateIPs.computeIfAbsent(allowedCPs.getKey(), k -> new ArrayList<>());
          hosts.add(host);
          if (!publicIp.trim().isEmpty()) {
            ArrayList<String> publicIPs = fallbackPublicIPs.computeIfAbsent(allowedCPs.getKey(), k -> new ArrayList<>());
            publicIPs.add(publicIp);
          }
          return;
        }
      }
      // Maintain the list of hosts which do not quality for any preference zone.
      // Use it as THE LAST fallback - the entire cluster nodes.
      ArrayList<String> remainingHosts = fallbackPrivateIPs.computeIfAbsent(REST_OF_CLUSTER, k -> new ArrayList<>());
      remainingHosts.add(host);
      ArrayList<String> remainingPublicIPs = fallbackPublicIPs.computeIfAbsent(REST_OF_CLUSTER, k -> new ArrayList<>());
      remainingPublicIPs.add(publicIp);
      LOGGER.log(Level.FINE,
          getLoadBalancerType() + ": allowedPlacements set: " + allowedPlacements
              + " returned contains false for cp: " + cp);
    }
  }

  @Override
  protected ArrayList<String> getPrivateOrPublicServers(ArrayList<String> privateHosts,
      ArrayList<String> publicHosts) {
    ArrayList<String> servers = super.getPrivateOrPublicServers(privateHosts, publicHosts);
    if (servers != null && !servers.isEmpty()) {
      return servers;
    }
    // If no servers are available in primary placements then attempt fallback nodes.
    for (int i = FIRST_FALLBACK; i <= MAX_PREFERENCE_VALUE; i++) {
      if (fallbackPrivateIPs.get(i) != null && !fallbackPrivateIPs.get(i).isEmpty()) {
        LOGGER.info("Attempting to connect servers in fallback level-" + (i - 1) + " ...");
        return super.getPrivateOrPublicServers(fallbackPrivateIPs.get(i), fallbackPublicIPs.get(i));
      }
    }
    // If nothing works out, let it fallback to entire cluster nodes
    boolean limitFallbackToGivenTKs = Boolean.getBoolean(EXPLICIT_FALLBACK_ONLY_KEY);
    if (limitFallbackToGivenTKs) {
      return servers;
    }
    if (fallbackPrivateIPs.get(REST_OF_CLUSTER) != null) {
      LOGGER.fine("Returning servers from rest of the cluster: "
          + fallbackPrivateIPs.get(REST_OF_CLUSTER));
    }
    return super.getPrivateOrPublicServers(fallbackPrivateIPs.get(REST_OF_CLUSTER),
        fallbackPublicIPs.get(REST_OF_CLUSTER));
  }

  protected String getLoadBalancerType() {
    return "TopologyAwareLoadBalancer";
  }

  static class CloudPlacement {
    private final String cloud;
    private final String region;
    private final String zone;

    CloudPlacement(String cloud, String region, String zone) {
      this.cloud = cloud;
      this.region = region;
      this.zone = zone;
    }

    public boolean isContainedIn(Set<CloudPlacement> set) {
      if (this.zone.equals("*")) {
        for (CloudPlacement cp : set) {
          if (cp.cloud.equalsIgnoreCase(this.cloud) && cp.region.equalsIgnoreCase(this.region)) {
            return true;
          }
        }
      } else {
        for (CloudPlacement cp : set) {
          if (cp.cloud.equalsIgnoreCase(this.cloud)
              && cp.region.equalsIgnoreCase(this.region)
              && (cp.zone.equalsIgnoreCase(this.zone) || cp.zone.equals("*"))) {
            return true;
          }
        }
      }
      return false;
    }

    public int hashCode() {
      return cloud.hashCode() ^ region.hashCode() ^ zone.hashCode();
    }

    public boolean equals(Object other) {
      boolean equal = false;
      LOGGER.log(Level.FINE, "equals called for this: " + this + " and other = " + other);
      if (other instanceof CloudPlacement) {
        CloudPlacement o = (CloudPlacement) other;
        equal = this.cloud.equalsIgnoreCase(o.cloud) &&
            this.region.equalsIgnoreCase(o.region) &&
            this.zone.equalsIgnoreCase(o.zone);
      }
      LOGGER.log(Level.FINE, "equals returning: " + equal);
      return equal;
    }

    public String toString() {
      return "Placement: " + cloud + "." + region + "." + zone;
    }
  }
}
