solr-undertow
=============

Solr running in standalone server - High Performance, fast, easy, standalone deployment.  Requires JDK 1.7 or newer.

Releases [are available here](https://github.com/bremeld/solr-undertow/releases) on GitHub.

This application launches a Solr WAR file as a standalone server running a high performance HTTP front-end based on [undertow.io](http://undertow.io) (the engine behind WildFly, the new JBoss).  It has no features of an application server, does nothing more than load Solr servlets and also service the Admin UI.  It is production-quality for a stand-alone Solr server.

Usage is simple, you only need a configuration file and a Solr WAR:

```sh
bin/solr-undertow <configurationFile>
```

The configuration file is on the JSON like [HOCON format](https://github.com/typesafehub/config/blob/master/HOCON.md) and loaded using [TypeSafe Config](https://github.com/typesafehub/config).  So any features it supports are supported here.

A configuration file must minimally contain these settings:

```json
solr.undertow: {
  solrHome: "./solr-home"
  solrLogs: "./solr-logs"
  tempDir: "./solr-temp"
  solrVersion: "4.4.0"
  solrWarFile: ./solr-wars/solr-${solr.undertow.solrVersion}.war
}
```

In this configuration `solrHome` contains either `conf` subdirectory with a normal Solr standalone configuration.  Or contains `solr.xml` and `zoo.cfg` for a SolrCloud configuration.

The defaults, and all configuration options can be seen in the configuration defaults file.  Which include `httpClusterPort: 8983`, the default server port.

Two example configurations are provided in the [examples directory](https://github.com/bremeld/solr-undertow/tree/master/example):

* Basic configuration 
* Configuration with request limitting (max concurrent requests + max queued requests)

Settings from the configuration file will be overriden by system properties of the same fully qualified name (i.e. `solr.undertow.zkHost`) but also by the standard Solr system property used with Jetty (i.e. `zkHost` or `jetty.Port`). The following typical Solr system properties are recognized, and also set by the system so that any Solr configuration file using variable substitution will find them as expected:

|Solr typical|Solr-Undertow Equivalent|
|---|---|
|jetty.port|solr.undertow.httpClusterPort|
|zkRun|solr.undertow.zkRun|
|zkHost|solr.undertow.zkHost|
|solr.log|solr.undertow.solrLogs|
|hostContext|solr.undertow.solrContextPath|
|solr.solr.home|solrHome|
|solr.data.dir|(no equivalent, not checked, passes through to Solr)|

The Solr Typical setting overrides the solr-undertow equivalent system property, which overrides the same value from the configuration file.  You only need to provide one (preferably the solr-undertow version) and the other will be set to match.

Other Notes
===========

This requires JDK 1.7 or newer.  Do not run Solr on anything older, it isn't worth the pain of inferior garbage collectors.  Start with default settings for garbage collection, and tune from there if needed at all. 

You can find Solr WAR files in Maven repository.  For example [Solr 4.10.0 WAR](http://central.maven.org/maven2/org/apache/solr/solr/4.10.0/solr-4.10.0.war) and [find older versions here](http://mvnrepository.com/artifact/org.apache.solr/solr).

To set additional Java startup parameters for the VM, you can set the `SOLR_UNDERTOW_OPTS` environment variable before running, for example:

```sh
export SOLR_UNDERTOW_OPTS="-Xms15G -Xmx15G -XX:MaxPermSize=512m -XX:PermSize=256m
```

And an example adding support for JMX:

```sh
export SOLR_UNDERTOW_OPTS="-Xms15G -Xmx15G -XX:MaxPermSize=512m -XX:PermSize=256m -Dcom.sun.management.jmxremote -Dcom.sun.management.jmxremote.port=9901 -Dcom.sun.management.jmxremote.ssl=false -Dcom.sun.management.jmxremote.authenticate=false
```

Building Your Own Binary
========

You may download a release under the releases here, or you can build your own binary.

`./gradlew build distTar`

and the resulting binary will be under `./build/distributions` as a .tgz file.  To build a zip, use `distZip` instead of `distTar`







