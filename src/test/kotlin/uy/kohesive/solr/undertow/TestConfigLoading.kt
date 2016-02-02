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

package uy.kohesive.solr.undertow.tests

import java.nio.file.Files
import kotlin.test.assertFalse
import org.slf4j.LoggerFactory
import kotlin.test.assertTrue
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertEquals
import org.junit.Before
import org.junit.Test
import uy.kohesive.solr.undertow.*
import java.nio.file.Paths
import java.nio.file.Path

class TestConfigLoading {
    val log = LoggerFactory.getLogger("ConfigTests")

    fun makeEmptyConfig(): ServerConfig {
        return makeConfig("${SOLR_UNDERTOW_CONFIG_PREFIX} { }")
    }

    fun makeConfig(hocon: String): ServerConfig {
        return ServerConfig(log, loadConfigFile(hocon))
    }

    fun loadConfigFile(hocon: String): ServerConfigLoader {
        val tempConfig = Files.createTempFile("UnitTest-ConfigTests", ".conf")
        Files.write(tempConfig, hocon.toByteArray())
        return ServerConfigLoaderFromFileAndSolrEnvironment(tempConfig)
    }

    @Before fun clearSystemProperties() {
        // make sure no system properties are set that could interfere with test
        System.clearProperty(SYS_PROP_ZKRUN)
        for (mapping in SOLR_OVERRIDES) {
            System.clearProperty(mapping.key)
        }
        System.clearProperty(SYS_PROP_JBOSS_LOGGING)

        // mock out the system and environment variables
        SERVER_ENV_WRAPPER = mapOf()
        SERVER_SYS_WRAPPER = hashMapOf()
    }

    @Test fun testThatLoggingForJBossIsSetup() {
        // the system property for jboss logging should magically appear after configuration
        assertNull(SERVER_SYS_WRAPPER.get(SYS_PROP_JBOSS_LOGGING))
        loadConfigFile("")
        assertNotNull(SERVER_SYS_WRAPPER.get(SYS_PROP_JBOSS_LOGGING))
    }

    @Test fun testNoZkRunOrZkHostEnvSysProp() {
        // if system prop zkRun or zkHost is not set, we should be empty in our configuration, and the system properties should be blank after
        assertNull(SERVER_SYS_WRAPPER.get(SYS_PROP_ZKRUN))
        assertNull(SERVER_SYS_WRAPPER.get(SYS_PROP_ZKHOST))
        assertNull(SERVER_ENV_WRAPPER.get(SYS_PROP_ZKRUN))
        assertNull(SERVER_ENV_WRAPPER.get(SYS_PROP_ZKHOST))
        val cfg = makeEmptyConfig()
        assertFalse(cfg.zkRun)
        assertTrue(cfg.zkHost.trim().isEmpty())
        assertNull(SERVER_SYS_WRAPPER.get(SYS_PROP_ZKRUN))
        assertNull(SERVER_SYS_WRAPPER.get(SYS_PROP_ZKHOST))
        assertNull(SERVER_ENV_WRAPPER.get(SYS_PROP_ZKRUN))
        assertNull(SERVER_ENV_WRAPPER.get(SYS_PROP_ZKHOST))
    }


    @Test fun testZkRunPresent() {
        // if system prop zkRun is set before, our config value should be true, and zkRun should exist after
        SERVER_SYS_WRAPPER = hashMapOf(SYS_PROP_ZKRUN to "")
        assertTrue(makeEmptyConfig().zkRun)
        assertNotNull(SERVER_SYS_WRAPPER.get(SYS_PROP_ZKRUN))
    }

    @Test fun testZkHostPresent() {
        val testVal = "one,two,three"
        // if system prop zkHost is set before, our config value should be true, and zkHost should exist after
        SERVER_SYS_WRAPPER = hashMapOf(SYS_PROP_ZKHOST to testVal)
        assertEquals(testVal, makeEmptyConfig().zkHost)
        assertEquals(testVal, SERVER_SYS_WRAPPER.get(SYS_PROP_ZKHOST))
    }

