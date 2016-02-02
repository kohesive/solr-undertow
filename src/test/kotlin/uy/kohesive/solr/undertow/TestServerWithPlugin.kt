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
import org.apache.solr.client.solrj.impl.HttpSolrClient
import org.apache.solr.client.solrj.request.CoreAdminRequest
import org.apache.solr.common.params.SolrParams
import org.apache.solr.common.util.NamedList
import org.apache.solr.request.SolrQueryRequest
import org.apache.solr.search.*
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.slf4j.LoggerFactory
import uy.klutter.config.typesafe.MapAsConfig
import uy.klutter.config.typesafe.loadConfig
import uy.klutter.config.typesafe.render
import uy.klutter.core.common.verifiedBy
import uy.klutter.core.jdk7.deleteRecursively
import uy.klutter.core.jdk7.exists
import uy.kohesive.solr.undertow.*
import java.net.URL
import java.nio.file.Files
import java.nio.file.Paths
import kotlin.test.*

class TestServerWithPlugin {

    val workingDir = Paths.get("test-data/solr-standalone").toAbsolutePath()
    val coreWithPluginDir = workingDir.resolve("plugin-test/collection1")


    @Before fun clearSystemProperties() {
        assertTrue(coreWithPluginDir.exists(), "test core w/plugin does not exist $coreWithPluginDir")

        // make sure no system properties are set that could interfere with test
        System.clearProperty(SYS_PROP_ZKRUN)
        for (mapping in SOLR_OVERRIDES) {
            System.clearProperty(mapping.key)
        }
        System.clearProperty(SYS_PROP_JBOSS_LOGGING)

        routeJbossLoggingToSlf4j()

        cleanFiles()
    }

    @After fun cleanUpTempFiles() {
        cleanFiles()
    }

    private fun cleanFiles() {
        coreWithPluginDir.resolve("data").deleteRecursively()
        Files.deleteIfExists(coreWithPluginDir.resolve("core.properties"))
    }

    @Test
    fun testServerLoadsPlugin() {
        val config = mapOf(
                OUR_PROP_SOLR_WAR_ALLOW_OMIT to true,
                OUR_PROP_SOLR_WAR to "",
                OUR_PROP_SOLR_LOG to workingDir.resolve("solr-logs").toString(),
                OUR_PROP_TEMP_DIR to workingDir.resolve("solr-temp").toString(),
                OUR_PROP_SOLR_HOME to workingDir.resolve("solr-home").toString(),
                "shutdown.password" to "!1234!"
        ).mapKeys { SOLR_UNDERTOW_CONFIG_PREFIX + ".${it.key}"}

        val configLoader = ServerConfigFromOverridesAndReference(workingDir, config)

        println(configLoader.resolvedConfig.render())

        val cfg = ServerConfig(LoggerFactory.getLogger("TestServerWithPlugin"), configLoader) verifiedBy {
            if (!it.validate()) {
                fail("invalid configuration")
            }
        }

        val server = Server(configLoader)
        val (serverStarted, message) = server.run()
        if (!serverStarted) {
            fail("Server not started: '$message'")
        }

        val solrAdmin = HttpSolrClient("http://localhost:8983/solr/")
        println("Loading core 'withplugin' from dir ${coreWithPluginDir.toString()}")
        val response = CoreAdminRequest.createCore("collection1", coreWithPluginDir.toString(), solrAdmin)
        assertEquals(0, response.status)

        server.shutdown()
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

}

