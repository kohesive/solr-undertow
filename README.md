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

#### Configuration

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

In this configuration `solrHome` contains at minimum `solr.xml` (and `zoo.cfg` if SolrCloud) and any pre-configured cores.

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
|zkRun|solr.undertow.zkRun|
|zkHost|solr.undertow.zkHost|
|solr.log|solr.undertow.solrLogs|
|hostContext|solr.undertow.solrContextPath|
|solr.solr.home|solrHome|
|solr.data.dir|(no equivalent, not checked, passes through to Solr)|

The solr-undertow has priority if both are present in system properties; you only need to provide one and the other will be made to match.

Other Notes
===========

#### JDK 1.7

**Solr-undertow requires JDK 1.7 or newer**.  Do not run Solr on anything older, it isn't worth the pain of inferior garbage collectors.  Oracle JDK is also prefered, Open JDK does not do as well with memory management, and has at different releases had different incompatibilities.  Start with clearing settings for garbage collection, and tune from there if needed.

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

It is rare that you would ever adjust `httpIoThreads` (and 2 * cores would be about the max value ever needed at any scale level).  

For `httpWorkerThreads` you should *be conservative* but not starve Solr either.  In the Solr distribution, you see that Jetty is configured with a max worker thread count of *10,000*.  **Do not immediately jump to this setting.**  Some user accounts have a file handle limit that this could exceed causing issues.  And this is an excesively high number.  It is best to test your performance and CPU usage, and set the worker threads to a value that protects you from a "train wreck" where your CPU is overloaded and performance degrades dramatically.  Find a value that keeps your CPU from max, leave head room for commits and index warming, and let the `httpWorkerThreads` keep the system from overloading, while having enough to maximize throughput.  Find the sweet spot by running typical load with something like [JMeter](http://jmeter.apache.org) that puts CPU above 90%, then lowering the worker threads until CPU drops to whatever maximum is safe for your envirionment.  It is better to queue users or reject them than to crush and kill your Solr instance.

**As a real-world example** for worker threads, we had a search cluster that a vendor for Solr support suggested should raise the thread count from 1000 to 10,000 to get more than 750 queries-per-second on a 6 node cluster (32 core, 64G memory), but running on solr-undertow the actual sweet spot on these boxes was `httpIoThreads` 16 (higher didn't help), and `httpWorkerThreads` 100 which along with other tuning changes brought the throughput to near 1600 queries per second, and with more manageable CPU that would not overload.  Hence 3.125 threads per core (6.25 per IO thread which is probably not relevant). Our default in solr-undertow would have been 256 which is too high for this use case, but let us see max CPU to tune downwards. 

So "more threads" is not always the answer. Tune queries, check `NRTCachingDirectoryFactory` vs `MMapDirectoryFactory` vs `NIOFSDirectoryFactory` since you could be surprised which one is fastest for your use case (MMap can be slower on some systems, and NRTCaching is based on MMap so might be slower as well), and watch out for `HDFSDirectoryFactory` which can be significantly slower so be sure you have value from being on HDFS to offset this lost of speed. Check your file system (SSD is the only one true answer, or fit your index into memory and OS disk cache, you'll pay the extra cost in tuning man hours otherwise). Disk/Network load and GC are other factors that will pull down performance.  _Test to find your own answer._  

Building Your Own Binary
========

You may download a release under the releases here, or you can build your own binary.

`./gradlew build distTar`

and the resulting binary will be under `./build/distributions` as a .tgz file.  To build a zip, use `distZip` instead of `distTar`

#### Building with IntelliJ

Load the build.gradle as a project, accept the default Gradle wrapper, and then fixup JDK to be 1.7 or newer.  Be sure you have Kotlin plugin installed, check the [gradle.properties file](https://github.com/bremeld/solr-undertow/blob/master/gradle.properties) for Kotlin version number.






