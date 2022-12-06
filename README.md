# YugabyteDB JDBC Driver
This is a distributed JDBC driver for YugabyteDB SQL. This driver is based on the [PostgreSQL JDBC Driver](https://github.com/pgjdbc/pgjdbc).

## Features

This JDBC driver has the following features:

### Cluster Awareness to eliminate need for a load balancer

This driver adds a `YBClusterAwareDataSource` that requires only an initial _contact point_ for the YugabyteDB cluster, using which it discovers the rest of the nodes. Additionally, it automatically learns about the nodes being started/added or stopped/removed. Internally the driver keeps track of number of connections it has created to each server endpoint and every new connection request is connected to the least loaded server as per the driver's view.


### Topology Awareness to enable geo-distributed apps

This is similar to 'Cluster Awareness' but uses those servers which are part of a given set of geo-locations specified by _topology-keys_.

### Shard awareness for high performance

> **NOTE:** This feature is still in the design phase.

### Connection Properties added for load balancing

- _load-balance_   - It expects **true/false** as its possible values. In YBClusterAwareDataSource load balancing is true by default. However when using the DriverManager.getConnection() API the 'load-balance' property needs to be set to 'true'.
- _topology-keys_  - It takes a comma separated geo-location values. A single geo-location can be given as 'cloud.region.zone'. Multiple geo-locations too can be specified, separated by comma (`,`).
- _yb-servers-refresh-interval_ - Time interval, in seconds, between two attempts to refresh the information about cluster nodes. Default is 300. Valid values are integers between 0 and 600. Value 0 means refresh for each connection request. Any value outside this range is ignored and the default is used.

Please refer to the [Use the Driver](#Use the Driver) section for examples.

### Get the Driver

### From Maven

Either add the following lines to your maven project in pom.xml file.
```
<dependency>
  <groupId>com.yugabyte</groupId>
  <artifactId>jdbc-yugabytedb</artifactId>
  <version>42.3.0</version>
</dependency>
```

or you can visit to this link for the latest version of dependency: https://search.maven.org/artifact/com.yugabyte/jdbc-yugabytedb

### Build locally

0. Build environment

   gpgsuite needs to be present on the machine where build is performed.
   ```
   https://gpgtools.org/
   ```
   Please install gpg and create a key.

1. Clone this repository.

    ```
    git clone https://github.com/yugabyte/pgjdbc.git && cd pgjdbc
    ```

2. Build and install into your local maven folder.

    ```
     ./gradlew publishToMavenLocal -x test -x checkstyleMain
    ```

3. Finally, use it by adding the lines below to your project.

    ```xml
    <dependency>
        <groupId>com.yugabyte</groupId>
        <artifactId>jdbc-yugabytedb</artifactId>
        <version>42.3.0</version>
    </dependency> 
    ```
> **Note:** You need to have installed 2.7.2.0-b0 or above version of YugabyteDB on your system for load balancing to work.

## Use the Driver

- Passing new connection properties for load balancing in connection url or properties bag

  For uniform load balancing across all the server you just need to specify the _load-balance=true_ property in the url.
    ```
    String yburl = "jdbc:yugabytedb://127.0.0.1:5433/yugabyte?user=yugabyte&password=yugabyte&load-balance=true";
    DriverManager.getConnection(yburl);
    ```

  For specifying topology keys you need to set the additional property with a valid comma separated value.

    ```
    String yburl = "jdbc:yugabytedb://127.0.0.1:5433/yugabyte?user=yugabyte&password=yugabyte&load-balance=true&topology-keys=cloud1.region1.zone1,cloud1.region1.zone2";
    DriverManager.getConnection(yburl);
    ```

### Specifying fallback zones

  For topology-aware load balancing, you can now specify fallback placements too. This is not applicable for cluster-aware load balancing.
  Each placement value can be suffixed with a colon (`:`) followed by a preference value between 1 and 10.
  A preference value of `:1` means it is a primary placement. A preference value of `:2` means it is the first fallback placement and so on.
  If no preference value is provided, it is considered to be a primary placement (equivalent to one with preference value `:1`). Example given below.

```
String yburl = "jdbc:yugabytedb://127.0.0.1:5433/yugabyte?user=yugabyte&password=yugabyte&load-balance=true&topology-keys=cloud1.region1.zone1:1,cloud1.region1.zone2:2";

```

  You can also use `*` for specifying all the zones in a given region as shown below. This is not allowed for cloud or region values.

```
String yburl = "jdbc:yugabytedb://127.0.0.1:5433/yugabyte?user=yugabyte&password=yugabyte&load-balance=true&topology-keys=cloud1.region1.*:1,cloud1.region2.*:2";
```

  The driver attempts connection to servers in the first fallback placement(s) if it does not find any servers available in the primary placement(s). If no servers are available in the first fallback placement(s),
  then it attempts to connect to servers in the second fallback placement(s), if specified. This continues until the driver finds a server to connect to, else an error is returned to the application.
  And this repeats for each connection request.

- Create and setup the DataSource for uniform load balancing
  A datasource for Yugabyte has been added. It can be configured like this for load balancing behaviour.
    ```
    String jdbcUrl = "jdbc:yugabytedb://127.0.0.1:5433/yugabyte";
    YBClusterAwareDataSource ds = new YBClusterAwareDataSource();
    ds.setUrl(jdbcUrl);
    // If topology aware distribution to be enabled then
    ds.setTopologyKeys("cloud1.region1.zone1,cloud1.region2.zone2");
    // If you want to provide more endpoints to safeguard against even first connection failure
    // due to the possible unavailability of initial contact point:
    ds.setAdditionalEndpoints("127.0.0.2:5433,127.0.0.3:5433");

    Connection conn = ds.getConnection();
    ```

- Create and setup the DataSource with a popular pooling solution like Hikari

    ```
    Properties poolProperties = new Properties();
    poolProperties.setProperty("dataSourceClassName", "com.yugabyte.ysql.YBClusterAwareDataSource");
    poolProperties.setProperty("maximumPoolSize", 10);
    poolProperties.setProperty("dataSource.serverName", "127.0.0.1");
    poolProperties.setProperty("dataSource.portNumber", "5433");
    poolProperties.setProperty("dataSource.databaseName", "yugabyte");
    poolProperties.setProperty("dataSource.user", "yugabyte");
    poolProperties.setProperty("dataSource.password", "yugabyte");
    // If you want to provide additional end points
    String additionalEndpoints = "127.0.0.2:5433,127.0.0.3:5433,127.0.0.4:5433,127.0.0.5:5433";
    poolProperties.setProperty("dataSource.additionalEndpoints", additionalEndpoints);
    // If you want to load balance between specific geo locations using topology keys
    String geoLocations = "cloud1.region1.zone1,cloud1.region2.zone2";
    poolProperties.setProperty("dataSource.topologyKeys", geoLocations);

    poolProperties.setProperty("poolName", name);

    HikariConfig config = new HikariConfig(poolProperties);
    config.validate();
    HikariDataSource ds = new HikariDataSource(config);

    Connection conn = ds.getConnection();
    ```

    Note that the property `dataSource.additionalEndpoints`, if specified, should include the respective port numbers as
    shown above, specially if those are not the default port numbers (`5433`).

    Otherwise, when the driver needs to connect to any of these additional endpoints (when the primary endpoint
    specified via `serverName` is unavailable), it will use the default port number (`5433`) and not
    `dataSource.portNumber` to connect to them.
