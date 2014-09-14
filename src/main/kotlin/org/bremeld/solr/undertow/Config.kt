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

private val SOLR_UNDERTOW_CONFIG_PREFIX = "solr.undertow"

private val SYS_PROP_JETTY_PORT = "jetty.port"
private val OUR_PROP_HTTP_PORT = "httpClusterPort"

private val SYS_PROP_ZKRUN = "zkRun"
private val OUR_PROP_ZKRUN = "zkRun"

private val SYS_PROP_ZKHOST = "zkHost"
private val OUR_PROP_ZKHOST = "zkHost"

private val SYS_PROP_SOLRLOG = "solr.log"
private val OUR_PROP_SOLRLOG = "solrLogs"

private val SYS_PROP_HOSTCONTEXT = "hostContext"
private val OUR_PROP_HOSTCONTEXT = "solrContextPath"

private val SYS_PROP_SOLRHOME = "solr.solr.home"
private val OUR_PROP_SOLRHOME = "solrHome"

private val SYS_PROP_JBOSS_LOGGING = "org.jboss.logging.provider"

private val OUR_PROP_HTTP_IO_THREADS = "httpIoThreads"
private val OUR_PROP_HTTP_WORKER_THREADS = "httpWorkerThreads"

// system and environment variables that need to be treated the same as our configuration items (excluding zkRun)
private val SOLR_OVERRIDES = mapOf(SYS_PROP_JETTY_PORT to OUR_PROP_HTTP_PORT,
        SYS_PROP_ZKHOST to OUR_PROP_ZKHOST,
        SYS_PROP_SOLRLOG to OUR_PROP_SOLRLOG,
        SYS_PROP_HOSTCONTEXT to OUR_PROP_HOSTCONTEXT,
        SYS_PROP_SOLRHOME to OUR_PROP_SOLRHOME)

private class ServerConfigLoader(val configFile: Path) {
    private val solrOverrides = ConfigFactory.parseProperties(getRelevantSystemProperties())!!
    private val userConfig = ConfigFactory.parseFile(configFile.toFile())!!
    private val fullConfig = solrOverrides + userConfig

    val resolvedConfig = ConfigFactory.load(fullConfig, ConfigResolveOptions.noSystem())!! then { config ->
        setRelevantSystemProperties(config)
    }

    fun hasLoggingDir(): Boolean {
        return resolvedConfig.hasPath("${SOLR_UNDERTOW_CONFIG_PREFIX}.${OUR_PROP_SOLRLOG}")
    }

    fun getRelevantSystemProperties(): Properties {
        // copy values from typical Solr system or environment properties into our equivalent configuration item

        val p = Properties()
        for (mapping in SOLR_OVERRIDES.entrySet()) {
            val cfgKey = "${SOLR_UNDERTOW_CONFIG_PREFIX}.${mapping.value}"
            // don't override our own config item set as system property
            if (System.getProperty(cfgKey) == null) {
                val value = System.getProperty(mapping.key) ?: System.getenv(mapping.key)
                if (value != null) {
                    p.put(cfgKey, value)
                }
            }
        }
        if (System.getProperty(SYS_PROP_ZKRUN) ?: System.getenv(SYS_PROP_ZKRUN) != null) {
            val cfgKey = "${SOLR_UNDERTOW_CONFIG_PREFIX}.${OUR_PROP_ZKRUN}"
            // don't override our own config item set as system property
            if (System.getProperty(cfgKey) == null) {
                p.put(cfgKey, "true")
            }
        }
        return p
    }

