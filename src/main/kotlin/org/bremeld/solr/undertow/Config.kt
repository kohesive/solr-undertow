//    Copyright 2014, 2015 Bremeld Corp SA (Montevideo, Uruguay)
//    https://www.linkedin.com/company/bremeld
//
//    Licensed under the Apache License, Version 2.0 (the "License");
//    you may not use this file except in compliance with the License.
//    You may obtain a copy of the License at
//
//    http://www.apache.org/licenses/LICENSE-2.0
//
//    Unless required by applicable law or agreed to in writing, software
//    distributed under the License is distributed on an "AS IS" BASIS,
//    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
//    See the License for the specific language governing permissions and
//    limitations under the License.

package org.bremeld.solr.undertow

import com.typesafe.config.Config
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import uy.klutter.config.typesafe.*
import uy.klutter.config.typesafe.jdk7.FileConfig
import uy.klutter.config.typesafe.jdk7.asPathSibling
import uy.klutter.core.common.initializedBy
import uy.klutter.core.jdk.minimum
import uy.klutter.core.jdk7.notExists
import java.nio.file.Files
import java.nio.file.Path
import java.util.*
import java.util.concurrent.TimeUnit
import kotlin.reflect.KProperty1

public val SOLR_UNDERTOW_CONFIG_PREFIX = "solr.undertow"

public val SYS_PROP_JETTY_PORT = "jetty.port"
public val OUR_PROP_HTTP_PORT = "httpClusterPort"

public val SYS_PROP_ZKRUN = "zkRun"
public val OUR_PROP_ZKRUN = "zkRun"

public val SYS_PROP_ZKHOST = "zkHost"
public val OUR_PROP_ZKHOST = "zkHost"

public val SYS_PROP_SOLR_LOG = "solr.log"
public val OUR_PROP_SOLR_LOG = "solrLogs"

public val SYS_PROP_HOST_CONTEXT = "hostContext"
public val OUR_PROP_HOST_CONTEXT = "solrContextPath"

public val OUR_PROP_HTTP_HOST = "httpHost"

public val SYS_PROP_SOLR_HOME = "solr.solr.home"
public val OUR_PROP_SOLR_HOME = "solrHome"

public val SYS_PROP_JBOSS_LOGGING = "org.jboss.logging.provider"

public val OUR_PROP_HTTP_IO_THREADS = "httpIoThreads"
public val OUR_PROP_HTTP_WORKER_THREADS = "httpWorkerThreads"
public val OUR_PROP_SOLR_WAR = "solrWarFile"
public val OUR_PROP_SOLR_VERSION = "solrVersion"
public val OUR_PROP_TEMP_DIR = "tempDir"
public val OUR_PROP_LIBEXT_DIR = "libExtDir"



// system and environment variables that need to be treated the same as our configuration items
public val SOLR_OVERRIDES = mapOf(SYS_PROP_JETTY_PORT to OUR_PROP_HTTP_PORT,
        SYS_PROP_ZKRUN to OUR_PROP_ZKRUN,
        SYS_PROP_ZKHOST to OUR_PROP_ZKHOST,
        SYS_PROP_SOLR_LOG to OUR_PROP_SOLR_LOG,
        SYS_PROP_HOST_CONTEXT to OUR_PROP_HOST_CONTEXT,
        SYS_PROP_SOLR_HOME to OUR_PROP_SOLR_HOME)
public val SYS_PROPERTIES_THAT_ARE_PATHS = setOf(SYS_PROP_SOLR_LOG, SYS_PROP_SOLR_HOME)

// These allow substitution of Env variables for unit tests.
public var SERVER_ENV_WRAPPER: Map<out Any, Any> = System.getenv()
public var SERVER_SYS_WRAPPER: MutableMap<Any, Any> = System.getProperties()

public class ServerConfigLoader(val configFile: Path) {
    // our config chain wants Env variables to override Reference Config because that is how Solr would work,
    // but then explicit configuration properties override those, and any system properties override everything.
    //
    // No configuration variables are resolved until the end, so a variable can be used in a lower level, and fulfilled
    // by a higher.
    val resolvedConfig = loadConfig(PropertiesAsConfig(readRelevantProperties(SERVER_SYS_WRAPPER)),
                                    FileConfig(configFile),
                                    PropertiesAsConfig(readRelevantProperties(SERVER_ENV_WRAPPER)),
                                    ReferenceConfig()) then { config ->
        writeRelevantSystemProperties(config)
    }

    fun hasLoggingDir(): Boolean {
        return resolvedConfig.hasPath("${SOLR_UNDERTOW_CONFIG_PREFIX}.${OUR_PROP_SOLR_LOG}")
    }

