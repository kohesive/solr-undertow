//    Copyright 2014 Bremeld Corp SA (Montevideo, Uruguay)
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

import kotlin.reflect.KMemberProperty
import com.typesafe.config.Config
import com.typesafe.config.ConfigFactory
import org.slf4j.Logger
import java.nio.file.Files
import java.util.HashMap
import java.util.Properties
import com.typesafe.config.ConfigResolveOptions
import java.nio.file.Path
import org.slf4j.LoggerFactory

private val SOLR_UNDERTOW_CONFIG_PREFIX = "solr.undertow"

private val SYS_PROP_JETTY_PORT = "jetty.port"
private val OUR_PROP_HTTP_PORT = "httpClusterPort"

private val SYS_PROP_ZKRUN = "zkRun"
private val OUR_PROP_ZKRUN = "zkRun"

private val SYS_PROP_ZKHOST = "zkHost"
private val OUR_PROP_ZKHOST = "zkHost"

private val SYS_PROP_SOLR_LOG = "solr.log"
private val OUR_PROP_SOLR_LOG = "solrLogs"

private val SYS_PROP_HOST_CONTEXT = "hostContext"
private val OUR_PROP_HOST_CONTEXT = "solrContextPath"

private val OUR_PROP_HTTP_HOST = "httpHost"

private val SYS_PROP_SOLR_HOME = "solr.solr.home"
private val OUR_PROP_SOLR_HOME = "solrHome"

private val SYS_PROP_JBOSS_LOGGING = "org.jboss.logging.provider"

private val OUR_PROP_HTTP_IO_THREADS = "httpIoThreads"
private val OUR_PROP_HTTP_WORKER_THREADS = "httpWorkerThreads"
private val OUR_PROP_SOLR_WAR = "solrWarFile"
private val OUR_PROP_SOLR_VERSION = "solrVersion"
private val OUR_PROP_TEMP_DIR = "tempDir"
private val OUR_PROP_LIBEXT_DIR = "libExtDir"


// system and environment variables that need to be treated the same as our configuration items
private val SOLR_OVERRIDES = mapOf(SYS_PROP_JETTY_PORT to OUR_PROP_HTTP_PORT,
        SYS_PROP_ZKRUN to OUR_PROP_ZKRUN,
        SYS_PROP_ZKHOST to OUR_PROP_ZKHOST,
        SYS_PROP_SOLR_LOG to OUR_PROP_SOLR_LOG,
        SYS_PROP_HOST_CONTEXT to OUR_PROP_HOST_CONTEXT,
        SYS_PROP_SOLR_HOME to OUR_PROP_SOLR_HOME)
private val SYS_PROPERTIES_THAT_ARE_PATHS = setOf(SYS_PROP_SOLR_LOG, SYS_PROP_SOLR_HOME)

public class ServerConfigLoader(val configFile: Path) {
    private val solrOverrides = ConfigFactory.parseProperties(getRelevantSystemProperties())!!
    private val userConfig = ConfigFactory.parseFile(configFile.toFile())!!
    private val fullConfig = solrOverrides + userConfig

    val resolvedConfig = ConfigFactory.load(fullConfig, ConfigResolveOptions.noSystem())!! then { config ->
        setRelevantSystemProperties(config)
    }

    fun hasLoggingDir(): Boolean {
        return resolvedConfig.hasPath("${SOLR_UNDERTOW_CONFIG_PREFIX}.${OUR_PROP_SOLR_LOG}")
    }

    private fun getRelevantSystemProperties(): Properties {
        // copy values from typical Solr system or environment properties into our equivalent configuration item

        val p = Properties()
        for (mapping in SOLR_OVERRIDES.entrySet()) {
            val cfgKey = "${SOLR_UNDERTOW_CONFIG_PREFIX}.${mapping.value}"
            // don't override our own config item set as system property
            if (System.getProperty(cfgKey) == null) {
                val value = System.getProperty(mapping.key) ?: System.getenv(mapping.key)
                if (value != null) {
                    val adjustedValue = if (mapping.key == SYS_PROP_ZKRUN) "true"
                                        else value
                    p.put(cfgKey, adjustedValue)
                }
            }
        }

        return p
    }

    private fun setRelevantSystemProperties(fromConfig: Config) {
        // copy our configuration items into Solr system properties that might be looked for later by Solr
        for (mapping in SOLR_OVERRIDES.entrySet()) {
            val cfgKey = "${SOLR_UNDERTOW_CONFIG_PREFIX}.${mapping.value}"
            val configValue = fromConfig.value(cfgKey)
            if (configValue.exists()) {
                if (configValue.isNotEmptyString()) {
                    val value = if (SYS_PROPERTIES_THAT_ARE_PATHS.contains(mapping.key)) configValue.asPath(configFile).toString()
                                else configValue.asString()
                    System.setProperty(mapping.key, value)
                }
            }
        }
        val zkRunCfgkey = fromConfig.value("${SOLR_UNDERTOW_CONFIG_PREFIX}.${OUR_PROP_ZKRUN}")
        if (zkRunCfgkey.notExists() || !zkRunCfgkey.asBoolean()) {
            System.clearProperty(SYS_PROP_ZKRUN)
        }

        // an extra system property to set
        System.setProperty(SYS_PROP_JBOSS_LOGGING, "slf4j")
    }
}

public class ServerConfig(private val log: Logger, val loader: ServerConfigLoader) {
    private val configured = loader.resolvedConfig.getConfig(SOLR_UNDERTOW_CONFIG_PREFIX)!!

