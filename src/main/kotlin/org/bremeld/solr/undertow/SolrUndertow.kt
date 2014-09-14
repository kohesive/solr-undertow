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

import kotlin.properties.Delegates
import io.undertow.servlet.*
import io.undertow.*
import io.undertow.servlet.api.*
import io.undertow.server.HttpHandler
import io.undertow.server.handlers.RequestLimit
import io.undertow.server.handlers.resource.FileResourceManager
import io.undertow.predicate.Predicates
import java.net.*
import java.nio.file.*
import java.util.*
import java.nio.file.attribute.BasicFileAttributes
import java.io.*
import javax.servlet.*
import org.slf4j.LoggerFactory

public class Server(cfgLoader: ServerConfigLoader) {
    val log = LoggerFactory.getLogger("SolrServer")!!
    val cfg by Delegates.lazy { ServerConfig(log, cfgLoader) }

    public fun run() {
        log.warn("Solr + Undertow = small server, happy days, fast, and maybe other cool things.")
        log.warn("Starting SolrServer")
        if (!cfg.validate()) {
            log.error("Configuration is not valid, terminating server")
            System.exit(-1)
        }
        cfg.print()

        val solrWarDeployment = deployWarFileToCache(cfg.solrWarFile)
        val ioThreads = Math.max(1, if (cfg.httpIoThreads == 0) Runtime.getRuntime().availableProcessors() else cfg.httpIoThreads)
        val workerThreads = if (cfg.httpWorkerThreads == 0) Runtime.getRuntime().availableProcessors() * 8 else cfg.httpWorkerThreads

        val server = Undertow.builder()!!
                .addHttpListener(cfg.httpClusterPort, cfg.httpHost)!!
                .setDirectBuffers(true)!!
                .setHandler(buildSolrServletHandler(solrWarDeployment))!!
                .setIoThreads(ioThreads)!!
                .setWorkerThreads(workerThreads)!!
                .build()!!

        server.start()

        log.info("Initializing Solr")
        // trigger a Solr servlet so that Solr is loaded, connects to cluster, et al
        URL("http://${cfg.httpHost}:${cfg.httpClusterPort}/solr/#/").readBytes()
        log.info("!!!! SERVER READY:  Solr has started and finished startup/cluster processing !!!!")
    }

    private data class DeployedWarInfo(val cacheDir: Path, val htmlDir: Path, val libDir: Path, val classLoader: URLClassLoader)

    private fun deployWarFileToCache(solrWar: Path): DeployedWarInfo {
        log.info("Extracting WAR file: ${solrWar}")

        val tempDirThisSolr = cfg.tempDir.resolve(cfg.solrVersion)!!
        val tempDirHtml = tempDirThisSolr.resolve("html-root")!!
        val tempDirJars = tempDirThisSolr.resolve("lib")!!

        deleteRecursive(tempDirThisSolr)
        Files.createDirectories(tempDirThisSolr)
        Files.createDirectories(tempDirHtml)
        Files.createDirectories(tempDirJars)

        val warUri = URI.create("jar:file:${solrWar}")
        val warJarFs = FileSystems.newFileSystem(warUri, mapOf("create" to "false"))!!
        val warLibPath = warJarFs.getPath("/WEB-INF/lib/")
        val warRootPath = warJarFs.getPath("/")

        if (!Files.exists(warLibPath) || !Files.isDirectory(warLibPath)) {
            log.error("The WAR file ${solrWar} does not contain WEB-INF/lib/ directory for the classpath jars")
            throw RuntimeException("Server cannot start.")
        }

        val jarFiles = ArrayList<URL>()
        Files.newDirectoryStream(warLibPath, "*.jar")!!.forEach { zippedJar ->
            val jarDestination = tempDirJars.resolve(zippedJar.getFileName().toString())!!
            Files.copy(zippedJar, jarDestination, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES)
            jarFiles.add(jarDestination.toUri().toURL())
        }

        if (cfg.hasLibExtDir()) {
            Files.newDirectoryStream(cfg.libExtDir, "*.jar")!!.forEach { extJar ->
                jarFiles.add(extJar.toUri().toURL())
            }
        }

        var warCopyFailed = false

        Files.walkFileTree(warRootPath, object : FileVisitor<Path?> {
            override fun preVisitDirectory(dir: Path?, attrs: BasicFileAttributes): FileVisitResult {
                // create dir in target
                val relativeSourceDir = warRootPath.relativize(dir!!).toString()
                if (relativeSourceDir.isEmpty()) {
                    return FileVisitResult.CONTINUE
                } else if (relativeSourceDir == "WEB-INF/" || relativeSourceDir == "META-INF/") {
                    return FileVisitResult.SKIP_SUBTREE
                } else {
                    val destination = tempDirHtml.resolve(relativeSourceDir)!!
                    try {
                        Files.copy(dir, destination, StandardCopyOption.COPY_ATTRIBUTES)
                    } catch (ex: FileAlreadyExistsException) {
                        // that's ok
                    }
                    return FileVisitResult.CONTINUE
                }
            }
            override fun postVisitDirectory(dir: Path?, exc: IOException?): FileVisitResult {
                // fix modification time of dir
                if (exc == null) {
                    val destination = tempDirHtml.resolve(warRootPath.relativize(dir!!).toString())!!
                    val lastModTime = Files.getLastModifiedTime(dir)
                    Files.setLastModifiedTime(destination, lastModTime)
                }
                return FileVisitResult.CONTINUE
            }
            override fun visitFile(file: Path?, attrs: BasicFileAttributes): FileVisitResult {
                val destination = tempDirHtml.resolve(warRootPath.relativize(file!!).toString())!!
                Files.copy(file, destination, StandardCopyOption.COPY_ATTRIBUTES, StandardCopyOption.REPLACE_EXISTING)
                return FileVisitResult.CONTINUE
            }
            override fun visitFileFailed(file: Path?, exc: IOException?): FileVisitResult? {
                log.error("Unable to copy from WAR to temp directory, file ${file!!.toAbsolutePath().toString()}", exc!!)
                warCopyFailed = true
                return FileVisitResult.TERMINATE
            }
        })

        if (warCopyFailed) {
            log.error("The WAR file ${solrWar} could not be copied to temp directory")
            throw RuntimeException("Server cannot start.")
        }

        return DeployedWarInfo(tempDirThisSolr, tempDirHtml, tempDirJars, URLClassLoader(jarFiles.copyToArray(), ClassLoader.getSystemClassLoader()))
    }