    private fun readRelevantProperties(props: Map<out Any, Any>): Properties {
        // copy values from typical Solr system or environment properties into our equivalent configuration item

        val p = Properties()
        for (mapping in SOLR_OVERRIDES.entries) {
            val (solrPropName, ourPropName) = mapping
            val ourPropFQName = "${SOLR_UNDERTOW_CONFIG_PREFIX}.${ourPropName}"

            val envPropValue: Any? = if (solrPropName == SYS_PROP_ZKRUN) {
                val temp = props.getRaw(solrPropName)
                if (temp != null) "true" else null
            }
            else {
                props.getRaw(solrPropName)
            }

            // don't override our own config item set as  property
            val value = props.getRaw(ourPropFQName) ?: envPropValue
            if (value != null) {
                p.put(ourPropFQName, value)
            }
        }

        return p
    }

    private fun writeRelevantSystemProperties(fromConfig: Config) {
        // copy our configuration items into Solr system properties that might be looked for later by Solr
        for (mapping in SOLR_OVERRIDES.entries) {
            val cfgKey = "${SOLR_UNDERTOW_CONFIG_PREFIX}.${mapping.value}"
            val configValue = fromConfig.value(cfgKey)
            if (configValue.exists()) {
                if (configValue.isNotEmptyString()) {
                    val value = if (SYS_PROPERTIES_THAT_ARE_PATHS.contains(mapping.key)) configValue.asPathSibling(configFile).toString()
                    else configValue.asString()
                    SERVER_SYS_WRAPPER.put(mapping.key, value)
                }
            }
        }
        val zkRunCfgkey = fromConfig.value("${SOLR_UNDERTOW_CONFIG_PREFIX}.${OUR_PROP_ZKRUN}")
        if (zkRunCfgkey.notExists() || !zkRunCfgkey.asBoolean()) {
            SERVER_SYS_WRAPPER.remove(SYS_PROP_ZKRUN)
        }

        // an extra system property to set
        SERVER_SYS_WRAPPER.put(SYS_PROP_JBOSS_LOGGING, "slf4j")
    }
}

public class ServerConfig(private val log: Logger, val loader: ServerConfigLoader) {
    private val configured = loader.resolvedConfig.getConfig(SOLR_UNDERTOW_CONFIG_PREFIX)!!

    val httpClusterPort = configured.value(OUR_PROP_HTTP_PORT).asInt()
    val httpHost = configured.value(OUR_PROP_HTTP_HOST).asString()
    val httpIoThreads = configured.value(OUR_PROP_HTTP_IO_THREADS).asInt().minimum(0)
    val httpWorkerThreads = configured.value(OUR_PROP_HTTP_WORKER_THREADS).asInt().minimum(0)

    val shutdownConfig = ShutdownConfig(log, configured.nested("shutdown"))

    val activeRequestLimits = configured.value("activeRequestLimits").asStringArray()
    val requestLimiters = HashMap<String, RequestLimitConfig>() initializedBy { requestLimiters ->
        val namedConfigs = configured.nested("requestLimits")
        activeRequestLimits.forEach { name ->
            requestLimiters.put(name, RequestLimitConfig(log, name, namedConfigs.getConfig(name)!!))
        }
    }
    val zkRun = configured.value(OUR_PROP_ZKRUN).asBoolean()
    val zkHost = configured.value(OUR_PROP_ZKHOST).asString()
    val solrHome = configured.value(OUR_PROP_SOLR_HOME).asPathSibling(loader.configFile)
    val solrLogs = configured.value(OUR_PROP_SOLR_LOG).asPathSibling(loader.configFile)
    val tempDir = configured.value(OUR_PROP_TEMP_DIR).asPathSibling(loader.configFile)
    val solrVersion = configured.value(OUR_PROP_SOLR_VERSION).asString()
    val solrWarFile = configured.value(OUR_PROP_SOLR_WAR).asPathSibling(loader.configFile)
    val libExtDir = configured.value(OUR_PROP_LIBEXT_DIR).asPathSibling(loader.configFile)
    val solrContextPath = configured.value(OUR_PROP_HOST_CONTEXT).asString().let { solrContextPath ->
        if (!solrContextPath.startsWith("/")) "/" + solrContextPath else solrContextPath
    }

    val accessLogFormat = configured.value("accessLogFormat").asString()
    val accessLogger = LoggerFactory.getLogger("http.access")!!
    val accessLogEnableRequestTiming = configured.value("accessLogEnableRequestTiming").asBoolean()

    fun hasLibExtDir(): Boolean = configured.value(OUR_PROP_LIBEXT_DIR).isNotEmptyString()


