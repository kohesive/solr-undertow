package org.bremeld.solr.undertow

import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.BasicFileAttributes
import kotlin.test.assertFalse
import org.slf4j.LoggerFactory
import kotlin.test.assertTrue
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertEquals
import org.junit.Before
import org.junit.Test
import java.nio.file.Paths

class ConfigTests {
    val log = LoggerFactory.getLogger("ConfigTests")!!

    fun makeEmptyConfig(): ServerConfig {
       return makeConfig("${SOLR_UNDERTOW_CONFIG_PREFIX} { }")
    }

    fun makeConfig(hocon: String): ServerConfig {
       return ServerConfig(log, loadConfigFile(hocon))
    }

    fun loadConfigFile(hocon: String): ServerConfigLoader {
        val tempConfig = Files.createTempFile("UnitTest-ConfigTests", ".conf")
        Files.write(tempConfig, hocon.toByteArray(defaultCharset))
        return ServerConfigLoader(tempConfig)
    }

    [Before] fun clearSystemProperties() {
        // make sure no system properties are set that could interfere with test
         System.clearProperty(SYS_PROP_ZKRUN)
         for (mapping in SOLR_OVERRIDES) {
            System.clearProperty(mapping.getKey())
         }
        System.clearProperty(SYS_PROP_JBOSS_LOGGING)
    }

    [Test] fun testThatLoggingForJBossIsSetup() {
        // the system property for jboss logging should magically appear after configuration
        assertNull(System.getProperty(SYS_PROP_JBOSS_LOGGING))
        loadConfigFile("")
        assertNotNull(System.getProperty(SYS_PROP_JBOSS_LOGGING))
    }

    [Test] fun testNoZkRunOrZkHost() {
        // if system prop zkRun or zkHost is not set, we should be empty in our configuration, and the system properties should be blank after
        assertNull(System.getProperty(SYS_PROP_ZKRUN))
        assertNull(System.getProperty(SYS_PROP_ZKHOST))
        val cfg = makeEmptyConfig()
        assertFalse(cfg.zkRun)
        assertTrue(cfg.zkHost.trim().isEmpty())
        assertNull(System.getProperty(SYS_PROP_ZKRUN))
        assertNull(System.getProperty(SYS_PROP_ZKHOST))
    }

    [Test] fun testZkRunPresent() {
       // if system prop zkRun is set before, our config value should be true, and zkRun should exist after
        System.setProperty(SYS_PROP_ZKRUN, "")
        assertTrue(makeEmptyConfig().zkRun)
        assertNotNull(System.getProperty(SYS_PROP_ZKRUN))
    }

    [Test] fun testZkHostPresent() {
        val testVal = "one,two,three"
        // if system prop zkHost is set before, our config value should be true, and zkHost should exist after
        System.setProperty(SYS_PROP_ZKHOST, testVal)
        assertEquals(testVal, makeEmptyConfig().zkHost)
        assertEquals(testVal, System.getProperty(SYS_PROP_ZKHOST))
    }

    [Test] fun testZkRunAndZkHostNotPresentButSetByConfig() {
        // system props zkRun and zkhost if absent should appear after our configuration
        val testVal = "one,two,three"
        val cfgHocon = """
                    ${SOLR_UNDERTOW_CONFIG_PREFIX} {
                        ${OUR_PROP_ZKRUN}=true
                        ${OUR_PROP_ZKHOST}="${testVal}"
                    }
                  """
        assertNull(System.getProperty(SYS_PROP_ZKRUN))
        assertNull(System.getProperty(SYS_PROP_ZKHOST))
        val cfg = makeConfig(cfgHocon)
        assertTrue(cfg.zkRun)
        assertEquals(testVal, cfg.zkHost)
        assertNotNull(System.getProperty(SYS_PROP_ZKRUN))
        assertEquals(testVal, System.getProperty(SYS_PROP_ZKHOST))
    }

    [Test] fun testJettyPortSolrHomeSolrLogAbsent() {
        // system props jetty.port, solr.solr.home, solr.log if absent should appear after our configuration
        val testPort = 9999
        val testHome = "/my/solr/home"
        val testLogs = "/my/solr/logs"
        val cfgHocon = """
                    ${SOLR_UNDERTOW_CONFIG_PREFIX} {
                        ${OUR_PROP_HTTP_PORT}=${testPort}
                        ${OUR_PROP_SOLRHOME}="${testHome}"
                        ${OUR_PROP_SOLRLOG}="${testLogs}"
                    }
                  """
        assertNull(System.getProperty(SYS_PROP_JETTY_PORT))
        assertNull(System.getProperty(SYS_PROP_SOLRHOME))
        assertNull(System.getProperty(SYS_PROP_SOLRLOG))

        val cfg = makeConfig(cfgHocon)

        assertEquals(testPort, cfg.httpClusterPort)
        assertEquals(Paths.get(testHome), cfg.solrHome)
        assertEquals(Paths.get(testLogs), cfg.solrLogs)

        assertEquals(testPort.toString(), System.getProperty(SYS_PROP_JETTY_PORT))
        assertEquals(testHome, System.getProperty(SYS_PROP_SOLRHOME))
        assertEquals(testLogs, System.getProperty(SYS_PROP_SOLRLOG))
    }

    [Test] fun testJettyPortSolrHomeSolrLogPresent() {
        // system props jetty.port, solr.solr.home, solr.log if set before, should set our config, and should still be set after
        val testPort = 1234
        val testHome = "/already/my/solr/home"
        val testLogs = "/already/my/solr/logs"

        System.setProperty(SYS_PROP_JETTY_PORT, testPort.toString())
        System.setProperty(SYS_PROP_SOLRHOME, testHome)
        System.setProperty(SYS_PROP_SOLRLOG, testLogs)

        val cfg = makeEmptyConfig()

        assertEquals(testPort, cfg.httpClusterPort)
        assertEquals(Paths.get(testHome), cfg.solrHome)
        assertEquals(Paths.get(testLogs), cfg.solrLogs)

        assertEquals(testPort.toString(), System.getProperty(SYS_PROP_JETTY_PORT))
        assertEquals(testHome, System.getProperty(SYS_PROP_SOLRHOME))
        assertEquals(testLogs, System.getProperty(SYS_PROP_SOLRLOG))
    }

    [Test] fun testThreadMinimum() {
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
}
