package com.yugabyte.ysql;

import org.postgresql.ssl.PGjdbcHostnameVerifier;

import java.util.Properties;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.SSLSession;

/**
 * Hostname verifier for clusters whose nodes share a single certificate issued for the cluster
 * endpoint rather than for the individual node addresses - YugabyteDB Aeon, whose certificate
 * carries one wildcard SAN of the form {@code *.<cluster-uuid>.aws.ybdb.io}.
 *
 * <p>With load balancing enabled the driver connects to node addresses returned by
 * {@code yb_servers()}, which such a certificate does not cover, so the standard verifier rejects
 * them and {@code sslmode=verify-full} cannot be used. This verifier accepts a node when the
 * certificate it presents is valid for the endpoint the user configured in the connection URL,
 * which load balancing stashed in {@link LoadBalanceProperties#ENDPOINT_HOST_KEY} before replacing
 * it. The guarantee is the same one {@code verify-full} gives when connecting to the endpoint
 * directly: the peer must present a certificate that chains to the configured
 * {@code sslrootcert} and is valid for the name the user asked for.
 *
 * <p>Deployments that issue a separate certificate per node (YugabyteDB Anywhere, on-premises)
 * are handled by the first check, which is plain {@link PGjdbcHostnameVerifier} against the
 * address actually dialed.
 */
public class YBManagedHostnameVerifier implements HostnameVerifier {

  private static final Logger LOGGER =
      Logger.getLogger("org.postgresql." + YBManagedHostnameVerifier.class.getName());

  private final Properties properties;

  public YBManagedHostnameVerifier(Properties properties) {
    this.properties = properties;
  }

  @Override
  public boolean verify(String hostname, SSLSession session) {

    String endpointHost = properties.getProperty(LoadBalanceProperties.ENDPOINT_HOST_KEY);
    if (LOGGER.isLoggable(Level.FINE)) {
      LOGGER.log(Level.FINE, "verify() called for host {0}, cluster endpoint {1}",
          new Object[]{hostname,
              endpointHost == null ? "<none - host is from the connection URL>" : endpointHost});
    }

    // Per-node certificates: the certificate covers the address we dialed.
    if (PGjdbcHostnameVerifier.INSTANCE.verify(hostname, session)) {
      LOGGER.log(Level.INFO,
          "Server name validation passed for host {0} against the certificate it presented",
          hostname);
      return true;
    }

    if (endpointHost == null || endpointHost.equalsIgnoreCase(hostname)) {
      LOGGER.log(Level.SEVERE,
          "Server name validation failed for host {0}: the certificate it presented is not valid "
              + "for that name, and there is no other name to check ({1})",
          new Object[]{hostname,
              endpointHost == null
                  ? "no cluster endpoint recorded - host came from the connection URL"
                  : "the cluster endpoint is the same host"});
      return false;
    }

    // Cluster-wide certificate: verify against the endpoint from the connection URL. Wildcard
    // matching follows RFC 6125 and is implemented by PGjdbcHostnameVerifier.
    if (PGjdbcHostnameVerifier.INSTANCE.verify(endpointHost, session)) {
      LOGGER.log(Level.INFO,
          "Server name validation passed for node {0} against cluster endpoint {1}",
          new Object[]{hostname, endpointHost});
      return true;
    }

    LOGGER.log(Level.SEVERE,
        "Server name validation failed for node {0}: the certificate it presented is valid for "
            + "neither {0} nor the cluster endpoint {1}",
        new Object[]{hostname, endpointHost});
    return false;
  }
}
