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
import org.slf4j.Logger
import java.net.URL
import java.net.URLClassLoader
import java.nio.file.Files

private fun printErrorAndExit(msg: String?, errCode: Int = -1) {
    System.err.println(msg ?: "Unknown Error")
    System.exit(errCode)
}

public fun Path.exists(): Boolean = Files.exists(this)
public fun Path.notExists(): Boolean = !this.exists()

public fun Path.deleteRecursive(): Unit = delDirRecurse(this.toFile())
public fun File.deleteRecursive(): Unit = delDirRecurse(this)

private fun delDirRecurse(f: File): Unit {
    if (f.isDirectory()) {
        for (c in f.listFiles()) {
            delDirRecurse(c)
        }
    }
    f.delete()
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

private fun Config.plus(fallback: Config): Config = this.withFallback(fallback)
private fun Config.value(key: String): ConfiguredValue = ConfiguredValue(this, key)
private fun Config.nested(key: String): Config = this.getConfig(key)
private fun Config.render(): String = this.root().render()

private class ConfiguredValue(val cfg: Config, val key: String) {
    fun asPath(): Path = Paths.get(cfg.getString(key).trim()).toAbsolutePath()
    fun asPath(relativeTo: Path): Path = relativeTo.resolveSibling(cfg.getString(key).trim()).toAbsolutePath()

    fun asString(): String = cfg.getString(key).trim()
    fun asBoolean(): Boolean = cfg.getBoolean(key)
    fun asInt(): Int = cfg.getInt(key)
    fun asStringList(): List<String> = cfg.getStringList(key)
    fun asStringArray(): Array<String> = cfg.getStringList(key).copyToArray()
    fun asDefaultedStringList(default: List<String>): List<String> = if (exists()) asStringList() else default
    fun asGuaranteedStringList(): List<String> = if (exists()) asStringList() else listOf()

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

private inline fun Logger.debug(foo: () -> String): Unit = if (this.isDebugEnabled()) this.debug(foo())

private fun String.mustStartWith(prefix: String): String {
   return if (this.startsWith(prefix)) {
       this
   }
   else {
       prefix + this
   }
}

private fun String.mustStartWith(prefix: Char): String {
    return if (this.startsWith(prefix)) {
        this
    }
    else {
        prefix + this
    }
}

// borrowed from KTOR
// https://github.com/Kotlin/ktor/blob/fd99512bf8e207fef7af5ae6521f871ea9d2fa7b/ktor-core/src/org/jetbrains/ktor/host/OverridingClassLoader.kt
// probably originally from:
// https://dzone.com/articles/java-classloader-handling

/**
 * A parent-last classloader that will try the child classloader first and then the parent.
 */
class ChildFirstClassloader(classpath: List<URL>, parentClassLoader: ClassLoader?) : ClassLoader(parentClassLoader) {
    private val childClassLoader = ChildURLClassLoader(classpath.copyToArray(), getParent())

    synchronized override fun loadClass(name: String, resolve: Boolean): Class<*> {
        try {
            // first we try to find a class inside the child classloader
            return childClassLoader.findClass(name)
        } catch (e: ClassNotFoundException) {
            // didn't find it, try the parent
            return super.loadClass(name, resolve)
        }
    }

    /**
     * This class delegates (child then parent) for the findClass method for a URLClassLoader.
     * We need this because findClass is protected in URLClassLoader
     */
    private class ChildURLClassLoader(urls: Array<URL>, private val realParent: ClassLoader) : URLClassLoader(urls, null) {
        public override fun findClass(name: String): Class<*> {
            val loaded = super.findLoadedClass(name)
            if (loaded != null)
                return loaded

            try {
                // first try to use the URLClassLoader findClass
                return super.findClass(name)
            } catch (e: ClassNotFoundException) {
                // if that fails, we ask our real parent classloader to load the class (we give up)
                return realParent.loadClass(name)
            }
        }
    }
}