    @Test fun testZkRunAndZkHostNotPresentButSetByConfig() {
        // system props zkRun and zkhost if absent should appear after our configuration
        val testVal = "one,two,three"
        val cfgHocon = """
                    ${SOLR_UNDERTOW_CONFIG_PREFIX} {
                        ${OUR_PROP_ZKRUN}=true
                        ${OUR_PROP_ZKHOST}="${testVal}"
                    }
                  """
        assertNull(SERVER_SYS_WRAPPER.get(SYS_PROP_ZKRUN))
        assertNull(SERVER_SYS_WRAPPER.get(SYS_PROP_ZKHOST))
        val cfg = makeConfig(cfgHocon)
        assertTrue(cfg.zkRun)
        assertEquals(testVal, cfg.zkHost)
        assertNotNull(SERVER_SYS_WRAPPER.get(SYS_PROP_ZKRUN))
        assertEquals(testVal, SERVER_SYS_WRAPPER.get(SYS_PROP_ZKHOST))
    }

    @Test fun testJettyPortHostSolrHomeSolrLogAbsent() {
        // system props jetty.port, solr.solr.home, solr.log if absent should appear after our configuration
        val testPort = 9999
        val testHome = "/my/solr/home"
        val testLogs = "/my/solr/logs"
        val testHost = "0.0.0.0"
        val cfgHocon = """
                    ${SOLR_UNDERTOW_CONFIG_PREFIX} {
                        ${OUR_PROP_HTTP_PORT}=${testPort}
                        ${OUR_PROP_SOLR_HOME}="${testHome}"
                        ${OUR_PROP_SOLR_LOG}="${testLogs}"
                        ${OUR_PROP_HTTP_HOST}="${testHost}"
                    }
                  """
        assertNull(SERVER_SYS_WRAPPER.get(SYS_PROP_JETTY_PORT))
        assertNull(SERVER_SYS_WRAPPER.get(SYS_PROP_SOLR_HOME))
        assertNull(SERVER_SYS_WRAPPER.get(SYS_PROP_SOLR_LOG))

        val cfg = makeConfig(cfgHocon)

        assertEquals(testPort, cfg.httpClusterPort)
        assertEquals(testHost, cfg.httpHost)
        assertEquals(Paths.get(testHome), cfg.solrHome)
        assertEquals(Paths.get(testLogs), cfg.solrLogs)

        assertEquals(testPort.toString(), SERVER_SYS_WRAPPER.get(SYS_PROP_JETTY_PORT))
        assertEquals(testHome, SERVER_SYS_WRAPPER.get(SYS_PROP_SOLR_HOME))
        assertEquals(testLogs, SERVER_SYS_WRAPPER.get(SYS_PROP_SOLR_LOG))
    }

    @Test fun testConfigOverridesEnvironment() {
        // props if set in config and environment, config will win, and end up in system props
        val testPort = 9999
        val testHome = "/my/solr/home"
        val testLogs = "/my/solr/logs"
        val testHost = "0.0.0.0"
        val cfgHocon = """
                    ${SOLR_UNDERTOW_CONFIG_PREFIX} {
                        ${OUR_PROP_HTTP_PORT}=${testPort}
                        ${OUR_PROP_SOLR_HOME}="${testHome}"
                        ${OUR_PROP_SOLR_LOG}="${testLogs}"
                        ${OUR_PROP_HTTP_HOST}="${testHost}"
                    }
                  """
        assertNull(SERVER_SYS_WRAPPER.get(SYS_PROP_JETTY_PORT))
        assertNull(SERVER_SYS_WRAPPER.get(SYS_PROP_SOLR_HOME))
        assertNull(SERVER_SYS_WRAPPER.get(SYS_PROP_SOLR_LOG))

        SERVER_ENV_WRAPPER = mapOf(SYS_PROP_JETTY_PORT to "10101",
                SYS_PROP_SOLR_HOME to "badHome",
                SYS_PROP_SOLR_LOG to "badLog")

        val cfg = makeConfig(cfgHocon)

        assertEquals(testPort, cfg.httpClusterPort)
        assertEquals(testHost, cfg.httpHost)
        assertEquals(Paths.get(testHome), cfg.solrHome)
        assertEquals(Paths.get(testLogs), cfg.solrLogs)

        assertEquals(testPort.toString(), SERVER_SYS_WRAPPER.get(SYS_PROP_JETTY_PORT))
        assertEquals(testHome, SERVER_SYS_WRAPPER.get(SYS_PROP_SOLR_HOME))
        assertEquals(testLogs, SERVER_SYS_WRAPPER.get(SYS_PROP_SOLR_LOG))
    }