    private fun buildSolrServletHandler(solrWarDeployment: DeployedWarInfo): HttpHandler {
        // load all by name so we have no direct dependency on Solr
        val solrDispatchFilterClass = solrWarDeployment.classLoader.loadClass("org.apache.solr.servlet.SolrDispatchFilter")!!.asSubclass(javaClass<Filter>())
        val solrZookeeprServletClass = solrWarDeployment.classLoader.loadClass("org.apache.solr.servlet.ZookeeperInfoServlet")!!.asSubclass(javaClass<Servlet>())
        val solrAdminUiServletClass = solrWarDeployment.classLoader.loadClass("org.apache.solr.servlet.LoadAdminUiServlet")!!.asSubclass(javaClass<Servlet>())
        val solrRestApiServletClass = solrWarDeployment.classLoader.loadClass("org.restlet.ext.servlet.ServerServlet")!!.asSubclass(javaClass<Servlet>())

        // mimic solr web.xml file, minus old deprecated redirects from old UI...
        val deployment = Servlets.deployment()!!
                .setClassLoader(solrWarDeployment.classLoader)!!
                .setContextPath(cfg.solrContextPath)!!
                .setDefaultServletConfig(DefaultServletConfig())!!
                .setDeploymentName("solr.war")!!
                .addFilters(
                        Servlets.filter("SolrRequestFilter", solrDispatchFilterClass)!!
                )!!
                .addFilterUrlMapping("SolrRequestFilter", "/*", DispatcherType.REQUEST)!!
                .addServlets(
                        Servlets.servlet("Zookeeper", solrZookeeprServletClass)!!
                                .addMapping("/zookeeper"),
                        Servlets.servlet("LoadAdminUI", solrAdminUiServletClass)!!
                                .addMapping("/admin.html"),
                        Servlets.servlet("SolrRestApi", solrRestApiServletClass)!!
                                .addInitParam("org.restlet.application", "org.apache.solr.rest.SolrRestApi")!!
                                .addMapping("/schema/*")!!
                )!!
                .addMimeMapping(MimeMapping(".xsl", "application/xslt+xml"))!!
                .addWelcomePage("admin.html")!!
                .setResourceManager(FileResourceManager(solrWarDeployment.htmlDir.toFile(), 1024))!!

        val deploymentManager = Servlets.defaultContainer()!!.addDeployment(deployment)!!
        deploymentManager.deploy()
        val deploymentHandler = deploymentManager.start()!!

        // create nested request handlers that route to rate limiting handlers put suffix on inside,
        // and exact matches on outside so they are checked first.

        var wrappedHandlers = deploymentHandler
        cfg.requestLimiters.values() map { RequestLimitHelper(it) } forEach { rl ->
            wrappedHandlers = rl.nestHandlerInsideRateLimitingBySuffixHandler(wrappedHandlers, deploymentHandler)
        }
        cfg.requestLimiters.values() map { RequestLimitHelper(it) } forEach { rl ->
            wrappedHandlers = rl.nestHandlerInsideRateLimitingByExactHandler(wrappedHandlers, deploymentHandler)
        }

        val pathHandler = Handlers.path(Handlers.redirect(cfg.solrContextPath))!!
                .addPrefixPath(cfg.solrContextPath, wrappedHandlers)!!

        // ensure we attempt to close things nicely on termination
        Runtime.getRuntime().addShutdownHook(Thread() {
            log.warn("Undeploying servlets...")
            deploymentManager.undeploy()
            log.warn("Undeploy complete.")
        })

        return pathHandler
    }

    private class RequestLimitHelper(private val rlCfg: RequestLimitConfig) {
        private val requestLimit = RequestLimit(rlCfg.concurrentRequestLimit, rlCfg.maxQueuedRequestLimit)
        private val exactPredicate = Predicates.paths(*rlCfg.exactPaths.copyToArray())
        private val suffixPredicate = Predicates.suffixes(*rlCfg.pathSuffixes.copyToArray())

        private fun hasExactPaths(): Boolean = rlCfg.exactPaths.isNotEmpty()
        private fun hasSuffixes(): Boolean = rlCfg.pathSuffixes.isNotEmpty()

        fun nestHandlerInsideRateLimitingBySuffixHandler(handlerToNest: HttpHandler, handlerToRateLimit: HttpHandler): HttpHandler {
            return if (hasSuffixes()) Handlers.predicate(suffixPredicate, Handlers.requestLimitingHandler(requestLimit, handlerToRateLimit)!!, handlerToNest)!!
            else handlerToNest
        }

        fun nestHandlerInsideRateLimitingByExactHandler(handlerToNest: HttpHandler, handlerToRateLimit: HttpHandler): HttpHandler {
            return if (hasExactPaths()) Handlers.predicate(exactPredicate, Handlers.requestLimitingHandler(requestLimit, handlerToRateLimit)!!, handlerToNest)!!
            else handlerToNest
        }
    }
}

