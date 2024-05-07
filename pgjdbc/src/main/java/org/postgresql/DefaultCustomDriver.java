package org.postgresql;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Properties;

import org.postgresql.core.QueryExecutor;
import org.postgresql.jdbc.PgConnection;
import static org.postgresql.Driver.*;

public class DefaultCustomDriver implements CustomDriver{

    /**
   * Create a connection from URL and properties. Always does the connection work in the current
   * thread without enforcing a timeout, regardless of any timeout specified in the properties.
   *
   * @param url the original URL
   * @param properties the parsed/defaulted connection properties
   * @return a new connection
   * @throws SQLException if the connection could not be made
   */
    @Override
    public Connection makeConnection(String url, Properties properties) throws SQLException {
    return new PgConnection(hostSpecs(properties), user(properties), database(properties),
        properties, url);
    }
    
    /**
   * <B>Note:</B> even though {@code Statement} is automatically closed when it is garbage
   * collected, it is better to close it explicitly to lower resource consumption.
   * The spec says that calling close on a closed connection is a no-op.
   *
   * {@inheritDoc}
   */
  @Override
  public void close(QueryExecutor queryExecutor) throws SQLException {
    //No op
  }
}
