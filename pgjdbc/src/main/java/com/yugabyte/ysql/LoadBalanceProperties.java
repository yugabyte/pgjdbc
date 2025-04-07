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
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

public class LoadBalanceProperties {
  public static final String LOAD_BALANCE_PROPERTY_KEY = "load-balance";
  public static final String TOPOLOGY_AWARE_PROPERTY_KEY = "topology-keys";
  public static final String REFRESH_INTERVAL_KEY = "yb-servers-refresh-interval";
  /**
   * The value can either be true or false. Default is false.
   * true means stick to explicitly given placements for fallback, and do not fall back to entire
   * cluster nodes. false means fall back to entire cluster nodes when nodes in explicit
   * placements are unavailable.
   */
  public static final String EXPLICIT_FALLBACK_ONLY_KEY = "fallback-to-topology-keys-only";
  /**
   * The driver marks a server as failed with a timestamp, when it cannot connect to it. Later,
   * whenever it refreshes the server list via yb_servers(), if it sees the failed server in the
   * response, it marks the server as UP only if the time specified via this property has elapsed
   * since the time it was last marked as a failed host.
   */
  public static final String FAILED_HOST_RECONNECT_DELAY_SECS_KEY = "failed-host-reconnect-delay-secs";
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
  public static final int MAX_FAILED_HOST_RECONNECT_DELAY_SECS = 60;

  private static final Logger LOGGER =
      Logger.getLogger("org.postgresql." + LoadBalanceProperties.class.getName());
  /* Topology/Cluster aware key to load balancer mapping. For uniform policy
   load-balance 'simple' to be used as KEY and for targeted topologies,
    <placements> value specified will be used as key
   */
  private static final Map<String, LoadBalancer> CONNECTION_MANAGER_MAP =
      new HashMap<>();
  private static final Map<LoadBalancerKey, LoadBalanceProperties> loadBalancePropertiesMap =
      new ConcurrentHashMap<>();
  private final String originalUrl;
  private final Properties originalProperties;

  /**
   * FOR TEST PURPOSE ONLY
   */
  static void clearConnectionManagerMap() {
    LOGGER.warning("Clearing CONNECTION_MANAGER_MAP for testing purposes");
    synchronized (CONNECTION_MANAGER_MAP) {
      CONNECTION_MANAGER_MAP.clear();
    }
  }

  public static LoadBalanceProperties getLoadBalanceProperties(LoadBalancerKey key) {
    LoadBalanceProperties lbp = loadBalancePropertiesMap.get(key);
    if (lbp == null) {
      synchronized (LoadBalanceProperties.class) {
        lbp = loadBalancePropertiesMap.get(key);
        if (lbp == null) {
          lbp = new LoadBalanceProperties(key);
          loadBalancePropertiesMap.put(key, lbp);
        }
      }
    }
    return lbp;
  }

  private LoadBalanceProperties(LoadBalancerKey key) {
    originalUrl = key.getUrl();
    originalProperties = (Properties) key.getProperties().clone();
  }

  public static ProcessedProperties processURLAndProperties(String url, Properties properties) {
    ProcessedProperties processedProperties = new ProcessedProperties();
    // Check properties bag also
    if (properties != null) {
      if (properties.containsKey(LOAD_BALANCE_PROPERTY_KEY)) {
        String propValue = properties.getProperty(LOAD_BALANCE_PROPERTY_KEY);
        processedProperties.setLoadBalance(getLoadBalanceValue(propValue));
      }
      if (properties.containsKey(TOPOLOGY_AWARE_PROPERTY_KEY)) {
        String propValue = properties.getProperty(TOPOLOGY_AWARE_PROPERTY_KEY);
        processedProperties.setPlacements(propValue);
      }
      if (properties.containsKey(REFRESH_INTERVAL_KEY)) {
        processedProperties.setRefreshInterval(parseAndGetValue(properties.getProperty(REFRESH_INTERVAL_KEY),
            DEFAULT_REFRESH_INTERVAL, MAX_REFRESH_INTERVAL));
      }
      if (properties.containsKey(EXPLICIT_FALLBACK_ONLY_KEY)) {
        String propValue = properties.getProperty(EXPLICIT_FALLBACK_ONLY_KEY);
        if (propValue.equalsIgnoreCase("true")) {
          processedProperties.setExplicitFallbackOnly(true);
        } else {
          processedProperties.setExplicitFallbackOnly(false);
        }
      }
      if (properties.containsKey(FAILED_HOST_RECONNECT_DELAY_SECS_KEY)) {
        processedProperties.setFailedHostReconnectDelaySecs(parseAndGetValue(properties.getProperty(FAILED_HOST_RECONNECT_DELAY_SECS_KEY),
            DEFAULT_FAILED_HOST_TTL_SECONDS, MAX_FAILED_HOST_RECONNECT_DELAY_SECS));
      }
    }
    return processedProperties;
  }

