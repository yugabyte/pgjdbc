package org.postgresql;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Properties;

/**
 * An interface for any custom driver to implement. connect() invokes the
 * implemented method while processing a new connection request.
 */
public interface CustomDriver {

 /**
   * Create a connection from URL and properties. Always does the connection work in the current
   * thread without enforcing a timeout, regardless of any timeout specified in the properties.
   *
   * @param url the original URL
   * @param properties the parsed/defaulted connection properties
   * @return a new connection
   * @throws SQLException if the connection could not be made
   */
    Connection makeConnection(String url, Properties properties) throws SQLException;

}
