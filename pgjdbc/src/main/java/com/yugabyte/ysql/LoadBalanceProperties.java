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

import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

public class LoadBalanceProperties {
  private static final String SIMPLE_LB = "simple";
  public static final String LOAD_BALANCE_PROPERTY_KEY = "load-balance";
  public static final String TOPOLOGY_AWARE_PROPERTY_KEY = "topology-keys";
  public static final String REFRESH_INTERVAL_KEY = "yb-servers-refresh-interval";
  /**
   * Can have value either true or false. Default is false.
   * true means stick to explicitly given placements for fallback, do not fallback to entire
   * cluster nodes. false means fallback to entire cluster nodes when nodes in explicit
   * placements are unavailable.
   */
  public static final String EXPLICIT_FALLBACK_ONLY_KEY = "fallback-to-topology-keys-only";
  /**
   * The default value should ideally match the interval at which the server-list is updated at
   * cluster side for yb_servers() function. Here, kept it 5 seconds which is not too high (30s) and
   * not too low (1s).
   */
  public static final int DEFAULT_FAILED_HOST_TTL_SECONDS = 5;
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
  public static final Map<String, LoadBalancer> CONNECTION_MANAGER_MAP =
      new HashMap<>();

  private static Map<LoadBalancerKey, LoadBalanceProperties> loadBalancePropertiesMap =
      new ConcurrentHashMap();
  private final String originalUrl;
  private final Properties originalProperties;
  private boolean hasLoadBalance;
  private final String ybURL;
  private String placements = null;
  private int refreshInterval = -1;
  private boolean explicitFallbackOnly;
  private boolean refreshIntervalSpecified;
  private boolean explicitFallbackOnlySpecified;

  public static LoadBalanceProperties getLoadBalanceProperties(String url, Properties properties) {
    LoadBalancerKey key = new LoadBalancerKey(url, properties);
    LoadBalanceProperties lbp = loadBalancePropertiesMap.get(key);
    if (lbp == null) {
      synchronized (LoadBalanceProperties.class) {
        lbp = loadBalancePropertiesMap.get(key);
        if (lbp == null) {
          lbp = new LoadBalanceProperties(url, properties);
          loadBalancePropertiesMap.put(key, lbp);
        }
      }
    }
    return lbp;
  }

  private LoadBalanceProperties(String origUrl, Properties origProperties) {
    originalUrl = origUrl;
    originalProperties = (Properties) origProperties.clone();
    ybURL = processURLAndProperties();
  }

  public String processURLAndProperties() {
    String[] urlParts = this.originalUrl.split("\\?");
    StringBuilder sb = new StringBuilder(urlParts[0]);
    if (urlParts.length == 2) {
      urlParts = urlParts[1].split(PROPERTY_SEP);
      String loadBalancerKey = LOAD_BALANCE_PROPERTY_KEY + EQUALS;
      String topologyKey = TOPOLOGY_AWARE_PROPERTY_KEY + EQUALS;
      String refreshIntervalKey = REFRESH_INTERVAL_KEY + EQUALS;
      String explicitFallbackOnlyKey = EXPLICIT_FALLBACK_ONLY_KEY;
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
            LOGGER.log(Level.WARNING, "No valid value provided for " + REFRESH_INTERVAL_KEY + ". " +
                "Ignoring it.");
            continue;
          }
          try {
            refreshInterval = Integer.parseInt(lbParts[1]);
            if (refreshInterval < 0 || refreshInterval > MAX_REFRESH_INTERVAL) {
              refreshInterval = DEFAULT_REFRESH_INTERVAL;
            } else {
              refreshIntervalSpecified = true;
            }
          } catch (NumberFormatException nfe) {
            refreshInterval = DEFAULT_REFRESH_INTERVAL;
          }
        } else if (part.startsWith(explicitFallbackOnlyKey)) {
          String[] lbParts = part.split(EQUALS);
          if (lbParts.length != 2) {
            continue;
          }
          String propValue = lbParts[1];
          if (propValue.equalsIgnoreCase("true")) {
            this.explicitFallbackOnly = true;
          }
          explicitFallbackOnlySpecified = true;
        } else {
          if (sb.toString().contains("?")) {
            sb.append(PROPERTY_SEP);
          } else {
            sb.append("?");
          }
          sb.append(part);
        }
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
      if (originalProperties.containsKey(REFRESH_INTERVAL_KEY)) {
        String propValue = originalProperties.getProperty(REFRESH_INTERVAL_KEY);
        try {
          refreshInterval = Integer.parseInt(propValue);
          if (refreshInterval < 0 || refreshInterval > MAX_REFRESH_INTERVAL) {
            refreshInterval = DEFAULT_REFRESH_INTERVAL;
          } else {
            refreshIntervalSpecified = true;
          }
        } catch (NumberFormatException nfe) {
          refreshInterval = DEFAULT_REFRESH_INTERVAL;
        }
      }
      if (originalProperties.containsKey(EXPLICIT_FALLBACK_ONLY_KEY)) {
        String propValue = originalProperties.getProperty(EXPLICIT_FALLBACK_ONLY_KEY);
        if (propValue.equalsIgnoreCase("true")) {
          explicitFallbackOnly = true;
        }
        explicitFallbackOnlySpecified = true;
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

  public boolean hasLoadBalance() {
    return hasLoadBalance;
  }

  public String getPlacements() {
    return placements;
  }

  public String getStrippedURL() {
    return ybURL;
  }

  public LoadBalancer getAppropriateLoadBalancer() {
    if (!hasLoadBalance) {
      throw new IllegalStateException(
          "This method is expected to be called only when load-balance is true");
    }
    // todo Find a better way to pass/update these properties. Currently, lb instance is
    //  singleton for a given placement, so cannot include these in it.
    if (refreshIntervalSpecified) {
      System.setProperty(REFRESH_INTERVAL_KEY, String.valueOf(refreshInterval));
    }
    LoadBalancer ld = null;
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
      String key = placements + "&" +  String.valueOf(explicitFallbackOnly).toLowerCase();
      ld = CONNECTION_MANAGER_MAP.get(key);
      if (ld == null) {
        synchronized (CONNECTION_MANAGER_MAP) {
          ld = CONNECTION_MANAGER_MAP.get(key);
          if (ld == null) {
            ld = new TopologyAwareLoadBalancer(placements, explicitFallbackOnly);
            CONNECTION_MANAGER_MAP.put(key, ld);
          }
        }
      }
    }
    return ld;
  }

  private static class LoadBalancerKey {
    private String url;
    private Properties properties;

    public LoadBalancerKey(String url, Properties properties) {
      this.url = url;
      this.properties = properties;
    }

    public boolean equals(Object other) {
      return other instanceof LoadBalancerKey &&
          url != null && url.equals(((LoadBalancerKey) other).url) &&
          properties != null &&
          properties.equals(((LoadBalancerKey) other).properties);
    }
  }
}