    @Test fun testSysPropOverridesConfigOverridesEnvironment() {
        // props if set in sysprop and config, syspropr will win, and if environment, config will win, and end up in system props
        // and our sys prop overrides legacy solr prop
        val testPort = 9999
        val testHome = "/my/solr/home"
        val testLogs = "/my/solr/logs"
        val testHost = "0.0.0.0"
        val cfgHocon = """
                    ${SOLR_UNDERTOW_CONFIG_PREFIX} {
                        ${OUR_PROP_HTTP_PORT}=1234
                        ${OUR_PROP_SOLR_HOME}="badHome"
                        ${OUR_PROP_SOLR_LOG}="${testLogs}"
                        ${OUR_PROP_HTTP_HOST}="${testHost}"
                    }
                  """
        SERVER_SYS_WRAPPER = hashMapOf(SYS_PROP_JETTY_PORT to "5151",
                "$SOLR_UNDERTOW_CONFIG_PREFIX.$OUR_PROP_HTTP_PORT" to testPort.toString(),
                SYS_PROP_SOLR_HOME to testHome)
        SERVER_ENV_WRAPPER = mapOf(SYS_PROP_JETTY_PORT to "10101",
                SYS_PROP_SOLR_HOME to "worseHome",
                SYS_PROP_SOLR_LOG to "worseLog")

        val cfg = makeConfig(cfgHocon)

        assertEquals(testPort, cfg.httpClusterPort)
        assertEquals(testHost, cfg.httpHost)
        assertEquals(Paths.get(testHome), cfg.solrHome)
        assertEquals(Paths.get(testLogs), cfg.solrLogs)

        assertEquals(testPort.toString(), SERVER_SYS_WRAPPER.get(SYS_PROP_JETTY_PORT))
        assertEquals(testHome, SERVER_SYS_WRAPPER.get(SYS_PROP_SOLR_HOME))
        assertEquals(testLogs, SERVER_SYS_WRAPPER.get(SYS_PROP_SOLR_LOG))
    }

    @Test fun testJettyPortSolrHomeSolrLogPresentUsingLegacySysProps() {
        // system props jetty.port, solr.solr.home, solr.log if set before, should set our config, and should still be set after
        val testPort = 1234
        val testHome = "/already/my/solr/home"
        val testLogs = "/already/my/solr/logs"

        SERVER_SYS_WRAPPER = hashMapOf(SYS_PROP_JETTY_PORT to testPort.toString(),
                SYS_PROP_SOLR_HOME to testHome,
                SYS_PROP_SOLR_LOG to testLogs)

        val cfg = makeEmptyConfig()

        assertFalse(cfg.hasLibExtDir())

        assertEquals(testPort, cfg.httpClusterPort)
        assertEquals(Paths.get(testHome), cfg.solrHome)
        assertEquals(Paths.get(testLogs), cfg.solrLogs)

        assertEquals(testPort.toString(), SERVER_SYS_WRAPPER.get(SYS_PROP_JETTY_PORT))
        assertEquals(testHome, SERVER_SYS_WRAPPER.get(SYS_PROP_SOLR_HOME))
        assertEquals(testLogs, SERVER_SYS_WRAPPER.get(SYS_PROP_SOLR_LOG))
    }

