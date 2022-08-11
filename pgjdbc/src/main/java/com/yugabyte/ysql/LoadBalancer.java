package com.yugabyte.ysql;

import java.sql.Connection;
import java.sql.SQLException;

public interface LoadBalancer {

  public String getLoadBalancerType();
  public boolean needsRefresh();
  public boolean refresh(Connection conn) throws SQLException;

}
