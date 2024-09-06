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

import org.postgresql.PGProperty;
import org.postgresql.ds.PGSimpleDataSource;

public class YBClusterAwareDataSource extends PGSimpleDataSource {

  public YBClusterAwareDataSource() {
    setLoadBalance("true");
  }

  private String additionalEndPoints;

  /**
   * @param value load balance value
   * @see PGProperty#YB_LOAD_BALANCE
   */
  public void setLoadBalance(String value) {
    PGProperty.YB_LOAD_BALANCE.set(properties, value);
  }

  /**
   * @return load balance value
   * @see PGProperty#YB_LOAD_BALANCE
   */
  public boolean getLoadBalance() {
    return PGProperty.YB_LOAD_BALANCE.getBoolean(properties);
  }

  public void setYbServersRefreshInterval(String value) {
    PGProperty.YB_SERVERS_REFRESH_INTERVAL.set(properties, value);
  }

  /**
   * @return yb_servers() refresh interval in seconds
   * @see PGProperty#YB_SERVERS_REFRESH_INTERVAL
   */
  public int getYbServersRefreshInterval() {
    return PGProperty.YB_SERVERS_REFRESH_INTERVAL.getIntNoCheck(properties);
  }

  public void setTopologyKeys(String value) {
    PGProperty.YB_TOPOLOGY_KEYS.set(properties, value);
  }

  /**
   * @return topology keys
   * @see PGProperty#YB_TOPOLOGY_KEYS
   */
  public String getTopologyKeys() {
    return PGProperty.YB_TOPOLOGY_KEYS.get(properties);
  }

  /**
   * @param value limit fallback to nodes in topology keys only
   * @see PGProperty#YB_FALLBACK_TO_TOPOLOGY_KEYS_ONLY
   */
  public void setFallbackToTopologyKeysOnly(String value) {
    PGProperty.YB_FALLBACK_TO_TOPOLOGY_KEYS_ONLY.set(properties, value);
  }

  /**
   * @return boolean
   * @see PGProperty#YB_FALLBACK_TO_TOPOLOGY_KEYS_ONLY
   */
  public boolean isFallbackToTopologyKeysOnly() {
    return PGProperty.YB_FALLBACK_TO_TOPOLOGY_KEYS_ONLY.getBoolean(properties);
  }

  public void setFailedHostReconnectDelaySecs(String value) {
    PGProperty.YB_FAILED_HOST_RECONNECT_DELAY_SECS.set(properties, value);
  }

  /**
   * @return delay in seconds
   * @see PGProperty#YB_FAILED_HOST_RECONNECT_DELAY_SECS
   */
  public int getFailedHostReconnectDelaySecs() {
    return PGProperty.YB_FAILED_HOST_RECONNECT_DELAY_SECS.getIntNoCheck(properties);
  }

  // additionalEndpoints
  public void setAdditionalEndpoints(String value) {
    this.additionalEndPoints = value;
  }

  public String getAdditionalEndPoints() {
    return additionalEndPoints;
  }

  public String getDescription() {
    return "YB cluster-aware DataSource from YugabyteDB JDBC Driver (YSQL)";
  }
}
