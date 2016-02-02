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

import uy.klutter.core.common.verifiedBy
import uy.klutter.core.jdk7.notExists
import java.nio.file.Paths

class App {
    companion object {
        @JvmStatic fun main(args: Array<String>) {
            fun printErrorAndExit(msg: String?, errCode: Int = -1) {
                System.err.println(msg ?: "Unknown Error")
                System.exit(errCode)
            }

            try {
                if (args.toList().size != 1) {
                    // TODO: go back to args.size after next Kotlin release (> 0.9.206) because the plugin stdlibrary doesn't match my used standard library
                    printErrorAndExit("A Configuration file must be passed on the command-line (i.e. /my/path/to/solr-undertow.conf)")
                }
                val configFile = Paths.get(args[0]).toAbsolutePath() verifiedBy { path ->
                    if (path.notExists()) {
                        printErrorAndExit("Configuration file does not exist: ${path.toString()}")
                    }
                }

                // load/parse configuration separate from building the ServerConfig object, so that logging is configured before
                // we need it...
                val configLoader = ServerConfigLoaderFromFileAndSolrEnvironment(configFile) verifiedBy { configLoader ->
                    if (!configLoader.hasLoggingDir()) {
                        printErrorAndExit("solr.undertow.solrLogs is missing from configFile, is required for logging")
                    }
                }

                routeJbossLoggingToSlf4j()

                val (serverStarted, message) = Server(configLoader).run()
                if (!serverStarted) {
                    printErrorAndExit(message)
                }

            } catch (ex: Throwable) {
                ex.printStackTrace()
                printErrorAndExit(ex.message)
            }
        }
    }
}
