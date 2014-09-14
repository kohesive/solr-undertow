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

import java.nio.file.Path
import java.io.File

private fun deleteRecursive(p: Path): Unit {
    deleteRecursive(p.toFile())
}

private fun deleteRecursive(f: File): Unit {
    if (f.isDirectory()) {
        for (c in f.listFiles()!!) {
            deleteRecursive(c)
        }
    }
    f.delete()
}

private fun String.trimSlashes(): String {
    var temp = this.trim()
    if (temp.startsWith('/')) {
        temp = temp.substring(1)
    }
    if (temp.endsWith('/')) {
        temp = temp.substring(0,temp.length()-1)
    }
    return temp
}

private fun <T> T.verifiedBy(verifyWith: (T) -> Unit): T {
    verifyWith(this)
    return this
}

private fun <T> T.initializedBy(initWith: (T) -> Unit): T {
    initWith(this)
    return this
}

private fun <T> T.then(initWith: (T) -> Unit): T {
    initWith(this)
    return this
}