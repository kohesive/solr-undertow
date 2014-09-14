solr-undertow
=============

[Solr](http://lucene.apache.org/solr/) running in standalone server - High Performance, tiny, fast, easy, standalone deployment.  Requires JDK 1.7 or newer, Solr 4.1 or newer.  Less than 4MB download, starts instantly, performs inline or better than all application servers.  Written in the [Kotlin language](http://kotlinlang.org) for the JVM (a better Java, not as complex as Scala).

Releases [are available here](https://github.com/bremeld/solr-undertow/releases) on GitHub.

This application launches a Solr WAR file as a standalone server running a high performance HTTP front-end based on [undertow.io](http://undertow.io) (the engine behind WildFly, the new JBoss).  It has no features of an application server, does nothing more than load Solr servlets and also service the Admin UI.  It is production-quality for a stand-alone Solr server.

**NOTE:** Be sure to read the section below entitled "HTTP IO and Worker threads" since the defaults are conservative.

#### Usage

Usage is simple, you only need a configuration file and a Solr WAR:

```sh
bin/solr-undertow <configurationFile>
```

You can run the included example configuration + Solr 4.10.0 (from the release that includes Solr) using:

```sh
bin/solr-undertow example/example.conf
```

Then navigate your browser to `http://localhost:8983/solr`

#### Configuration

The configuration file is on the JSON like [HOCON format](https://github.com/typesafehub/config/blob/master/HOCON.md) and loaded using [TypeSafe Config](https://github.com/typesafehub/config).  So any features it supports are supported here.

A configuration file must minimally contain these settings (paths are relative to the configuration file):

```conf
solr.undertow: {
  solrHome: "./solr-home"
  solrLogs: "./solr-logs"
  tempDir: "./solr-temp"
  solrVersion: "4.4.0"
  solrWarFile: ./solr-wars/solr-${solr.undertow.solrVersion}.war
}
```

In this configuration `solrHome` must contain at minimum `solr.xml` (and `zoo.cfg` if SolrCloud) and any pre-configured cores.

The defaults, and all configuration options can be seen in the [configuration defaults file](https://github.com/bremeld/solr-undertow/blob/master/src/main/resources/reference.conf).  Which include `httpClusterPort: 8983`, the default server port.

Configured directories are validated at startup to give clear error messages, they are checked for existance and readable/writeable attributes (depending on the directory).

#### Example Configuration / Directory Tree

Two example configurations are provided in the [example directory](https://github.com/bremeld/solr-undertow/tree/master/example):

* [Basic configuration](https://github.com/bremeld/solr-undertow/blob/master/example/example.conf)
* [Configuration with request limitting](https://github.com/bremeld/solr-undertow/blob/master/example/example-ratelimited.conf) (max concurrent requests + max queued requests)

#### System Properties

Settings from the configuration file will be overriden by system properties of the same fully qualified name (i.e. `solr.undertow.zkHost`) but also by the standard Solr system property used with Jetty (i.e. `zkHost` or `jetty.Port`). The following Solr system properties are recognized, and are also set by solr-undertow so that any Solr configuration file using variable substitution will find them as expected:

|Solr typical|Solr-Undertow Equivalent|
|---|---|
|jetty.port|solr.undertow.httpClusterPort|
|host|solr.undertow.httpHost|
|zkRun|solr.undertow.zkRun|
|zkHost|solr.undertow.zkHost|
|solr.log|solr.undertow.solrLogs|
|hostContext|solr.undertow.solrContextPath|
|solr.solr.home|solrHome|
|solr.data.dir|(no equivalent, not checked, passes through to Solr)|

The solr-undertow has priority if both are present in system properties; you only need to provide one and the other will be made to match.

Other Notes
===========


#### Tuning

**Read about tuning** in the [TUNNING.md file](https://github.com/bremeld/solr-undertow/blob/master/TUNING.MD)


#### JDK 1.7

**Solr-undertow requires JDK 1.7 or newer**.  Do not run Solr on anything older, it isn't worth the pain of inferior garbage collectors.  Oracle JDK is also prefered, Open JDK does not perform as well and at times has been incompatible.  

**see also: [Tuning Solr-Undertow](https://github.com/bremeld/solr-undertow/blob/master/TUNING.MD)

#### Solr WAR Files

**You can download Solr WAR files** from the Maven repository.  For example [Solr 4.10.0 WAR](http://central.maven.org/maven2/org/apache/solr/solr/4.10.0/solr-4.10.0.war) and [find older versions here](http://mvnrepository.com/artifact/org.apache.solr/solr).

#### Custom JVM Parameters

**To set additional Java startup parameters** for the VM, you can set the `SOLR_UNDERTOW_OPTS` environment variable before running, for example:

```sh
export SOLR_UNDERTOW_OPTS="-Xms15G -Xmx15G -XX:MaxPermSize=512m -XX:PermSize=256m"
```

And an example adding support for JMX:

```sh
export SOLR_UNDERTOW_OPTS="-Xms15G -Xmx15G -XX:MaxPermSize=512m -XX:PermSize=256m -Dcom.sun.management.jmxremote -Dcom.sun.management.jmxremote.port=9901 -Dcom.sun.management.jmxremote.ssl=false -Dcom.sun.management.jmxremote.authenticate=false"
```

#### Logging

**Logging is via Slf4j routing to _Log4j_** and can be configured differently than the [default](https://github.com/bremeld/solr-undertow/blob/master/src/main/resources/log4j.properties) by providing a [custom configuration file](http://logging.apache.org/log4j/2.x/manual/configuration.html) pointed to by the system property log4j.configurationFile. 

#### HTTP IO and Worker threads

The following settings are defaulted as:

|Setting|Default|
|---|---|
|httpIoThreads|number of system cores, as returned by Runtime.getRuntime().availableProcessors()|
|httpWorkerThreads|8 * number of system cores|

It is rare that you would ever adjust `httpIoThreads`.  The front-end of this server uses non-blocking IO and all IO is done separately from the worker threads.  Therefore be conservative with your thread count.  Start with the defaults, go upwards if not using all CPU, and downwards until CPU hovers below 90% (plus more headroom to allow for index commits and warming new searchers using CPU). 

**see also: [Tuning Solr-Undertow](https://github.com/bremeld/solr-undertow/blob/master/TUNING.MD)
 
Building Your Own Binary
========

You may download a release under the releases here, or you can build your own binary.

`./gradlew build distTar distWithSolr`

and the resulting binaries will be under `./build/distributions` as .tgz files.  

#### Building with IntelliJ

Load the build.gradle as a project, accept the default Gradle wrapper, and then fixup JDK to be 1.7 or newer.  Be sure you have Kotlin plugin installed, check the [gradle.properties file](https://github.com/bremeld/solr-undertow/blob/master/gradle.properties) for Kotlin version number.






