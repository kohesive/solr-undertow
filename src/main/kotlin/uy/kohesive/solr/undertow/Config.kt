//    Copyright 2014, 2015, 2016 Bremeld Corp SA (Montevideo, Uruguay)
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

package uy.kohesive.solr.undertow

import com.typesafe.config.Config
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import uy.klutter.config.typesafe.*
import uy.klutter.config.typesafe.jdk7.FileConfig
import uy.klutter.config.typesafe.jdk7.asPathList
import uy.klutter.config.typesafe.jdk7.asPathRelative
import uy.klutter.core.common.initializedBy
import uy.klutter.core.jdk.maximum
import uy.klutter.core.jdk.minimum
import uy.klutter.core.jdk.nullIfEmpty
import uy.klutter.core.jdk7.notExists
import java.nio.file.Files
import java.nio.file.Path
import java.util.*
import java.util.concurrent.TimeUnit
import kotlin.reflect.KProperty1

val SOLR_UNDERTOW_CONFIG_PREFIX = "solr.undertow"

val SYS_PROP_JETTY_PORT = "jetty.port"
val OUR_PROP_HTTP_PORT = "httpClusterPort"

val SYS_PROP_ZKRUN = "zkRun"
val OUR_PROP_ZKRUN = "zkRun"

val SYS_PROP_ZKHOST = "zkHost"
val OUR_PROP_ZKHOST = "zkHost"

val SYS_PROP_SOLR_LOG = "solr.log"
val OUR_PROP_SOLR_LOG = "solrLogs"

val SYS_PROP_HOST_CONTEXT = "hostContext"
val OUR_PROP_HOST_CONTEXT = "solrContextPath"

val OUR_PROP_HTTP_HOST = "httpHost"

val SYS_PROP_SOLR_HOME = "solr.solr.home"
val OUR_PROP_SOLR_HOME = "solrHome"

val SYS_PROP_JBOSS_LOGGING = "org.jboss.logging.provider"

val OUR_PROP_HTTP_IO_THREADS = "httpIoThreads"
val OUR_PROP_HTTP_WORKER_THREADS = "httpWorkerThreads"
val OUR_PROP_SOLR_WAR = "solrWarFile"
val OUR_PROP_SOLR_WAR_ALLOW_OMIT = "solrWarCanBeOmitted"
val OUR_PROP_SOLR_VERSION = "solrVersion"
val OUR_PROP_TEMP_DIR = "tempDir"
val OUR_PROP_LIBEXT_DIR = "libExtDir"


// system and environment variables that need to be treated the same as our configuration items
val SOLR_OVERRIDES = mapOf(SYS_PROP_JETTY_PORT to OUR_PROP_HTTP_PORT,
        SYS_PROP_ZKRUN to OUR_PROP_ZKRUN,
        SYS_PROP_ZKHOST to OUR_PROP_ZKHOST,
        SYS_PROP_SOLR_LOG to OUR_PROP_SOLR_LOG,
        SYS_PROP_HOST_CONTEXT to OUR_PROP_HOST_CONTEXT,
        SYS_PROP_SOLR_HOME to OUR_PROP_SOLR_HOME)
val SYS_PROPERTIES_THAT_ARE_PATHS = setOf(SYS_PROP_SOLR_LOG, SYS_PROP_SOLR_HOME)

// Allow substitution of Env variables for unit tests.
@Volatile var SERVER_ENV_PROXY: EnvProxy = RealEnvProxy()
fun resetEnvProxy() { SERVER_ENV_PROXY = RealEnvProxy() }

interface EnvProxy {
    fun readEnvProperty(key: String): Any? = System.getenv(key)
    fun readSysProperty(key: String): Any? = System.getProperty(key)
    fun writeSysProperty(key: String, value: String): Unit {
        System.setProperty(key, value)
    }
    fun clearSysProperty(key: String): Unit { System.clearProperty(key) }

    fun envPropertiesAsMap(): Map<out Any, Any> = System.getenv()
    fun sysPropertiesAsMap(): Map<out Any, Any> = System.getProperties()
}

class RealEnvProxy: EnvProxy {}

interface ServerConfigLoader {
    val workingDir: Path
    val resolvedConfig: Config
}

fun routeJbossLoggingToSlf4j() {
    SERVER_ENV_PROXY.writeSysProperty(SYS_PROP_JBOSS_LOGGING, "slf4j")
}

