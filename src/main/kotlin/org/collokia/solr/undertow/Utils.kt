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

package org.collokia.solr.undertow

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

private fun Int.isPositiveNumberElse(defaultTo: Int): Int = if (this >= 1) this else defaultTo



