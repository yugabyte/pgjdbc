package com.yugabyte.ysql;

import static org.postgresql.util.internal.Nullness.castNonNull;

import org.postgresql.core.PGStream;
import org.postgresql.jdbc.PgConnection;
import org.postgresql.util.GT;
import org.postgresql.util.HostSpec;
import org.postgresql.util.PSQLException;
import org.postgresql.util.PSQLState;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.security.cert.CertificateParsingException;
import java.security.cert.X509Certificate;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.*;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.SSLPeerUnverifiedException;
import javax.net.ssl.SSLSession;

public class YBManagedHostnameVerifier implements HostnameVerifier {

  private static final Logger LOGGER = Logger.getLogger(YBManagedHostnameVerifier.class.getName());
  protected static final String GET_SERVERS_QUERY = "select * from yb_servers()";
  protected Boolean useHostColumn = null;




  private static final int TYPE_DNS_NAME = 2;
  private static final int TYPE_IP_ADDRESS = 7;
  private final Properties originalProperties;
  protected ArrayList<String> currentPublicIps = new ArrayList<>();
  protected Map<String, String> hostPortMap = new HashMap<>();
  protected Map<String, String> hostPortMapPublic = new HashMap<>();
  private final PGStream stream;
  private static Connection controlConnection = null;

  public YBManagedHostnameVerifier(Properties props, PGStream stream){
    this.originalProperties = props;
    this.stream= stream;

  }

  @Override
  public boolean verify(String hostname, SSLSession session) {

    X509Certificate[] peerCerts;
    try {
      peerCerts = (X509Certificate[]) session.getPeerCertificates();
    } catch (SSLPeerUnverifiedException e) {
      LOGGER.log(Level.SEVERE,
          GT.tr("Unable to parse X509Certificate for hostname {0}", hostname), e);
      return false;
    }
    if (peerCerts == null || peerCerts.length == 0) {
      LOGGER.log(Level.SEVERE,
          GT.tr("No certificates found for hostname {0}", hostname));
      return false;
    }

    X509Certificate serverCert = peerCerts[0];

    // Check for Subject Alternative Names (see RFC 6125)

    Collection<List<?>> subjectAltNames;
    try {
      subjectAltNames = serverCert.getSubjectAlternativeNames();
      if (subjectAltNames == null) {
        subjectAltNames = Collections.emptyList();
      }
    } catch (CertificateParsingException e) {
      LOGGER.log(Level.SEVERE,
          GT.tr("Unable to parse certificates for hostname {0}", hostname), e);
      return false;
    }

    boolean anyDnsSan = false;
    String san = null;
    /*
     * Each item in the SAN collection is a 2-element list.
     * See {@link X509Certificate#getSubjectAlternativeNames}
     * The first element in each list is a number indicating the type of entry.
     */
    for (List<?> sanItem : subjectAltNames) {
      if (sanItem.size() != 2) {
        continue;
      }
      Integer sanType = (Integer) sanItem.get(0);
      if (sanType == null) {
        // just in case
        continue;
      }
      if (sanType != TYPE_IP_ADDRESS && sanType != TYPE_DNS_NAME) {
        continue;
      }
      san = (String) sanItem.get(1);
      if (sanType == TYPE_IP_ADDRESS && san != null && san.startsWith("*")) {
        // Wildcards should not be present in the IP Address field
        continue;
      }
      anyDnsSan |= sanType == TYPE_DNS_NAME;
    }

    originalProperties.setProperty("PGHOST", san);
    HostSpec[] hspec = hostSpecs(this.originalProperties);
    try {
      if (controlConnection == null) {
        controlConnection = new PgConnection(hspec, originalProperties, null);
      }
    } catch (SQLException e) {
      throw new RuntimeException(e);
    }

    ArrayList<String> hostlist = new ArrayList<>();
    try {
      hostlist = getCurrentServers(controlConnection);
    } catch (SQLException e) {
      throw new RuntimeException(e);
    }
    if (hostlist.contains(hostname)) {
      return true;
    }
    else {
      return false;
    }


  }

  private ArrayList<String> getCurrentServers(Connection conn) throws SQLException {
    Statement st = conn.createStatement();

    ResultSet rs = st.executeQuery(GET_SERVERS_QUERY);
    ArrayList<String> currentPrivateIps = new ArrayList<>();
    String hostConnectedTo = ((PgConnection) conn).getQueryExecutor().getHostSpec().getHost();
    InetAddress hostConnectedInetAddr;

    boolean isIpv6Addresses = hostConnectedTo.contains(":");
    if (isIpv6Addresses) {
      hostConnectedTo = hostConnectedTo.replace("[", "").replace("]", "");
    }

    try {
      hostConnectedInetAddr = InetAddress.getByName(hostConnectedTo);
    } catch (UnknownHostException e) {
      // This is totally unexpected. As the connection is already created on this host
      throw new PSQLException(GT.tr("Unexpected UnknownHostException for ${0} ", hostConnectedTo),
          PSQLState.UNKNOWN_STATE, e);
    }

    currentPublicIps.clear();
    while (rs.next()) {
      String host = rs.getString("host");
      String publicHost = rs.getString("public_ip");
      String port = rs.getString("port");
      String cloud = rs.getString("cloud");
      String region = rs.getString("region");
      String zone = rs.getString("zone");
      hostPortMap.put(host, port);
      hostPortMapPublic.put(publicHost, port);

      currentPrivateIps.add(host);
      if (!publicHost.trim().isEmpty()) {
        currentPublicIps.add(publicHost);
      }

      InetAddress hostInetAddr;
      InetAddress publicHostInetAddr;
      try {
        hostInetAddr = InetAddress.getByName(host);
      } catch (UnknownHostException e) {
        // set the hostInet to null
        hostInetAddr = null;
      }
      try {
        publicHostInetAddr = !publicHost.isEmpty()
            ? InetAddress.getByName(publicHost) : null;
      } catch (UnknownHostException e) {
        // set the publicHostInetAddr to null
        publicHostInetAddr = null;
      }
      if (useHostColumn == null) {
        if (hostConnectedInetAddr.equals(hostInetAddr)) {
          useHostColumn = Boolean.TRUE;
        } else if (hostConnectedInetAddr.equals(publicHostInetAddr)) {
          useHostColumn = Boolean.FALSE;
        }
      }
    }

    if (useHostColumn == null) {
      if (currentPublicIps.isEmpty()) {
        useHostColumn = Boolean.TRUE;
      }

      return currentPrivateIps;
    }
    ArrayList<String> currentHosts = useHostColumn ? currentPrivateIps : currentPublicIps;
    return currentHosts;
  }

  private static HostSpec[] hostSpecs(Properties props) {
    String[] hosts = castNonNull(props.getProperty("PGHOST")).split(",");
    String[] ports = castNonNull(props.getProperty("PGPORT")).split(",");
    String localSocketAddress = props.getProperty("localSocketAddress");
    HostSpec[] hostSpecs = new HostSpec[hosts.length];
    for (int i = 0; i < hostSpecs.length; ++i) {
      hostSpecs[i] = new HostSpec(hosts[i], Integer.parseInt(ports[i]), localSocketAddress);
    }
    return hostSpecs;
  }
}