    fun setRelevantSystemProperties(fromConfig: Config) {
        // copy our configuration items into Solr system properties that might be looked for later by Solr
        for (mapping in SOLR_OVERRIDES.entrySet()) {
            val cfgKey = "${SOLR_UNDERTOW_CONFIG_PREFIX}.${mapping.value}"
            if (fromConfig.hasPath(cfgKey)) {
                val configValue = fromConfig.getString(cfgKey)!!.trim()
                if (configValue.isNotEmpty()) {
                    System.setProperty(mapping.key, configValue)
                }
            }
        }
        val zkRunCfgkey = "${SOLR_UNDERTOW_CONFIG_PREFIX}.${OUR_PROP_ZKRUN}"
        if (fromConfig.hasPath(zkRunCfgkey) && fromConfig.getBoolean(zkRunCfgkey)) {
            System.setProperty(SYS_PROP_ZKRUN, "true")
        }

        // an extra system property to set
        System.setProperty(SYS_PROP_JBOSS_LOGGING, "slf4j")
    }
}

private class ServerConfig(private val log: Logger, private val loader: ServerConfigLoader) {
    val configured = loader.resolvedConfig.getConfig(SOLR_UNDERTOW_CONFIG_PREFIX)!!

    val httpClusterPort = configured.value(OUR_PROP_HTTP_PORT).asInt()
    val httpHost = configured.value("httpHost").asString()
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
    val solrHome = configured.value(OUR_PROP_SOLRHOME).asPath()
    val solrLogs = configured.value(OUR_PROP_SOLRLOG).asPath()
    val tempDir = configured.value("tempDir").asPath()
    val solrVersion = configured.value("solrVersion").asString()
    val solrWarFile = configured.value("solrWarFile").asPath()
    val libExtDir = configured.value("libExtDir").asPath()
    val solrContextPath = configured.value(OUR_PROP_HOSTCONTEXT).asString() let { solrContextPath ->
        if (solrContextPath.isEmpty()) "/" else solrContextPath
    }

    fun hasLibExtDir(): Boolean = configured.value("libExtDir").isNotEmptyString()


    private fun printF(p: KMemberProperty<ServerConfig, Path>) = log.info("  ${p.name}: ${p.get(this)}")
    private fun printS(p: KMemberProperty<ServerConfig, String>) = log.info("  ${p.name}: ${p.get(this)}")
    private fun printSA(p: KMemberProperty<ServerConfig, Array<String>>) = log.info("  ${p.name}: ${p.get(this).joinToString(",")}")
    private fun printB(p: KMemberProperty<ServerConfig, Boolean>) = log.info("  ${p.name}: ${p.get(this)}")
    private fun printI(p: KMemberProperty<ServerConfig, Int>) = log.info("  ${p.name}: ${p.get(this)}")

    fun print() {
        log.info("=== [ Config File settings from: ${loader.configFile} ] ===")
        printB(::zkRun)
        printS(::zkHost)
        printI(::httpClusterPort)
        printS(::httpHost)
        printI(::httpIoThreads)
        printI(::httpWorkerThreads)

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
        if (log.isDebugEnabled()) {
            log.debug("<<<< CONFIGURATION FILE TRACE >>>>")
            log.debug(configured.root()!!.render())
        }
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

        if (hasLibExtDir()) {
            existsIsReadable(::libExtDir)
        }

        return isValid
    }
}


private class RequestLimitConfig(private val log: Logger, val name: String, private val cfg: Config) {
    val exactPaths = if (cfg.hasPath("exactPaths")) cfg.getStringList("exactPaths")!! else listOf<String>()
    val pathSuffixes = if (cfg.hasPath("pathSuffixes")) cfg.getStringList("pathSuffixes")!! else listOf<String>()
    val concurrentRequestLimit = Math.max(cfg.getInt("concurrentRequestLimit"), -1)
    val maxQueuedRequestLimit = Math.max(cfg.getInt("maxQueuedRequestLimit"), -1)


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
        log.info("    concurrentRequestLimit: ${if (concurrentRequestLimit < 0) "unlimited" else Math.min(concurrentRequestLimit, 1) }")
        log.info("    maxQueuedRequestLimit: ${if (maxQueuedRequestLimit < 0) "unlimited" else maxQueuedRequestLimit }")
    }
}