    @Test fun testJettyPortSolrHomeSolrLogPresentUsingOurSysProps() {
        // system props jetty.port, solr.solr.home, solr.log if set before, should set our config, and should still be set after
        val testPort = 1234
        val testHome = "/already/my/solr/home"
        val testLogs = "/already/my/solr/logs"

        SERVER_SYS_WRAPPER = hashMapOf("$SOLR_UNDERTOW_CONFIG_PREFIX.$OUR_PROP_HTTP_PORT" to testPort.toString(),
                "$SOLR_UNDERTOW_CONFIG_PREFIX.$OUR_PROP_SOLR_HOME" to testHome,
                "$SOLR_UNDERTOW_CONFIG_PREFIX.$OUR_PROP_SOLR_LOG" to testLogs)

        val cfg = makeEmptyConfig()

        assertFalse(cfg.hasLibExtDir())

        assertEquals(testPort, cfg.httpClusterPort)
        assertEquals(Paths.get(testHome), cfg.solrHome)
        assertEquals(Paths.get(testLogs), cfg.solrLogs)

        assertEquals(testPort.toString(), SERVER_SYS_WRAPPER.get(SYS_PROP_JETTY_PORT))
        assertEquals(testHome, SERVER_SYS_WRAPPER.get(SYS_PROP_SOLR_HOME))
        assertEquals(testLogs, SERVER_SYS_WRAPPER.get(SYS_PROP_SOLR_LOG))
    }

    @Test fun testJettyPortSolrHomeSolrLogPresentUsingEnv() {
        // Environment props jetty.port, solr.solr.home, solr.log if set before, should set our config, and should be set as system props after
        val testPort = 1234
        val testHome = "/already/my/solr/home"
        val testLogs = "/already/my/solr/logs"

        SERVER_ENV_WRAPPER = mapOf(SYS_PROP_JETTY_PORT to testPort.toString(),
                SYS_PROP_SOLR_HOME to testHome,
                SYS_PROP_SOLR_LOG to testLogs)

        val cfg = makeEmptyConfig()

        assertFalse(cfg.hasLibExtDir())

        assertEquals(testPort, cfg.httpClusterPort)
        assertEquals(Paths.get(testHome), cfg.solrHome)
        assertEquals(Paths.get(testLogs), cfg.solrLogs)

        assertEquals(testPort.toString(), SERVER_SYS_WRAPPER.get(SYS_PROP_JETTY_PORT))
        assertEquals(testHome, SERVER_SYS_WRAPPER.get(SYS_PROP_SOLR_HOME))
        assertEquals(testLogs, SERVER_SYS_WRAPPER.get(SYS_PROP_SOLR_LOG))
    }


    @Test fun testJettyPortSolrHomeSolrLogPresentUsingMixEnvAndSys() {
        // Environment or System props jetty.port, solr.solr.home, solr.log if set before, should set our config, and should be set as system props after
        val testPort = 1234
        val testHome = "/already/my/solr/home"
        val testLogs = "/already/my/solr/logs"

        SERVER_ENV_WRAPPER = mapOf(SYS_PROP_JETTY_PORT to testPort.toString(),
                SYS_PROP_SOLR_LOG to testLogs)
        SERVER_SYS_WRAPPER = hashMapOf(SYS_PROP_SOLR_HOME to testHome)

        val cfg = makeEmptyConfig()

        assertFalse(cfg.hasLibExtDir())

        assertEquals(testPort, cfg.httpClusterPort)
        assertEquals(Paths.get(testHome), cfg.solrHome)
        assertEquals(Paths.get(testLogs), cfg.solrLogs)

        assertEquals(testPort.toString(), SERVER_SYS_WRAPPER.get(SYS_PROP_JETTY_PORT))
        assertEquals(testHome, SERVER_SYS_WRAPPER.get(SYS_PROP_SOLR_HOME))
        assertEquals(testLogs, SERVER_SYS_WRAPPER.get(SYS_PROP_SOLR_LOG))
    }