abstract class ServerConfigReplicatedToSysProps: ServerConfigLoader {
    protected fun writeRelevantSystemProperties(fromConfig: Config) {
        // copy our configuration items into Solr system properties that might be looked for later by Solr
        for (mapping in SOLR_OVERRIDES.entries) {
            val cfgKey = "${SOLR_UNDERTOW_CONFIG_PREFIX}.${mapping.value}"
            val configValue = fromConfig.value(cfgKey)
            if (configValue.exists()) {
                if (configValue.isNotEmptyString()) {
                    val value = if (SYS_PROPERTIES_THAT_ARE_PATHS.contains(mapping.key)) {
                        configValue.asPathRelative(workingDir).normalize().toString()
                    }
                    else {
                        configValue.asString()
                    }
                    SERVER_ENV_PROXY.writeSysProperty(mapping.key, value)
                }
            }
        }
        val zkRunCfgkey = fromConfig.value("${SOLR_UNDERTOW_CONFIG_PREFIX}.${OUR_PROP_ZKRUN}")
        if (zkRunCfgkey.notExists() || !zkRunCfgkey.asBoolean()) {
            SERVER_ENV_PROXY.clearSysProperty(SYS_PROP_ZKRUN)
        }
    }
}

val referenceConfigLoader = ResourceConfig("solr-undertow-reference.conf")

class ServerConfigFromOverridesAndReference(override val workingDir: Path, private val overrideConfig: Map<String, Any>): ServerConfigReplicatedToSysProps() {
    override val resolvedConfig = loadConfig(MapAsConfig(overrideConfig), referenceConfigLoader) then { config ->
        writeRelevantSystemProperties(config)
    }
}

class ServerConfigLoaderFromFileAndSolrEnvironment(private val configFile: Path): ServerConfigReplicatedToSysProps() {
    override val workingDir: Path = configFile.parent

    // our config chain wants Env variables to override Reference Config because that is how Solr would work,
    // but then explicit configuration properties override those, and any system properties override everything.
    //
    // No configuration variables are resolved until the end, so a variable can be used in a lower level, and fulfilled
    // by a higher.
    override val resolvedConfig = loadConfig(PropertiesAsConfig(readRelevantProperties(SERVER_ENV_PROXY.sysPropertiesAsMap())),
                                    FileConfig(configFile),
                                    PropertiesAsConfig(readRelevantProperties(SERVER_ENV_PROXY.envPropertiesAsMap())),
                                    referenceConfigLoader) then { config ->
        writeRelevantSystemProperties(config)
        routeJbossLoggingToSlf4j()
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
                val temp = props.get(solrPropName)
                if (temp != null) "true" else null
            }
            else {
                props.get(solrPropName)
            }

            // don't override our own config item set as  property
            val value = props.get(ourPropFQName) ?: envPropValue
            if (value != null) {
                p.put(ourPropFQName, value)
            }
        }

        return p
    }

}

class ServerConfig(private val log: Logger, val loader: ServerConfigLoader) {
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
    val solrHome = configured.value(OUR_PROP_SOLR_HOME).asPathRelative(loader.workingDir).normalize()
    val solrLogs = configured.value(OUR_PROP_SOLR_LOG).asPathRelative(loader.workingDir).normalize()
    val tempDir = configured.value(OUR_PROP_TEMP_DIR).asPathRelative(loader.workingDir).normalize()
    val tempDirSymLinksAllow = configured.value("tempDirSymLinksAllow").asBoolean(false)
    val tempDirSymLinksSafePaths = configured.value("tempDirSymLinksSafePaths").asPathList().map(Path::normalize)
    val solrVersion = configured.value(OUR_PROP_SOLR_VERSION).asString()

    private val solrWarFileString = configured.value(OUR_PROP_SOLR_WAR).asStringOrNull().nullIfEmpty()
    val solrWarFile: Path? = solrWarFileString?.let { loader.workingDir.resolve(solrWarFileString).normalize() }
    val solrWarCanBeOmitted = configured.value(OUR_PROP_SOLR_WAR_ALLOW_OMIT).asBoolean(false)

    private val libExtDirString = configured.value(OUR_PROP_LIBEXT_DIR).asStringOrNull().nullIfEmpty()
    val libExtDir: Path? = libExtDirString?.let { loader.workingDir.resolve(libExtDirString).normalize() }

    val solrContextPath = configured.value(OUR_PROP_HOST_CONTEXT).asString().let { solrContextPath ->
        if (!solrContextPath.startsWith("/")) "/" + solrContextPath else solrContextPath
    }

    val accessLogFormat = configured.value("accessLogFormat").asString()
    val accessLogger = LoggerFactory.getLogger("http.access")!!
    val accessLogEnableRequestTiming = configured.value("accessLogEnableRequestTiming").asBoolean()

    fun hasLibExtDir(): Boolean = libExtDir != null
    fun omittingSolrWarLegally(): Boolean = solrWarFile == null && solrWarCanBeOmitted

    private fun printF(p: KProperty1<ServerConfig, Path?>) = log.info("  ${p.name}: ${p.get(this)}")
    private fun printS(p: KProperty1<ServerConfig, String>) = log.info("  ${p.name}: ${p.get(this)}")
    private fun printSA(p: KProperty1<ServerConfig, Array<String>>) = log.info("  ${p.name}: ${p.get(this).joinToString(",")}")
    private fun printB(p: KProperty1<ServerConfig, Boolean>) = log.info("  ${p.name}: ${p.get(this)}")
    private fun printI(p: KProperty1<ServerConfig, Int>) = log.info("  ${p.name}: ${p.get(this)}")
    private fun printI(p: KProperty1<ServerConfig, Int>, defaultVal: Int) = log.info("  ${p.name}: ${p.get(this)} ${if (p.get(this) == defaultVal) "(no setting, using default)" else ""}")