  public static LoadBalanceService.LoadBalanceType getLoadBalanceValue(String value) {
    if (value == null) {
      return LoadBalanceService.LoadBalanceType.FALSE;
    }
    switch (value.toLowerCase(Locale.ROOT)) {
    case "true":
    case "any":
      return LoadBalanceService.LoadBalanceType.ANY;
    case "prefer-primary":
      return LoadBalanceService.LoadBalanceType.PREFER_PRIMARY;
    case "prefer-rr":
      return LoadBalanceService.LoadBalanceType.PREFER_RR;
    case "only-primary":
      return LoadBalanceService.LoadBalanceType.ONLY_PRIMARY;
    case "only-rr":
      return LoadBalanceService.LoadBalanceType.ONLY_RR;
    case "false":
      return LoadBalanceService.LoadBalanceType.FALSE;
    default:
      LOGGER.warning("Invalid value for load-balance: " + value + ", ignoring it.");
      return LoadBalanceService.LoadBalanceType.FALSE;
    }
  }

  private static int parseAndGetValue(String propValue, int defaultValue, int maxValue) {
    try {
      int value = Integer.parseInt(propValue);
      if (value < 0 || value > maxValue) {
        LOGGER.warning("Provided value (" + value + ") is outside the permissible range,"
            + " using the default value instead");
        return defaultValue;
      }
      return value;
    } catch (NumberFormatException nfe) {
      LOGGER.warning("Provided value (" + propValue + ") is invalid, using the default value "
          + "instead");
      return defaultValue;
    }
  }

  public String getOriginalURL() {
    return originalUrl;
  }

  public Properties getOriginalProperties() {
    return originalProperties;
  }

  public static boolean isLoadBalanceEnabled(LoadBalancerKey key) {
    return (getLoadBalanceValue(key.getProperties().getProperty(LOAD_BALANCE_PROPERTY_KEY)) != LoadBalanceService.LoadBalanceType.FALSE);
  }

  public static class LoadBalancerKey {
    private String url;
    private Properties properties;

    public String getUrl() {
      return url;
    }

    public Properties getProperties() {
      return properties;
    }

    public LoadBalancerKey(String url, Properties properties) {
      this.url = url;
      this.properties = properties;
    }

    @Override
    public int hashCode() {
      final int prime = 31;
      int result = 1;
      result = prime * result + ((url == null) ? 0 : url.hashCode());
      result = prime * result + ((properties == null) ? 0 : properties.hashCode());
      return result;
    }

    public boolean equals(Object other) {
      return other instanceof LoadBalancerKey &&
          url != null && url.equals(((LoadBalancerKey) other).url) &&
          properties != null &&
          properties.equals(((LoadBalancerKey) other).properties);
    }
  }

  public static class ProcessedProperties {
    private String placements;
    private int refreshInterval;
    private boolean explicitFallbackOnly;
    private int failedHostReconnectDelaySecs;
    private LoadBalanceService.LoadBalanceType loadBalance;

    public int getFailedHostReconnectDelaySecs() {
      return failedHostReconnectDelaySecs;
    }

    public void setFailedHostReconnectDelaySecs(int failedHostReconnectDelaySecs) {
      this.failedHostReconnectDelaySecs = failedHostReconnectDelaySecs;
    }

    public String getPlacements() {
      return placements;
    }

    public void setPlacements(String placements) {
      this.placements = placements;
    }

    public int getRefreshInterval() {
      return refreshInterval;
    }

    public void setRefreshInterval(int refreshInterval) {
      this.refreshInterval = refreshInterval;
    }

    public boolean isExplicitFallbackOnly() {
      return explicitFallbackOnly;
    }

    public void setExplicitFallbackOnly(boolean explicitFallbackOnly) {
      this.explicitFallbackOnly = explicitFallbackOnly;
    }

    public LoadBalanceService.LoadBalanceType getLoadBalance() {
      return loadBalance;
    }

    public void setLoadBalance(LoadBalanceService.LoadBalanceType loadBalance) {
      this.loadBalance = loadBalance;
    }
  }
}
