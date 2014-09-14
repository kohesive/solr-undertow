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

import java.io.File

public fun main(args: Array<String>) {
    try {
        if (args.size != 1) {
            System.err.println("A Configuration file must be passed on the command-line (i.e. /my/path/to/solr-undertow.conf)")
            System.exit(-1)
        }
        val configFile = File(args[0]) verifiedBy { file ->
            if (!file.exists()) {
                System.err.println("Configuration file does not exist: ${file.getAbsolutePath()}")
                System.exit(-1)
            }
        }

        // load/parse configuration separate from building the ServerConfig object, so that logging is configured before
        // we need it...
        val configLoader = ServerConfigLoader(configFile) verifiedBy { configLoader ->
            if (!configLoader.hasLoggingDir()) {
                System.err.println("solr.undertow.solrLogs is missing from configFile, is required for logging")
                System.exit(-1)
            }
        }

        Server(configLoader).run()

    } catch (ex: Throwable) {
        ex.printStackTrace()
        System.exit(-1)
    }
}