    fun print() {
        log.info("=== [ Config Settings ] ===")
        log.info("  working dir: ${loader.workingDir}")

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
        printB(ServerConfig::solrWarCanBeOmitted)
        printS(ServerConfig::solrContextPath)
        if (hasLibExtDir()) {
            printF(ServerConfig::libExtDir)
        }

        log.debug { "<<<< CONFIGURATION FILE TRACE >>>>" }
        log.debug { configured.render() }

        log.info("=== [ END CONFIG ] ===")


    }

    fun validate(): Boolean {
        log.info("Validating configuration...")
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

        fun existsIsReadable(p: KProperty1<ServerConfig, Path?>) {
            val dir = p.get(this)
            if (dir != null) {
                if (dir.notExists()) {
                    err("${p.name} does not exist: ${dir}")
                }
                if (!Files.isReadable(dir)) {
                    err("${p.name} must be readable by current user: ${dir}")
                }
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
        if (omittingSolrWarLegally()) {
            // is ok, allowed to omit the war sometimes
        } else if (!distributionFilename.endsWith(".war") && !distributionFilename.endsWith(".zip")) {
            err("Distribution file should be a WAR file or a ZIP file, instead it is: ${solrWarFile}")
        }

        if (hasLibExtDir()) {
            existsIsReadable(ServerConfig::libExtDir)
        }

        return isValid
    }
}

class ShutdownConfig(private val log: Logger, private val cfg: Config) {
    val httpPort = cfg.value("httpPort").asInt()
    val httpHost = cfg.value("httpHost").asString()
    val password = cfg.value("password").asString()
    val gracefulDelay = cfg.value("gracefulDelay").parseDuration(TimeUnit.MILLISECONDS)
    val gracefulDelayString = cfg.value("gracefulDelay").asString()
}

class RequestLimitConfig(private val log: Logger, val name: String, private val cfg: Config) {
    val exactPaths = cfg.value("exactPaths").asStringListOrEmpty()
    val pathSuffixes = cfg.value("pathSuffixes").asStringListOrEmpty()
    val concurrentRequestLimit = cfg.value("concurrentRequestLimit").asInt().minimum(-1)
    val maxQueuedRequestLimit = cfg.value("maxQueuedRequestLimit").asInt().minimum(-1)

    val maxReqPerSecond = cfg.value("maxReqPerSecond").asIntOrNull()?.minimum(-1) ?: -1
    val throttledReqPerSecondMinPauseMillis =  cfg.value("throttledReqPerSecondMinPauseMillis").asIntOrNull()?.minimum(0) ?: 0
    val throttledReqPerSecondMaxPauseMillis =  cfg.value("throttledReqPerSecondMaxPauseMillis").asIntOrNull()?.minimum(0) ?: 0
    val overLimitReqPerSecondHttpErrorCode =  cfg.value("overLimitReqPerSecondHttpErrorCode").asIntOrNull()?.minimum(300)?.maximum(999) ?: 503

    fun validate(): Boolean {
        if (exactPaths.isEmpty() && pathSuffixes.isEmpty()) {
            log.error("${name}: exactPaths AND/OR pathSuffixes is required in rate limitter")
            return false
        }
        return true
    }

    fun print() {
        log.info("  ${name} >>")
        log.info("    exactPaths:                           ${exactPaths.joinToString(",")}")
        log.info("    pathSuffixes:                         ${pathSuffixes.joinToString(",")}")
        log.info("    concurrentRequestLimit:               ${if (concurrentRequestLimit < 0) "unlimited" else concurrentRequestLimit.minimum(1).toString() }")
        log.info("    maxQueuedRequestLimit:                ${if (maxQueuedRequestLimit < 0) "unlimited" else maxQueuedRequestLimit.toString() }")
        log.info("    maxReqPerSecond:                      ${if (maxReqPerSecond <= 0) "unlimited" else maxReqPerSecond.toString() }")
        log.info("    throttledReqPerSecondMinPauseMillis:  ${if (throttledReqPerSecondMinPauseMillis <= 0) "no pause" else throttledReqPerSecondMinPauseMillis.toString() }")
        log.info("    throttledReqPerSecondMaxPauseMillis:  ${if (throttledReqPerSecondMaxPauseMillis <= 0) "no pause" else throttledReqPerSecondMaxPauseMillis.toString() }")
        log.info("    overLimitReqPerSecondHttpErrorCode:   ${overLimitReqPerSecondHttpErrorCode}")
    }
}