    @Test fun testSysPropWinsOverEnv() {
        // System property wins over Env property
        val testPort = 1234
        val testHome = "/already/my/solr/home"
        val testLogs = "/already/my/solr/logs"

        SERVER_ENV_WRAPPER = mapOf(SYS_PROP_JETTY_PORT to "9999",
                SYS_PROP_SOLR_HOME to "badhome",
                SYS_PROP_SOLR_LOG to "badlog")
        SERVER_SYS_WRAPPER = hashMapOf(SYS_PROP_JETTY_PORT to testPort.toString(),
                SYS_PROP_SOLR_HOME to testHome,
                SYS_PROP_SOLR_LOG to testLogs)

        val cfg = makeEmptyConfig()

        assertFalse(cfg.hasLibExtDir())

        assertEquals(testPort, cfg.httpClusterPort)
        assertEquals(Paths.get(testHome), cfg.solrHome)
        assertEquals(Paths.get(testLogs), cfg.solrLogs)

        assertEquals(testPort.toString(), SERVER_SYS_WRAPPER.get(SYS_PROP_JETTY_PORT))
        assertEquals(testHome, SERVER_SYS_WRAPPER.get(SYS_PROP_SOLR_HOME))
        assertEquals(testLogs, SERVER_SYS_WRAPPER.get(SYS_PROP_SOLR_LOG))
    }

    @Test fun testThreadMinimum() {
        run {
            val cfgHocon = """
                    ${SOLR_UNDERTOW_CONFIG_PREFIX} {
                        ${OUR_PROP_HTTP_IO_THREADS} = -1
                        ${OUR_PROP_HTTP_WORKER_THREADS} = -3
                    }
                  """
            val cfg = makeConfig(cfgHocon)
            assertEquals(0, cfg.httpIoThreads)
            assertEquals(0, cfg.httpWorkerThreads)
        }

        run {
            val cfgHocon = """
                    ${SOLR_UNDERTOW_CONFIG_PREFIX} {
                        ${OUR_PROP_HTTP_IO_THREADS} = 0
                        ${OUR_PROP_HTTP_WORKER_THREADS} = 0
                    }
                  """
            val cfg = makeConfig(cfgHocon)
            assertEquals(0, cfg.httpIoThreads)
            assertEquals(0, cfg.httpWorkerThreads)
        }

        run {
            val cfgHocon = """
                    ${SOLR_UNDERTOW_CONFIG_PREFIX} {
                        ${OUR_PROP_HTTP_IO_THREADS} = 1
                        ${OUR_PROP_HTTP_WORKER_THREADS} = 3
                    }
                  """
            val cfg = makeConfig(cfgHocon)
            assertEquals(1, cfg.httpIoThreads)
            assertEquals(3, cfg.httpWorkerThreads)
        }
    }

    @Test fun testPathsAreRelativeToConfig() {
        val testHome = "./home"
        val testLogs = "./log"
        val testWar = "./war/something.war"
        val testTemp = "./temp"
        val testLibExt = "./libext"
        val cfgHocon = """
                    ${SOLR_UNDERTOW_CONFIG_PREFIX} {
                        ${OUR_PROP_SOLR_HOME}="${testHome}"
                        ${OUR_PROP_SOLR_LOG}="${testLogs}"
                        ${OUR_PROP_SOLR_WAR}="${testWar}"
                        ${OUR_PROP_TEMP_DIR}="${testTemp}"
                        ${OUR_PROP_LIBEXT_DIR}="${testLibExt}"
                    }
                  """

        val cfg = makeConfig(cfgHocon)
        val cfgFile = cfg.loader.workingDir
        fun String.toCfgDir(): Path = cfgFile.resolve(this).toAbsolutePath()

        assertEquals(testHome.toCfgDir(), cfg.solrHome)
        assertEquals(testLogs.toCfgDir(), cfg.solrLogs)
        assertEquals(testWar.toCfgDir(), cfg.solrWarFile)
        assertEquals(testTemp.toCfgDir(), cfg.tempDir)
        assertEquals(testLibExt.toCfgDir(), cfg.libExtDir)
        assertTrue(cfg.hasLibExtDir())

        assertEquals(testHome.toCfgDir().toString(), SERVER_SYS_WRAPPER.get(SYS_PROP_SOLR_HOME))
        assertEquals(testLogs.toCfgDir().toString(), SERVER_SYS_WRAPPER.get(SYS_PROP_SOLR_LOG))
    }
}
