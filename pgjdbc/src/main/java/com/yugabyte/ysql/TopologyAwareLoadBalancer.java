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
import java.util.logging.Level;

public class TopologyAwareLoadBalancer extends ClusterAwareLoadBalancer {
  private final String placements;
  private final Set<CloudPlacement> allowedPlacements;
  private ArrayList<Set<CloudPlacement>> fallbackPlacements;
  private ArrayList<ArrayList<String>> fallbackPrivateIPs;
  private ArrayList<ArrayList<String>> fallbackPublicIPs;
  private final byte PRIMARY = -1;
  private byte currentPlacementLevel = PRIMARY;

  public TopologyAwareLoadBalancer(String placementvalues, String[] fallbackPlacements) {
    placements = placementvalues;
    allowedPlacements = new HashSet<>();
    populatePlacementSet(placements, allowedPlacements);
    if (fallbackPlacements != null && fallbackPlacements.length > 0) {
      populateFallbackPlacementSet(fallbackPlacements);
    }
  }

  protected String loadBalancingNodes() {
    return placements;
  }

  private void populatePlacementSet(String placements, Set<CloudPlacement> allowedPlacements) {
    String[] placementstrings = placements.split(",");
    for (String pl : placementstrings) {
      String[] placementParts = pl.split("\\.");
      if (placementParts.length != 3) {
        // Log a warning and return.
        // todo This should ideally happen earlier so that we may fallback to non-load-balanced behaviour.
        LOGGER.log(Level.WARNING, getLoadBalancerType() + ": "
            + "Ignoring malformed topology-key property value: " + pl);
        continue;
      }
      CloudPlacement cp = new CloudPlacement(
          placementParts[0], placementParts[1], placementParts[2]);
      LOGGER.log(Level.FINE, getLoadBalancerType() + ": Adding placement " + cp + " to allowed "
          + "list");
      allowedPlacements.add(cp);
    }
  }

  private void populateFallbackPlacementSet(String[] fallbackLevels) {
    fallbackPlacements = new ArrayList<Set<CloudPlacement>>(fallbackLevels.length);
    for (String fl : fallbackLevels) {
      Set<CloudPlacement> allowedPlacements = new HashSet<CloudPlacement>();
      populatePlacementSet(fl, allowedPlacements);
      fallbackPlacements.add(allowedPlacements);
    }
    fallbackPrivateIPs = new ArrayList<ArrayList<String>>(fallbackPlacements.size());
    fallbackPublicIPs = new ArrayList<ArrayList<String>>(fallbackPlacements.size());
    for (int i = 0; i < fallbackPlacements.size(); i++) {
      fallbackPrivateIPs.add(new ArrayList<String>());
      fallbackPublicIPs.add(new ArrayList<String>());
    }
  }

  @Override
  protected void updateCurrentHostList(ArrayList<String> currentPrivateIps, String host,
      String publicIp, String cloud, String region, String zone) {
    CloudPlacement cp = new CloudPlacement(cloud, region, zone);
    if (allowedPlacements.contains(cp)) {
      LOGGER.log(Level.FINE,
          getLoadBalancerType() + ": allowedPlacements set: "
              + allowedPlacements + " returned contains true for cp: " + cp);
      currentPrivateIps.add(host);
      if (!publicIp.trim().isEmpty()) {
        currentPublicIps.add(publicIp);
      }
    } else {
      if (fallbackPlacements != null && !fallbackPlacements.isEmpty()) {
        for (int i = 0; i < fallbackPlacements.size(); i++) {
          Set<CloudPlacement> pl = fallbackPlacements.get(i);
          if (pl.contains(cp)) {
            fallbackPrivateIPs.get(i).add(host);
            if (!publicIp.trim().isEmpty()) {
              fallbackPublicIPs.get(i).add(publicIp);
            }
            return;
          }
        }
      }
      LOGGER.log(Level.FINE,
          getLoadBalancerType() + ": allowedPlacements set: " + allowedPlacements
              + " returned contains false for cp: " + cp);
    }
  }

  @Override
  protected boolean checkFallback() {
    return !fallbackPlacements.isEmpty();
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

  static class FallbackDetails {
    private Set<CloudPlacement> placements;
    private ArrayList<String> currentPrivateIPs;
    private ArrayList<String> currentPublicIPs;

    public Set<CloudPlacement> getPlacements() {
      return placements;
    }

    public ArrayList<String> getCurrentPrivateIPs() {
      return currentPrivateIPs;
    }

    public ArrayList<String> getCurrentPublicIPs() {
      return currentPublicIPs;
    }
  }
}
