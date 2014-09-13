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

package org.collokia.solr.undertow

import kotlin.reflect.KMemberProperty
import com.typesafe.config.Config
import com.typesafe.config.ConfigFactory
import org.slf4j.Logger
import java.io.File
import java.nio.file.Files
import java.util.HashMap

private val SOLR_UNDERTOW_CONFIG_PREFIX = "solr.undertow"
private val SYS_PROP_JETTY_PORT = "jetty.port"

private val SYS_PROP_ZKRUN = "zkRun"
private val OUR_PROP_ZKRUN = SYS_PROP_ZKRUN

private val SYS_PROP_ZKHOST = "zkHost"
private val OUR_PROP_ZKHOST = SYS_PROP_ZKHOST

private val SYS_PROP_SOLRLOG = "solr.log"
private val OUR_PROP_SOLRLOG = "solrLogs"

private val SYS_PROP_SOLRHOME = "solr.solr.home"

private class ServerConfigLoader(val configFile: File) {
    private val propertyOverrides = ConfigFactory.defaultOverrides()!!
    private val propertyDefaults = ConfigFactory.defaultReference()!!
    private val ourRawConfig = ConfigFactory.parseFile(configFile)!!
    val resolvedConfig = run {
        propertyOverrides.withFallback(ourRawConfig)!!.withFallback(propertyDefaults)!!.resolve()!!
    }

    fun fixupLogging() {
        System.setProperty("org.jboss.logging.provider", "slf4j")

        if (!resolvedConfig.hasPath("${SOLR_UNDERTOW_CONFIG_PREFIX}.${OUR_PROP_SOLRLOG}")) {
            System.err.println("solr.undertow.solrLogs is missing from configFile, is required for logging")
            System.exit(-1)
        }

        System.setProperty(SYS_PROP_SOLRLOG, resolvedConfig.getString("${SOLR_UNDERTOW_CONFIG_PREFIX}.${OUR_PROP_SOLRLOG}")!!)
    }
}

private class ServerConfig(private val log: Logger, private val loader: ServerConfigLoader) {
    val cfg = loader.resolvedConfig.getConfig(SOLR_UNDERTOW_CONFIG_PREFIX)!!

    val httpClusterPort = run {
        // Solr configuration files reference this port by default, so let's set it in case they haven't been customized
        val temp = System.getProperty(SYS_PROP_JETTY_PORT) ?: cfg.getString("httpClusterPort")!!
        System.setProperty(SYS_PROP_JETTY_PORT, temp)
        temp.toInt()
    }

    val httpHost = cfg.getString("httpHost")!!
    val httpIoThreads = Math.max(cfg.getInt("httpIoThreads"),0)
    val httpWorkerThreads = Math.max(cfg.getInt("httpWorkerThreads"),0)

    val activeRequestLimits = cfg.getStringList("activeRequestLimits")!!.copyToArray()

    val requestLimiters = run {
        val namedConfigs = cfg.getConfig("requestLimits")!!
        val temp = HashMap<String, RequestLimitConfig>()
        activeRequestLimits.forEach { name ->
            temp.put(name, RequestLimitConfig(log, name, namedConfigs.getConfig(name)!!))
        }
        temp
    }

    val solrHome = run {
        // SOLR references solr.solr.home in config files by default, so set the variable to match our configuration
        val temp = File(cfg.getString("solrHome")!!)
        System.setProperty(SYS_PROP_SOLRHOME, temp.getAbsolutePath())
        temp
    }

    val solrLogs = run {
        // Log configuration references solr.log, so set the variable to match our configuration
        val temp = File(cfg.getString(OUR_PROP_SOLRLOG)!!)
        System.setProperty(SYS_PROP_SOLRLOG, temp.getAbsolutePath())
        temp
    }

    val tempDir = File(cfg.getString("tempDir")!!)

    val solrVersion = cfg.getString("solrVersion")!!

    val solrWarFile = File(cfg.getString("solrWarFile")!!)

    val solrContextPath = cfg.getString("solrContextPath")!!

    val zkRun = run {
        // SOLR looks for zkRun system property, so we can use it if set, and also set it from  our config
        val sysZkRun = if (System.getProperty(SYS_PROP_ZKRUN) != null) true else false

        val zkRunValue = sysZkRun || cfg.getBoolean(OUR_PROP_ZKRUN)
        if (zkRunValue) {
            System.setProperty(SYS_PROP_ZKRUN, "true")
        } else {
            System.clearProperty(SYS_PROP_ZKRUN)
        }
        zkRunValue
    }

    val zkHost = run {
        // SOLR looks for zkHost system property, so we can use it if set, and also set it from  our config
        val zkHostValue = System.getProperty(SYS_PROP_ZKHOST) ?: cfg.getString(OUR_PROP_ZKHOST)!!
        if (zkHostValue.trim().isNotEmpty()) {
            System.setProperty(SYS_PROP_ZKHOST, zkHostValue)
        } else {
            System.clearProperty(SYS_PROP_ZKHOST)
        }
        zkHostValue
    }

    private fun printF(p: KMemberProperty<ServerConfig, File>) = log.info("  ${p.name}: ${p.get(this).getAbsolutePath()}")
    private fun printS(p: KMemberProperty<ServerConfig, String>) = log.info("  ${p.name}: ${p.get(this)}")
    private fun printSA(p: KMemberProperty<ServerConfig, Array<String>>) = log.info("  ${p.name}: ${p.get(this).joinToString(",")}")
    private fun printB(p: KMemberProperty<ServerConfig, Boolean>) = log.info("  ${p.name}: ${p.get(this).toString()}")
    private fun printI(p: KMemberProperty<ServerConfig, Int>) = log.info("  ${p.name}: ${p.get(this).toString()}")

    fun print() {
        log.info("=== [ Config File settings from: ${loader.configFile.getAbsolutePath()} ] ===")
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
        if (log.isDebugEnabled()) {
            log.debug("<<<< CONFIGURATION FILE TRACE >>>>")
            log.debug(cfg.root()!!.render())
        }
        log.info("=== [ END CONFIG ] ===")


    }

    fun validate(): Boolean {
        log.info("Validating configuration from: ${loader.configFile.getAbsolutePath()}")
        var isValid = true
        fun err(msg: String) {
            log.error(msg)
            isValid = false
        }

        fun existsIsWriteable(p: KMemberProperty<ServerConfig, File>) {
            val dir = p.get(this)
            if (!dir.exists()) {
                err("${p.name} dir does not exist: ${dir.getAbsolutePath()}")
            }
            if (!Files.isWritable(dir.toPath()!!)) {
                err("${p.name} dir must be writable by current user: ${dir.getAbsolutePath()}")
            }
        }

        requestLimiters.values().forEach { rl ->
            rl.validate()
        }

        existsIsWriteable(::solrHome)
        existsIsWriteable(::solrLogs)
        existsIsWriteable(::tempDir)

        if (!solrWarFile.exists()) {
            error("solrWarFile must exist, expected WAR filename is: ${solrWarFile.getAbsolutePath()}")
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


