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

import java.nio.file.Paths
import java.nio.file.Files

public fun main(args: Array<String>) {
    try {
        if (args.size != 1) {
            printErrorAndExit("A Configuration file must be passed on the command-line (i.e. /my/path/to/solr-undertow.conf)")
        }
        val configFile = Paths.get(args[0])!!.toAbsolutePath() verifiedBy { path ->
            if (!Files.exists(path)) {
                System.err.println("Configuration file does not exist: ${path.toString()}")
                System.exit(-1)
            }
        }

        // load/parse configuration separate from building the ServerConfig object, so that logging is configured before
        // we need it...
        val configLoader = ServerConfigLoader(configFile) verifiedBy { configLoader ->
            if (!configLoader.hasLoggingDir()) {
                printErrorAndExit("solr.undertow.solrLogs is missing from configFile, is required for logging")
            }
        }

        val (serverStarted, message) = Server(configLoader).run()
        if (!serverStarted) {
            printErrorAndExit(message)
        }

    } catch (ex: Throwable) {
        ex.printStackTrace()
        printErrorAndExit(ex.getMessage())
    }
}