    private fun printF(p: KProperty1<ServerConfig, Path>) = log.info("  ${p.name}: ${p.get(this)}")
    private fun printS(p: KProperty1<ServerConfig, String>) = log.info("  ${p.name}: ${p.get(this)}")
    private fun printSA(p: KProperty1<ServerConfig, Array<String>>) = log.info("  ${p.name}: ${p.get(this).joinToString(",")}")
    private fun printB(p: KProperty1<ServerConfig, Boolean>) = log.info("  ${p.name}: ${p.get(this)}")
    private fun printI(p: KProperty1<ServerConfig, Int>) = log.info("  ${p.name}: ${p.get(this)}")
    private fun printI(p: KProperty1<ServerConfig, Int>, defaultVal: Int) = log.info("  ${p.name}: ${p.get(this)} ${if (p.get(this) == defaultVal) "(no setting, using default)" else ""}")

    fun print() {
        log.info("=== [ Config File settings from: ${loader.configFile} ] ===")
        printB(ServerConfig::zkRun)
        printS(ServerConfig::zkHost)
        printI(ServerConfig::httpClusterPort)
        printS(ServerConfig::httpHost)
        printI(ServerConfig::httpIoThreads, 0)
        printI(ServerConfig::httpWorkerThreads, 0)

        printSA(ServerConfig::activeRequestLimits)

        requestLimiters.values.forEach { rl ->
            rl.print()
        }

        printF(ServerConfig::solrHome)
        printF(ServerConfig::solrLogs)
        printF(ServerConfig::tempDir)
        printS(ServerConfig::solrVersion)
        printF(ServerConfig::solrWarFile)
        printS(ServerConfig::solrContextPath)
        if (hasLibExtDir()) {
            printF(ServerConfig::libExtDir)
        }

        log.debug { "<<<< CONFIGURATION FILE TRACE >>>>" }
        log.debug { configured.render() }

        log.info("=== [ END CONFIG ] ===")


    }

    fun validate(): Boolean {
        log.info("Validating configuration from: ${loader.configFile}")
        var isValid = true
        fun err(msg: String) {
            log.error(msg)
            isValid = false
        }

        fun existsIsWriteable(p: KProperty1<ServerConfig, Path>) {
            val dir = p.get(this)
            if (dir.notExists()) {
                err("${p.name} dir does not exist: ${dir}")
            }
            if (!Files.isWritable(dir)) {
                err("${p.name} dir must be writable by current user: ${dir}")
            }
        }

        fun existsIsReadable(p: KProperty1<ServerConfig, Path>) {
            val dir = p.get(this)
            if (dir.notExists()) {
                err("${p.name} does not exist: ${dir}")
            }
            if (!Files.isReadable(dir)) {
                err("${p.name} must be readable by current user: ${dir}")
            }
        }

        requestLimiters.values.forEach { rl ->
            rl.validate()
        }

        existsIsWriteable(ServerConfig::solrHome)
        existsIsWriteable(ServerConfig::solrLogs)
        existsIsWriteable(ServerConfig::tempDir)
        existsIsReadable(ServerConfig::solrWarFile)

        val distributionFilename = solrWarFile.toString()
        if (!distributionFilename.endsWith(".war") && !distributionFilename.endsWith(".zip")) {
            err("Distribution file should be a WAR file or a ZIP file, instead it is: ${solrWarFile}")
        }

        if (hasLibExtDir()) {
            existsIsReadable(ServerConfig::libExtDir)
        }

        return isValid
    }
}

public class ShutdownConfig(private val log: Logger, private val cfg: Config) {
    val httpPort = cfg.value("httpPort").asInt()
    val httpHost = cfg.value("httpHost").asString()
    val password = cfg.value("password").asString()
    val gracefulDelay = cfg.value("gracefulDelay").parseDuration(TimeUnit.MILLISECONDS)
    val gracefulDelayString = cfg.value("gracefulDelay").asString()
}

public class RequestLimitConfig(private val log: Logger, val name: String, private val cfg: Config) {
    val exactPaths = cfg.value("exactPaths").asStringListOrEmpty()
    val pathSuffixes = cfg.value("pathSuffixes").asStringListOrEmpty()
    val concurrentRequestLimit = cfg.value("concurrentRequestLimit").asInt().minimum(-1)
    val maxQueuedRequestLimit = cfg.value("maxQueuedRequestLimit").asInt().minimum(-1)


    fun validate(): Boolean {
        if (exactPaths.isEmpty() && pathSuffixes.isEmpty()) {
            log.error("${name}: exactPaths AND/OR pathSuffixes is required in rate limitter")
            return false
        }
        return true
    }

    fun print() {
        log.info("  ${name} >>")
        log.info("    exactPaths: ${exactPaths.joinToString(",")}")
        log.info("    pathSuffixes: ${pathSuffixes.joinToString(",")}")
        log.info("    concurrentRequestLimit: ${if (concurrentRequestLimit < 0) "unlimited" else concurrentRequestLimit.minimum(1) }")
        log.info("    maxQueuedRequestLimit: ${if (maxQueuedRequestLimit < 0) "unlimited" else maxQueuedRequestLimit }")
    }
}


