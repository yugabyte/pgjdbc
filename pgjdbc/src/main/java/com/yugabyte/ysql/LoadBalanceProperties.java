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
import java.util.logging.Logger;

public class LoadBalanceProperties {
  private static final String SIMPLE_LB = "simple";
  public static final String LOAD_BALANCE_PROPERTY_KEY = "load-balance";
  public static final String TOPOLOGY_AWARE_PROPERTY_KEY = "topology-keys";
  public static final String REFRESH_INTERVAL_KEY = "yb-servers-refresh-interval";
  private static final String PROPERTY_SEP = "&";
  private static final String EQUALS = "=";
  public static final String LOCATIONS_DELIMITER = ",";
  public static final String PREFERENCE_DELIMITER = ":";
  public static final int MAX_PREFERENCE_VALUE = 10;
  public static final int DEFAULT_REFRESH_INTERVAL = 300;
  public static final int MAX_REFRESH_INTERVAL = 600;


  private static final Logger LOGGER = Logger.getLogger("org.postgresql.Driver");
  /* Topology/Cluster aware key to load balancer mapping. For uniform policy
   load-balance 'simple' to be used as KEY and for targeted topologies,
    <placements> value specified will be used as key
   */
  public static final Map<String, ClusterAwareLoadBalancer> CONNECTION_MANAGER_MAP =
      new HashMap<>();

  private final String originalUrl;
  private final Properties originalProperties;
  private final Properties strippedProperties;
  private boolean hasLoadBalance;
  private final String ybURL;
  private String placements = null;
  private int refreshInterval = -1;
//   private Map<Integer, Set<TopologyAwareLoadBalancer.CloudPlacement>> fallbackPlacements = null;

  public LoadBalanceProperties(String origUrl, Properties origProperties) {
    originalUrl = origUrl;
    originalProperties = origProperties;
    strippedProperties = (Properties) origProperties.clone();
    strippedProperties.remove(LOAD_BALANCE_PROPERTY_KEY);
    strippedProperties.remove(TOPOLOGY_AWARE_PROPERTY_KEY);
    ybURL = processURLAndProperties();
  }

  public String processURLAndProperties() {
    String[] urlParts = this.originalUrl.split("\\?");
    if (urlParts.length != 2) return this.originalUrl;
    StringBuilder sb = new StringBuilder(urlParts[0]);
    urlParts = urlParts[1].split(PROPERTY_SEP);
    String loadBalancerKey = LOAD_BALANCE_PROPERTY_KEY + EQUALS;
    String topologyKey = TOPOLOGY_AWARE_PROPERTY_KEY + EQUALS;
    String refreshIntervalKey = REFRESH_INTERVAL_KEY + EQUALS;
    for (String part : urlParts) {
      if (part.startsWith(loadBalancerKey)) {
        String[] lbParts = part.split(EQUALS);
        if (lbParts.length < 2) {
          LOGGER.log(Level.WARNING, "No value provided for load balance property. Ignoring it.");
          continue;
        }
        String propValue = lbParts[1];
        if (propValue.equalsIgnoreCase("true")) {
          this.hasLoadBalance = true;
        }
      } else if (part.startsWith(topologyKey)) {
        String[] lbParts = part.split(EQUALS);
        if (lbParts.length != 2) {
          LOGGER.log(Level.WARNING, "No valid value provided for topology keys. Ignoring it.");
          continue;
        }
        placements = lbParts[1];
      } else if (part.startsWith(refreshIntervalKey)) {
        String[] lbParts = part.split(EQUALS);
        if (lbParts.length != 2) {
          LOGGER.log(Level.WARNING, "No valid value provided for " + REFRESH_INTERVAL_KEY + ". Ignoring it.");
          continue;
        }
        try {
          refreshInterval = Integer.parseInt(lbParts[1]);
          if (refreshInterval < 0 || refreshInterval > MAX_REFRESH_INTERVAL) {
            refreshInterval = -1;
          }
        } catch (NumberFormatException nfe) {
          refreshInterval = -1;
        }
        if (refreshInterval == -1) {
//           String interval = System.getProperty("", "300");
//           refreshInterval = Integer.parseInt(interval);
//           if (refreshInterval < 0 || refreshInterval > 600) {
            refreshInterval = DEFAULT_REFRESH_INTERVAL;
//           }
        }
      } else {
        if (sb.toString().contains("?")) {
          sb.append(PROPERTY_SEP);
        } else {
          sb.append("?");
        }
        sb.append(part);
      }
    }
    // Check properties bag also
    if (originalProperties != null) {
      if (originalProperties.containsKey(LOAD_BALANCE_PROPERTY_KEY)) {
        String propValue = originalProperties.getProperty(LOAD_BALANCE_PROPERTY_KEY);
        if (propValue.equalsIgnoreCase("true")) {
          hasLoadBalance = true;
        }
      }
      if (originalProperties.containsKey(TOPOLOGY_AWARE_PROPERTY_KEY)) {
        String propValue = originalProperties.getProperty(TOPOLOGY_AWARE_PROPERTY_KEY);
        placements = propValue;
      }
    }
    return sb.toString();
  }

  public String getOriginalURL() {
    return originalUrl;
  }

  public Properties getOriginalProperties() {
    return originalProperties;
  }

  public Properties getStrippedProperties() {
    return strippedProperties;
  }

  public boolean hasLoadBalance() {
    return hasLoadBalance;
  }

  public String getPlacements() {
    return placements;
  }

  public boolean hasFallback() {
//     return fallbackPlacements != null && fallbackPlacements.size() > 0;
    // todo do we need it in this class?
    return false;
  }

  public String getStrippedURL() {
    return ybURL;
  }

  public ClusterAwareLoadBalancer getAppropriateLoadBalancer() {
    if (!hasLoadBalance) {
      throw new IllegalStateException(
          "This method is expected to be called only when load-balance is true");
    }
    ClusterAwareLoadBalancer ld = null;
    if (placements == null) {
      // return base class conn manager.
      ld = CONNECTION_MANAGER_MAP.get(SIMPLE_LB);
      if (ld == null) {
        synchronized (CONNECTION_MANAGER_MAP) {
          ld = CONNECTION_MANAGER_MAP.get(SIMPLE_LB);
          if (ld == null) {
            ld = ClusterAwareLoadBalancer.getInstance(refreshInterval);
            CONNECTION_MANAGER_MAP.put(SIMPLE_LB, ld);
          }
        }
      }
    } else {
      ld = CONNECTION_MANAGER_MAP.get(placements);
      if (ld == null) {
        synchronized (CONNECTION_MANAGER_MAP) {
          ld = CONNECTION_MANAGER_MAP.get(placements);
          if (ld == null) {
//             Set<TopologyAwareLoadBalancer.CloudPlacement> preferred = new HashSet<>();
//             Map<Integer, Set<TopologyAwareLoadBalancer.CloudPlacement>> fallbacks = new HashMap<>();
//             parseGeoLocations(preferred, fallbacks);
            ld = new TopologyAwareLoadBalancer(placements);
            CONNECTION_MANAGER_MAP.put(placements, ld);
          }
        }
      }
    }
    return ld;
  }
}
