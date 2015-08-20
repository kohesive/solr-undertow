//    Copyright 2014, 2015 Bremeld Corp SA (Montevideo, Uruguay)
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

import java.nio.file.Path
import java.io.File
import com.typesafe.config.Config
import java.nio.file.Paths
import org.slf4j.Logger
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlin.reflect.KMemberProperty
import kotlin.reflect.KProperty
import kotlin.reflect.declaredExtensionProperties

private fun printErrorAndExit(msg: String?, errCode: Int = -1) {
    System.err.println(msg ?: "Unknown Error")
    System.exit(errCode)
}

private fun <T> T.then(initWith: (T) -> Unit): T {
    initWith(this)
    return this
}

private inline fun Logger.debug(foo: () -> String): Unit = if (this.isDebugEnabled()) this.debug(foo())
