solr-undertow
=============

[![CircleCI branch](https://img.shields.io/circleci/project/kohesive/solr-undertow/master.svg)](https://circleci.com/gh/kohesive/solr-undertow/tree/master) [![License](https://img.shields.io/badge/license-Apache%202-blue.svg)](https://github.com/kohesive/solr-undertow/blob/master/LICENSE.txt) [![Kotlin](https://img.shields.io/badge/kotlin-1.0.0--rc--1017-blue.svg)](http://kotlinlang.org)

[Solr](http://lucene.apache.org/solr/) and SolrCloud running in high performance server - Tiny, fast, easy, standalone deployment, simple to configure, and without an application server.  Requires JDK 1.7 or newer, Solr 4.x or 5.x.  Less than 7MB download, starts instantly, performs inline or better than all application servers.  Written in the [Kotlin language](http://kotlinlang.org) for the JVM (a better Java, not as complex as Scala).

This application launches a Solr distribution as a standalone server running a high performance HTTP front-end based on [undertow.io](http://undertow.io) (the engine behind WildFly, the new JBoss).  It has no features of an application server, does nothing more than load Solr servlets and also service the Admin UI.  It is production-quality for a stand-alone Solr server.

Releases [are available on GitHub](https://github.com/kohesive/solr-undertow/releases).

**NOTE:** Be sure to read the [Tuning Solr-Undertow](./TUNING.MD) guide.

#### Usage

Usage is simple, you only need a configuration file and a Solr distribution (or in the olden days, a WAR):

```sh
bin/solr-undertow <configurationFile>
```

You can run the included example configuration + Solr (from the release that includes each Solr version) using:

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
  solrVersion: "5.4.1"
  solrWarFile: ./solr-wars/solr-${solr.undertow.solrVersion}.zip
}
```

In this configuration `solrHome` must contain at minimum `solr.xml` (and `zoo.cfg` if SolrCloud) and any pre-configured cores.

The defaults, and all configuration options can be seen in the [configuration defaults file](src/main/resources/reference.conf).  Which include `httpClusterPort: 8983`, the default server port.

Configured directories are validated at startup to give clear error messages, they are checked for existance and readable/writeable attributes (depending on the directory).

#### Example Configuration / Directory Tree

Two example configurations are provided in the [example directory](example/):

* [Basic configuration](example/example.conf)
* [Configuration with request limitting](example/example-ratelimited.conf) (max concurrent requests + max queued requests)

#### System and Environment Properties

When System or Environment variables are used, an order of precedence is used favoring the Solr-Undertow properties over legacy property names from Solr.  Note, not all variables are legal environment variables, and the use of env variables is not recommended, configuration or system properties is best.  Here is the exact order of configuration overriding:

* **Solr-Undertow fully qualified** System property
* **Solr legacy** System property
* Configuration File **Solr-Undertow** property
* **Solr-Undertow full qualified** Environment variable  (impossible on systems that do not allow "." in environment variable names)
* **Solr legacy** Environment variable (only for variables legal on the system)

It is recommended only to use the Solr-Undertow configuration file, with occasional overrides using Solr-Undertow fully qualified property names in SOLR_UNDERTOW_OPTS environment variable.  The following are the properties, Solr legacy and Solr-Undertow fully qualified:

|Solr typical (legacy)|Solr-Undertow Fully Qualified|
|---|---|
|jetty.port|solr.undertow.httpClusterPort|
|zkRun|solr.undertow.zkRun|
|zkHost|solr.undertow.zkHost|
|solr.log|solr.undertow.solrLogs|
|hostContext|solr.undertow.solrContextPath|
|solr.solr.home|solrHome|
|solr.data.dir|(no equivalent, not checked, passes through to Solr)|

**After configuration loading, the Solr legacy system properties are reset to match the resulting configuration** so that Solr configuration files with variables, and the Solr process will see them as expected.

An [example using SOLR_UNDERTOW_OPTS](#custom-jvm-parameters) environment variable to override configuration is below...

Other Notes
===========


#### Tuning

**Read about tuning** in the [TUNNING.md file](./TUNING.MD)


#### JDK 1.7

**Solr-undertow requires JDK 1.7 or newer**.  Do not run Solr on anything older, it isn't worth the pain of inferior garbage collectors.  Oracle JDK is also prefered, Open JDK does not perform as well and at times has been incompatible.  

#### Solr Distributions

Solr-Undertow uses one of the following as a supported Solr distrubtion:

* A `WAR` file from 5.2.1 or earlier 
* A `Zip` full Solr distribution pre 5.2.1 that contains a `WAR` file in `server/webapps/solr.war` or `example/webapps/solr.war` 
* A `Zip` full Solr distribution post 5.2.1 that conatins `server/solr-webapp/webapp` which is really an extracted `WAR` file
* A `WAR` file you create by rezipping the contents of a distribution `server/solr-webapp/webapp` directory and naming it with `.war` extension.

The smallest distribution is the WAR file, which you can create, or you can remove everything else from a distribution `Zip` file keeping only the contents of the `server/solr-webapp/webapp` directory (including the path names).

**For Solr 4.x you can download Solr WAR files** from the Maven repository.  For example [Solr 4.10.4 WAR](http://central.maven.org/maven2/org/apache/solr/solr/4.10.4/solr-4.10.4.war) and [find older versions here](http://mvnrepository.com/artifact/org.apache.solr/solr).

**For Solr 5.x you download full distributions** from the main [Solr website](http://lucene.apache.org/solr/) or from the [past version archives](http://archive.apache.org/dist/lucene/solr/).   Some earlier 5.x versions have WAR files available as well.

**For Solr 6.x you download a nightly build** from [Solr Build System](https://builds.apache.org/job/Solr-Artifacts-trunk/lastSuccessfulBuild/artifact/solr/package/)


#### Custom JVM Parameters

**To set additional Java startup parameters** for the VM, you can set the `SOLR_UNDERTOW_OPTS` environment variable before running, for example:

```sh
export SOLR_UNDERTOW_OPTS="-Xms15G -Xmx15G -XX:MaxPermSize=512m -XX:PermSize=256m"
```

And an example adding support for JMX:

```sh
export SOLR_UNDERTOW_OPTS="-Xms15G -Xmx15G -XX:MaxPermSize=512m -XX:PermSize=256m -Dcom.sun.management.jmxremote -Dcom.sun.management.jmxremote.port=9901 -Dcom.sun.management.jmxremote.ssl=false -Dcom.sun.management.jmxremote.authenticate=false"
```

And and example of overriding settings in the configuration file from system properties, and then running:

```sh
export SOLR_UNDERTOW_OPTS="-Dsolr.undertow.httpClusterPort=8080 -Dsolr.undertow.solrHome=./solr-alt-home"
../bin/solr-undertow example.conf
```

#### Logging

**Logging is via Slf4j routing to _LogBack_** and can be configured differently than the [default](src/main/resources/logback.xml) by providing a [custom configuration file](http://logback.qos.ch/manual/configuration.html) pointed to by the system property `logback.configurationFile`. 

Solr-Undertow writes the following log files:

|filename|description|
|---|---|
|solr*.log|Java logging, including Solr internal logging|
|error*.log|Java logging, only log messages with level ERROR or above|
|access*.log|HTTP access logging, see [configuration defaults](src/main/resources/reference.conf) for more information on format| 

The default access log format is:
`%t %a %p \"%r\" %q %s %b %Dms %{o,X-Solr-QTime} ${o,X-Solr-Hits}`

In order, these are described as:

|macro|description|
|---|---|
|%t|Date and time, in Common Log Format format|
|%a|Remote IP address|
|%p|Local port|
|%U|Requested URL path|
|%q|Query string, otherwise empty string|
|%r|First line of the request|
|%s|HTTP status code of the response|
|%b|Bytes sent, excluding HTTP headers, or '-' or '-1' if no bytes were sent or unknown
|%D|Time taken to process the request, in millis|
|%{o,X-Solr-QTime}|Solr QTime if present in headers, Solr 4.9 and newer see https://issues.apache.org/jira/browse/SOLR-4018|
|%{o,X-Solr-Hits}|Solr Hits if present in headers, Solr 4.9 and newer see https://issues.apache.org/jira/browse/SOLR-4018|

Other available formats:

|macro|description|
|---|---|
|%A|Local IP Address|
|%B|Bytes sent, excluding HTTP headers|
|%h|Remote host name|
|%h|Request protocol (also included in %r)|
|%l|Remote logical username from identd (always returns '-')|
|%m|Request method (also included in %r)|
|%u|Remote user that was authenticated|
|%v|Local server name|
|%T|Time taken to process the request, in seconds|
|%I|current Request thread name (can compare later with stacktraces)|
|%{i,xxx}|xxx is incoming headers|
|%{o,xxx}|xxx is outgoing response headers|
|%{c,xxx}|xxx is a specific cookie|
|%{r,xxx}|xxx is an attribute in the ServletRequest|
|%{s,xxx}|xxx is an attribute in the HttpSession|

You can also specify either of these prebuilt formats instead of using macros, although in Solr they may not provide as much useful information as the default format:

|format string|equivalent|
|---|---|
|common|`%h %l %u %t "%r" %s %b`|
|combined|`%h %l %u %t "%r" %s %b "%{i,Referer}" "%{i,User-Agent}"`|

#### HTTP IO and Worker threads

**see also:** [Tuning Solr-Undertow](./TUNING.MD)


#### Scripting Startup / Shutdown

Solr-Undertow listens on the configured shutdown HTTP port (defaults to 9983) for a GET request, single parameter of `password` which must be set to a value matching the configured password.

If the a shutdown password is not configured then a 403 forbidden error will be returned.  If the password does not match, a 401 unauthorized error will be return.  Otherwise on success a 200 HTTP response, and on timeout or other error a 500 HTTP response (although the VM will still exit).  See the [configuration defaults file](src/main/resources/reference.conf) for the `shutdown` section.

An example of sending a shutdown command when port is configured as `9983` and password is `diediedie` (please use a better password than that!)

```
curl -X GET http://localhost:9983?password=diediedie
```

A user created example of scripting can be seen in a [GIST from @magicdude4eva](https://gist.github.com/magicdude4eva/3b5fec150fbcaafdc34c) where the stop script properly checks exit codes, and does a kill command against the `PID` if the graceful shutdown request fails.
 
Building Your Own Binary
========

You may download a release under the releases here, or you can build your own binary.

`./gradlew build distTar distWithSolr`

and the resulting binaries will be under `./build/distributions` as .tgz files.  

#### Building with IntelliJ

Open the `build.gradle` file as a project, accept the default Gradle wrapper, and then fixup JDK to be 1.7 or newer.  Be sure you have Kotlin plugin installed and that it matches the version in the Gradle build, check the [gradle.properties file](./gradle.properties) for Kotlin version number.






