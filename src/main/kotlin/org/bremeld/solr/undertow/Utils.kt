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
import com.typesafe.config.Config
import java.nio.file.Paths

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

private fun Config.plus(fallback: Config): Config = this.withFallback(fallback)!!

private fun Config.value(key: String): ConfiguredValue = ConfiguredValue(this, key)
private fun Config.nested(key: String): Config = this.getConfig(key)!!

private class ConfiguredValue(val cfg: Config, val key: String) {
    fun asPath(): Path = Paths.get(cfg.getString(key)!!.trim())!!.toAbsolutePath()
    fun asString(): String = cfg.getString(key)!!.trim()
    fun asBoolean(): Boolean = cfg.getBoolean(key)
    fun asInt(): Int = cfg.getInt(key)
    fun asStringList(): List<String> = cfg.getStringList(key)!!
    fun asStringArray(): Array<String> = cfg.getStringList(key)!!.copyToArray()

    fun isZero(): Boolean = asInt() == 0

    fun isEmptyString(): Boolean = notExists() || asString().isEmpty()
    fun isNotEmptyString(): Boolean = exists() && asString().isNotEmpty()

    fun exists(): Boolean = cfg.hasPath(key)
    fun notExists(): Boolean = !cfg.hasPath(key)
}

private fun Int.minimum(minVal: Int): Int = Math.max(this, minVal)
private fun Int.maximum(maxVal: Int): Int = Math.min(this, maxVal)
private fun Int.coerce(minVal: Int, maxVal: Int) = this.minimum(minVal).maximum(maxVal)
private fun Int.coerce(range: IntRange) = this.minimum(range.start).maximum(range.end)

