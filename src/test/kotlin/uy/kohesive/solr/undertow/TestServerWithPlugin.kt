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

import org.apache.lucene.search.IndexSearcher
import org.apache.lucene.search.Query
import org.apache.solr.client.solrj.SolrClient
import org.apache.solr.client.solrj.impl.HttpSolrClient
import org.apache.solr.client.solrj.request.CoreAdminRequest
import org.apache.solr.common.params.SolrParams
import org.apache.solr.common.util.NamedList
import org.apache.solr.core.SolrCore
import org.apache.solr.request.SolrQueryRequest
import org.apache.solr.search.*
import org.junit.*
import org.slf4j.LoggerFactory
import uy.klutter.config.typesafe.render
import uy.klutter.core.common.verifiedBy
import uy.klutter.core.jdk7.deleteRecursively
import uy.klutter.core.jdk7.exists
import uy.kohesive.solr.undertow.*
import java.nio.file.Files
import java.nio.file.Paths
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail

class TestServerWithPlugin {
    companion object {
        val workingDir = Paths.get("test-data/solr-standalone").toAbsolutePath()
        val solrVersion = SolrCore::class.java.`package`.specificationVersion
        val coreWithPluginDir = if (solrVersion.substringBefore('.').toInt() >= 6) {
            workingDir.resolve("plugin-test/collection-solr6")
        } else {
            workingDir.resolve("plugin-test/collection1")
        }


        lateinit var server: Server

        @BeforeClass @JvmStatic fun setup() {
            assertTrue(coreWithPluginDir.exists(), "test core w/plugin does not exist $coreWithPluginDir")

            // make sure no system properties are set that could interfere with test
            resetEnvProxy()
            cleanSysProps()

            routeJbossLoggingToSlf4j()

            cleanFiles()

            val config = mapOf(
                    OUR_PROP_SOLR_WAR_ALLOW_OMIT to true,
                    OUR_PROP_SOLR_WAR to "",
                    OUR_PROP_SOLR_VERSION to SolrCore::class.java.`package`.specificationVersion,
                    OUR_PROP_SOLR_LOG to workingDir.resolve("solr-logs").toString(),
                    OUR_PROP_TEMP_DIR to workingDir.resolve("solr-temp").toString(),
                    OUR_PROP_SOLR_HOME to workingDir.resolve("solr-home").toString(),
                    "shutdown.password" to "!1234!"
            ).mapKeys { SOLR_UNDERTOW_CONFIG_PREFIX + ".${it.key}" }

            val configLoader = ServerConfigFromOverridesAndReference(workingDir, config) verifiedBy { loader ->
                println(loader.resolvedConfig.render())

                ServerConfig(LoggerFactory.getLogger(TestServerWithPlugin::class.java), loader) verifiedBy {
                    if (!it.validate()) {
                        fail("invalid configuration")
                    }
                }
            }

            assertNotNull(System.getProperty("solr.solr.home"))

            server = Server(configLoader)
            val (serverStarted, message) = server.run()
            if (!serverStarted) {
                fail("Server not started: '$message'")
            }
        }

        @AfterClass @JvmStatic fun teardown() {
            server.shutdown()
            cleanFiles()
            resetEnvProxy()
            cleanSysProps()
        }

        private fun cleanSysProps() {
            System.clearProperty(SYS_PROP_ZKRUN)
            for (mapping in SOLR_OVERRIDES) {
                System.clearProperty(mapping.key)
            }
            System.clearProperty(SYS_PROP_JBOSS_LOGGING)
        }

        private fun cleanFiles() {
            // don't leave any test files behind
            coreWithPluginDir.resolve("data").deleteRecursively()
            Files.deleteIfExists(coreWithPluginDir.resolve("core.properties"))
            Files.deleteIfExists(coreWithPluginDir.resolve("core.properties.unloaded"))
        }
    }

    val adminClient: SolrClient = HttpSolrClient("http://localhost:8983/solr/")

    @Before fun prepareTest() {
        // anything before each test?
    }

    @After fun cleanupTest() {
        unloadCoreIfExists("tempCollection1")
        unloadCoreIfExists("tempCollection2")
        unloadCoreIfExists("tempCollection3")
    }

    private fun unloadCoreIfExists(name: String) {
        try {
            CoreAdminRequest.unloadCore(name, adminClient)
        } catch (ex: Throwable) {
            // nop
        }
    }

    @Test
    fun testServerLoadsPlugin() {
        println("Loading core 'withplugin' from dir ${coreWithPluginDir.toString()}")
        val response = CoreAdminRequest.createCore("tempCollection1", coreWithPluginDir.toString(), adminClient)
        assertEquals(0, response.status)
    }
}

class NothingQueryParserPlugin : QParserPlugin() {
    override fun init(args: NamedList<*>?) {

    }

    override fun createParser(qstr: String, localParams: SolrParams, params: SolrParams, req: SolrQueryRequest): QParser {
        return object : QParser(qstr, localParams, params, req) {
            override fun parse(): Query {
                return NothingQuery()
            }

        }
    }

}

class NothingQuery : ExtendedQueryBase(), PostFilter {
    override fun getCache(): Boolean {
        return false
    }

    override fun getCost(): Int {
        return 100
    }

    override fun getFilterCollector(searcher: IndexSearcher): DelegatingCollector {
        return object : DelegatingCollector() {
            override fun collect(doc: Int) {
                // nothing, filter out all docs
            }
        }
    }

    override fun equals(other: Any?): Boolean {
        return other is NothingQuery
    }

    override fun hashCode(): Int {
        return 999
    }
}