    val httpClusterPort = configured.value(OUR_PROP_HTTP_PORT).asInt()
    val httpHost = configured.value(OUR_PROP_HTTP_HOST).asString()
    val httpIoThreads = configured.value(OUR_PROP_HTTP_IO_THREADS).asInt().minimum(0)
    val httpWorkerThreads = configured.value(OUR_PROP_HTTP_WORKER_THREADS).asInt().minimum(0)
    val activeRequestLimits = configured.value("activeRequestLimits").asStringArray()
    val requestLimiters = HashMap<String, RequestLimitConfig>() initializedBy { requestLimiters ->
        val namedConfigs = configured.nested("requestLimits")
        activeRequestLimits.forEach { name ->
            requestLimiters.put(name, RequestLimitConfig(log, name, namedConfigs.getConfig(name)!!))
        }
    }
    val zkRun = configured.value(OUR_PROP_ZKRUN).asBoolean()
    val zkHost = configured.value(OUR_PROP_ZKHOST).asString()
    val solrHome = configured.value(OUR_PROP_SOLR_HOME).asPath(loader.configFile)
    val solrLogs = configured.value(OUR_PROP_SOLR_LOG).asPath(loader.configFile)
    val tempDir = configured.value(OUR_PROP_TEMP_DIR).asPath(loader.configFile)
    val solrVersion = configured.value(OUR_PROP_SOLR_VERSION).asString()
    val solrWarFile = configured.value(OUR_PROP_SOLR_WAR).asPath(loader.configFile)
    val libExtDir = configured.value(OUR_PROP_LIBEXT_DIR).asPath(loader.configFile)
    val solrContextPath = configured.value(OUR_PROP_HOST_CONTEXT).asString() let { solrContextPath ->
        if (!solrContextPath.startsWith("/")) "/" + solrContextPath else solrContextPath
    }

    val accessLogFormat = configured.value("accessLogFormat").asString()
    val accessLogger = LoggerFactory.getLogger("http.access")!!
    val accessLogEnableRequestTiming = configured.value("accessLogEnableRequestTiming").asBoolean()

    fun hasLibExtDir(): Boolean = configured.value(OUR_PROP_LIBEXT_DIR).isNotEmptyString()


    private fun printF(p: KMemberProperty<ServerConfig, Path>) = log.info("  ${p.name}: ${p.get(this)}")
    private fun printS(p: KMemberProperty<ServerConfig, String>) = log.info("  ${p.name}: ${p.get(this)}")
    private fun printSA(p: KMemberProperty<ServerConfig, Array<String>>) = log.info("  ${p.name}: ${p.get(this).joinToString(",")}")
    private fun printB(p: KMemberProperty<ServerConfig, Boolean>) = log.info("  ${p.name}: ${p.get(this)}")
    private fun printI(p: KMemberProperty<ServerConfig, Int>) = log.info("  ${p.name}: ${p.get(this)}")
    private fun printI(p: KMemberProperty<ServerConfig, Int>, defaultVal: Int) = log.info("  ${p.name}: ${p.get(this)} ${if (p.get(this) == defaultVal) "(no setting, using default)" else ""}")

    fun print() {
        log.info("=== [ Config File settings from: ${loader.configFile} ] ===")
        printB(::zkRun)
        printS(::zkHost)
        printI(::httpClusterPort)
        printS(::httpHost)
        printI(::httpIoThreads, 0)
        printI(::httpWorkerThreads, 0)

        printSA(::activeRequestLimits)

        requestLimiters.values().forEach { rl ->
            rl.print()
        }

        printF(::solrHome)
        printF(::solrLogs)
        printF(::tempDir)
        printS(::solrVersion)
        printF(::solrWarFile)
        printS(::solrContextPath)
        if (hasLibExtDir()) {
            printF(::libExtDir)
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

        fun existsIsWriteable(p: KMemberProperty<ServerConfig, Path>) {
            val dir = p.get(this)
            if (!Files.exists(dir)) {
                err("${p.name} dir does not exist: ${dir}")
            }
            if (!Files.isWritable(dir)) {
                err("${p.name} dir must be writable by current user: ${dir}")
            }
        }

        fun existsIsReadable(p: KMemberProperty<ServerConfig, Path>) {
            val dir = p.get(this)
            if (!Files.exists(dir)) {
                err("${p.name} does not exist: ${dir}")
            }
            if (!Files.isReadable(dir)) {
                err("${p.name} must be readable by current user: ${dir}")
            }
        }

        requestLimiters.values().forEach { rl ->
            rl.validate()
        }

        existsIsWriteable(::solrHome)
        existsIsWriteable(::solrLogs)
        existsIsWriteable(::tempDir)
        existsIsReadable(::solrWarFile)

        val warFileAsString = solrWarFile.toString()
        if (!warFileAsString.endsWith(".war") && !warFileAsString.endsWith(".zip")) {
            err("WAR file should have a name ending in .war or maybe .zip, instead is: ${solrWarFile}")
        }

        if (hasLibExtDir()) {
            existsIsReadable(::libExtDir)
        }

        return isValid
    }
}


public class RequestLimitConfig(private val log: Logger, val name: String, private val cfg: Config) {
    val exactPaths = cfg.value("exactPaths").asGuaranteedStringList()
    val pathSuffixes = cfg.value("pathSuffixes").asGuaranteedStringList()
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


