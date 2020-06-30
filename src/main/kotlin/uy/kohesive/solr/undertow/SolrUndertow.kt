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

import io.undertow.Handlers
import io.undertow.Undertow
import io.undertow.UndertowOptions
import io.undertow.predicate.Predicates
import io.undertow.server.HttpHandler
import io.undertow.server.HttpServerExchange
import io.undertow.server.handlers.GracefulShutdownHandler
import io.undertow.server.handlers.encoding.EncodingHandler
import io.undertow.server.handlers.RequestLimit
import io.undertow.server.handlers.accesslog.AccessLogHandler
import io.undertow.server.handlers.accesslog.AccessLogReceiver
import io.undertow.server.handlers.resource.FileResourceManager
import io.undertow.servlet.Servlets
import io.undertow.servlet.api.DefaultServletConfig
import io.undertow.servlet.api.DeploymentManager
import io.undertow.servlet.api.MimeMapping
import io.undertow.util.Headers
import org.slf4j.LoggerFactory
import uy.klutter.core.common.*
import java.io.File
import java.io.IOException
import java.net.URI
import java.net.URL
import java.nio.file.*
import java.nio.file.attribute.BasicFileAttributes
import java.util.*
import javax.servlet.DispatcherType
import javax.servlet.Filter
import javax.servlet.Servlet
import kotlin.properties.Delegates

data class ServerStartupStatus(val started: Boolean, val errorMessage: String)

class Server(cfgLoader: ServerConfigLoader) {
    val log = LoggerFactory.getLogger("SolrServer")
    val cfg by lazy { ServerConfig(log, cfgLoader) }
    private var server: Undertow by Delegates.notNull()
    private var gracefulShutdownWrapperHandler: GracefulShutdownHandler by Delegates.notNull()
    private var servletDeploymentMgr: DeploymentManager by Delegates.notNull()

    fun run(): ServerStartupStatus {
        log.warn("Solr + Undertow = small server, happy days, fast, and maybe other cool things.")
        try {
            log.warn("Starting SolrServer")
            if (!cfg.validate()) {
                return ServerStartupStatus(false, "Configuration is not valid, terminating server")
            }
            cfg.print()

            val solrWarDeployment = if (cfg.omittingSolrWarLegally()) {
                DeployedDistributionInfo(true, null, this.javaClass.getClassLoader())
            } else {
                val deployment = deploySolrDistributionToCache(cfg.solrWarFile!!)
                if (!deployment.successfulDeploy) {
                    return ServerStartupStatus(false, "WAR file failed to deploy, terminating server")
                }
                deployment
            }

            val ioThreads = (if (cfg.httpIoThreads == 0) Runtime.getRuntime().availableProcessors() else cfg.httpIoThreads).minimum(2)
            val workerThreads = (if (cfg.httpWorkerThreads == 0) Runtime.getRuntime().availableProcessors() * 8 else cfg.httpWorkerThreads).minimum(8)

            val (servletDeploymentMgr, servletHandler) = buildSolrServletHandler(solrWarDeployment)

            val loggedHandler = AccessLogHandler(servletHandler, object : AccessLogReceiver {
                override fun logMessage(message: String?) {
                    cfg.accessLogger.info(message)
                }
            }, cfg.accessLogFormat, Server::class.java.getClassLoader())

            gracefulShutdownWrapperHandler = Handlers.gracefulShutdown(loggedHandler)

            // ensure we attempt to close things nicely on termination
            val gracefulShutdownHook = Thread() {
                if (servletDeploymentMgr.getState() == DeploymentManager.State.STARTED) {
                    log.warn("Shutdown Hook triggered:  Undeploying servlets...")
                    val wasGraceful = shutdownNicely(servletDeploymentMgr, gracefulShutdownWrapperHandler)
                    if (wasGraceful) {
                        log.warn("Undeploy complete and graceful.")
                    } else {
                        log.error("Undeploy complete, but shutdown was not graceful.")
                    }
                } else {
                    log.warn("Shutdown Hook triggered: servlets already undeployed.")
                }
            }
            Runtime.getRuntime().addShutdownHook(gracefulShutdownHook)

            val shutdownRequestHandler = ShutdownRequestHandler(servletDeploymentMgr, gracefulShutdownWrapperHandler)

            log.info("Building Undertow server [port:${cfg.httpClusterPort},host:${cfg.httpHost},ioThreads:${ioThreads},workerThreads:${workerThreads}]")
            server = Undertow.builder()
                    .addHttpListener(cfg.httpClusterPort, cfg.httpHost)
                    .addHttpListener(cfg.shutdownConfig.httpPort, cfg.shutdownConfig.httpHost, shutdownRequestHandler)
                    .setDirectBuffers(true)
                    .setHandler(gracefulShutdownWrapperHandler)
                    .setIoThreads(ioThreads)
                    .setWorkerThreads(workerThreads)
                    .setServerOption(UndertowOptions.RECORD_REQUEST_START_TIME, cfg.accessLogEnableRequestTiming)
                    .build()

            log.warn("Starting Undertow HTTP server...")
            server.start()
            val listeningUrl = "http://${cfg.httpHost}:${cfg.httpClusterPort}${cfg.solrContextPath}"
            log.warn("Undertow HTTP Server started, listening on ${listeningUrl}")

            val shutdownUrl = "http://${cfg.shutdownConfig.httpHost}:${cfg.shutdownConfig.httpPort}?password=*****"
            log.warn("Shutdown port configured as ${shutdownUrl} with graceful delay of ${cfg.shutdownConfig.gracefulDelayString}")
            if (cfg.shutdownConfig.password.isNullOrBlank()) {
                log.warn("Shutdown password is not configured, therefore shutdown requests via HTTP will fail.")
            }

            log.info("Loading one Solr admin page to confirm not in a wait state...")
            // trigger a Solr servlet in case Solr is waiting to start still
            // TODO: change this to a HTTP request with status check (200), terminate server on 500?  (allow for long timeout, we could be waiting on node joins and startup delays)
           // URL("${listeningUrl}/#/").readBytes()
            log.warn("!!!! SERVER READY:  Listening, and ready at ${listeningUrl} !!!!")

            return ServerStartupStatus(true, "OK")
        } catch (ex: Throwable) {
            log.error("Server unhandled exception during startup '${ex.message ?: ex.javaClass.getName()}'", ex)
            return ServerStartupStatus(false, "Server unhandled exception during startup '${ex.message}'")
        }
    }

    private data class DeployedDistributionInfo(val successfulDeploy: Boolean, val htmlDir: Path?, val classLoader: ClassLoader)
    private data class ServletDeploymentAndHandler(val deploymentManager: DeploymentManager, val pathHandler: HttpHandler)

    private inner class ShutdownRequestHandler(val servletDeploymentMgr: DeploymentManager, val gracefulShutdownWrapperHandler: GracefulShutdownHandler) : HttpHandler {
        override fun handleRequest(exchange: HttpServerExchange) {
            if (cfg.shutdownConfig.password.isNullOrBlank()) {
                exchange.setStatusCode(403).getResponseHeaders().put(Headers.CONTENT_TYPE, "text/plain")
                exchange.getResponseSender().send("forbidden")
                log.error("Forbidden attempt to talk to shutdown port")
            } else if (exchange.getQueryParameters().get("password")?.firstOrNull() != cfg.shutdownConfig.password) {
                exchange.setStatusCode(401).getResponseHeaders().put(Headers.CONTENT_TYPE, "text/plain")
                exchange.getResponseSender().send("unauthorized")
                log.error("Unauthorized attempt to talk to shutdown port")
            } else {
                if (exchange.isInIoThread()) {
                    exchange.dispatch(this);
                    return;
                }

                log.warn("Shutdown requested via shutdown port...")
                val wasGraceful = shutdownNicely(servletDeploymentMgr, gracefulShutdownWrapperHandler)
                if (wasGraceful) {
                    try {
                        exchange.setStatusCode(200).getResponseHeaders().put(Headers.CONTENT_TYPE, "text/plain")
                        exchange.getResponseSender().send("OK")
                    } finally {
                        server.stop()
                        log.warn("Undeploy complete and graceful.")
                    }
                } else {
                    try {
                        exchange.setStatusCode(500).getResponseHeaders().put(Headers.CONTENT_TYPE, "text/plain")
                        exchange.getResponseSender().send("ERROR")
                    } finally {
                        server.stop()
                        log.error("Undeploy complete, but shutdown was not graceful.")
                        System.exit(1)
                    }
                }

            }
        }
    }

    fun shutdown(): Boolean {
        return shutdownNicely(servletDeploymentMgr, gracefulShutdownWrapperHandler)
    }

    private fun shutdownNicely(servletDeploymentMgr: DeploymentManager, gracefulShutdownWrapperHandler: GracefulShutdownHandler): Boolean {
        // block traffic first
        gracefulShutdownWrapperHandler.shutdown()
        // wait for a moment for things to finish that are not blocked
        val wasGraceful = gracefulShutdownWrapperHandler.awaitShutdown(cfg.shutdownConfig.gracefulDelay)
        // undeploy and hope everyone closes down nicely
        servletDeploymentMgr.stop()
        servletDeploymentMgr.undeploy()
        return wasGraceful
    }

    private fun deploySolrDistributionToCache(solrDistribution: Path): DeployedDistributionInfo {
        log.warn("Extracting Solr from file: ${solrDistribution}")

        val tempDirThisSolr = cfg.tempDir.resolve(cfg.solrVersion)
        val tempDirHtml = tempDirThisSolr.resolve("html-root")
        val tempDirJars = tempDirThisSolr.resolve("lib")

        tempDirThisSolr.deleteRecursively()
        Files.createDirectories(tempDirThisSolr)
        Files.createDirectories(tempDirHtml)
        Files.createDirectories(tempDirJars)

        val FAILED_DEPLOYMENT = DeployedDistributionInfo(false, tempDirHtml, ClassLoader.getSystemClassLoader())

        val normalizedPathToDistribution = solrDistribution.normalize().toAbsolutePath()
        val unixStylePathForUri = normalizedPathToDistribution.toString().replace(File.separatorChar, '/').replace('\\', '/')

        val (warLibPath, warRootPath, metricLibPath) = if (unixStylePathForUri.endsWith(".zip")) {
            val checkZip = URI("jar:file", unixStylePathForUri.mustStartWith('/'), null)
            log.warn("  ${checkZip}")
            val checkZipFs = FileSystems.newFileSystem(checkZip, mapOf("create" to "false"))
            val checkRootOfZip = checkZipFs.getPath("/")
            val rootDir = Files.newDirectoryStream(checkRootOfZip).firstOrNull()
            if (rootDir != null) {
                val firstLevelDir = rootDir.getName(0).toString().mustNotStartWith('/').mustNotEndWith('/')
                val checkInnerWar = checkZipFs.getPath("/${firstLevelDir}/server/webapps/solr.war")
                val checkOldInnerWar =  checkZipFs.getPath("/${firstLevelDir}/example/webapps/solr.war")
                val correctWar = if (checkInnerWar.exists()) { checkInnerWar } else { if (checkOldInnerWar.exists()) checkOldInnerWar else null  }
                if (correctWar != null) {
                    val tempWar = tempDirThisSolr.resolve("extracted-solr.war")
                    Files.copy(correctWar, tempWar, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES)
                    val warUri = URI("jar:file", tempWar.toString().mustStartWith('/'), null)

                    val warJarFs = try {
                        log.warn("  ${warUri}")
                        FileSystems.newFileSystem(warUri, mapOf("create" to "false"))
                    } catch (ex: Throwable) {
                        log.error("The extracted distribution WAR file from ${solrDistribution} cannot be opened as a Zip file, due to '${ex.message ?: ex.javaClass.getName()}'", ex)
                        return FAILED_DEPLOYMENT
                    }
                    Triple(warJarFs.getPath("/WEB-INF/lib/"), warJarFs.getPath("/"), null)
                } else {
                    val checkInnerDir = checkZipFs.getPath("/${firstLevelDir}/server/solr-webapp/webapp")
                    log.warn("  webapp:     ${checkZip}!${checkInnerDir}")
                    val checkServerDir = checkZipFs.getPath("/${firstLevelDir}/server")
                    log.warn("  server lib: ${checkZip}!${checkServerDir}/lib")
                    Triple(checkInnerDir.resolve("WEB-INF/lib/"), checkInnerDir, checkServerDir.resolve("lib/"))
                }
            }
            else {
                log.error("The distribution Zip file from ${solrDistribution} does not seem to contain a solr-* root directory")
                Triple(null, null, null)  // TODO: why is this not allowed:  return FAILED_DEPLOYMENT
            }
        } else {
            val warUri = URI("jar:file", unixStylePathForUri.mustStartWith('/'), null)

            val warJarFs = try {
                log.warn("  ${warUri}")
                FileSystems.newFileSystem(warUri, mapOf("create" to "false"))
            } catch (ex: Throwable) {
                log.error("The WAR file ${solrDistribution} cannot be opened as a Zip file, due to '${ex.message ?: ex.javaClass.getName()}'", ex)
                return FAILED_DEPLOYMENT
            }
            Triple(warJarFs.getPath("/WEB-INF/lib/"), warJarFs.getPath("/"), null)
        }

        if (warLibPath == null || warRootPath == null || warLibPath.notExists() || !Files.isDirectory(warLibPath)) {
            log.error("The WAR file ${solrDistribution} does not contain WEB-INF/lib/ directory for the classpath jars")
            return FAILED_DEPLOYMENT
        }

        val jarFiles = ArrayList<URL>()
        Files.newDirectoryStream(warLibPath, "*.jar").forEach { zippedJar ->
            val jarDestination = tempDirJars.resolve(zippedJar.getFileName().toString())
            Files.copy(zippedJar, jarDestination, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES)
            jarFiles.add(jarDestination.toUri().toURL())
        }

        if (metricLibPath != null) {
            Files.newDirectoryStream(metricLibPath, "metrics-*.jar").forEach { zippedJar ->
                val jarDestination = tempDirJars.resolve(zippedJar.getFileName().toString())
                Files.copy(zippedJar, jarDestination, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES)
                jarFiles.add(jarDestination.toUri().toURL())
            }
        }

        if (cfg.hasLibExtDir()) {
            Files.newDirectoryStream(cfg.libExtDir, "*.jar").forEach { extJar ->
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
                    val destination = tempDirHtml.resolve(relativeSourceDir)
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
                    val destination = tempDirHtml.resolve(warRootPath.relativize(dir!!).toString())
                    val lastModTime = Files.getLastModifiedTime(dir)
                    Files.setLastModifiedTime(destination, lastModTime)
                }
                return FileVisitResult.CONTINUE
            }

            override fun visitFile(file: Path?, attrs: BasicFileAttributes): FileVisitResult {
                val destination = tempDirHtml.resolve(warRootPath.relativize(file!!).toString())
                Files.copy(file, destination, StandardCopyOption.COPY_ATTRIBUTES, StandardCopyOption.REPLACE_EXISTING)
                return FileVisitResult.CONTINUE
            }

            override fun visitFileFailed(file: Path?, ex: IOException?): FileVisitResult {
                log.error("Unable to copy from WAR to temp directory, file ${file?.toAbsolutePath().toString()}, due to '${ex?.message ?: ex?.javaClass?.getName() ?: "unknown"}'", ex)
                warCopyFailed = true
                return FileVisitResult.TERMINATE
            }
        })

        if (warCopyFailed) {
            log.error("The WAR file ${solrDistribution} could not be copied to temp directory")
            return FAILED_DEPLOYMENT
        }

        val classLoader = ChildFirstClassloader(jarFiles, this.javaClass.classLoader)
        return DeployedDistributionInfo(true, tempDirHtml, classLoader)
    }

    private fun buildSolrServletHandler(solrWarDeployment: DeployedDistributionInfo): ServletDeploymentAndHandler {
        val solrVersion = cfg.solrVersion
        val isSolr6 = solrVersion.substringBefore('.').toInt() == 6
        val welcomePages = listOf("index.html", "old.html") +
            if (isSolr6) {
                emptyList()
            }
            else {
                listOf("admin.html")
            }

        // load all by name so we have no direct dependency on Solr
        val solrDispatchFilterClass = solrWarDeployment.classLoader.loadClass("org.apache.solr.servlet.SolrDispatchFilter").asSubclass(Filter::class.java)
        val solrZookeeprServletClass = try {
            solrWarDeployment.classLoader.loadClass("org.apache.solr.servlet.ZookeeperInfoServlet").asSubclass(Servlet::class.java)
        } catch (ex: ClassNotFoundException) {
            null
        }

        val solrAdminUiServletClass = solrWarDeployment.classLoader.loadClass("org.apache.solr.servlet.LoadAdminUiServlet").asSubclass(Servlet::class.java)
        val solrRestApiServletClass = solrWarDeployment.classLoader.loadClass("org.restlet.ext.servlet.ServerServlet").asSubclass(Servlet::class.java)
        val solrRestApiClass = try {
            solrWarDeployment.classLoader.loadClass("org.apache.solr.rest.SolrSchemaRestApi")
        } catch (ex: ClassNotFoundException) {
            solrWarDeployment.classLoader.loadClass("org.apache.solr.rest.SolrRestApi")
        }
        val solrRestConfigApiClass: Class<*>? = try {
            solrWarDeployment.classLoader.loadClass("org.apache.solr.rest.SolrConfigRestApi")
        } catch (ex: ClassNotFoundException) {
            null
        }



        // mimic solr web.xml file, minus old deprecated redirects from old UI...
        val deployment = Servlets.deployment()
                .setClassLoader(solrWarDeployment.classLoader)
                .setContextPath(cfg.solrContextPath)
                .setDefaultServletConfig(SolrDefaultServletConfig())
                .setDefaultEncoding("UTF-8")
                .setDeploymentName("solr.war")
                .setEagerFilterInit(true)
                .addFilters(
                        Servlets.filter("SolrRequestFilter", solrDispatchFilterClass)
                                .addInitParam("path-prefix", null) // do we need to set thisif context path is more than one level deep?
                                .addInitParam("excludePatterns", "/css/.+,/js/.+,/img/.+,/tpl/.+")
                )
                .addFilterUrlMapping("SolrRequestFilter", "/*", DispatcherType.REQUEST)
                .addServlets(
                        Servlets.servlet("LoadAdminUI", solrAdminUiServletClass)
                                .apply {
                                    welcomePages.forEach {
                                        addMapping(it)
                                    }
                                }
                                .setRequireWelcomeFileMapping(true),
                        Servlets.servlet("SolrRestApi", solrRestApiServletClass)
                                .addInitParam("org.restlet.application", solrRestApiClass.getName())
                                .addMapping("/schema/*")
                                .setRequireWelcomeFileMapping(true)
                )
                .addMimeMapping(MimeMapping(".xsl", "application/xslt+xml"))
                .addWelcomePages(welcomePages)
        solrWarDeployment.htmlDir?.let { htmlDir ->
            deployment.setResourceManager(FileResourceManager(solrWarDeployment.htmlDir.toFile(), 1024L,
                    cfg.tempDirSymLinksAllow, *cfg.tempDirSymLinksSafePaths.map(Path::toString).toTypedArray()))
        }

        if (solrRestConfigApiClass != null) {
            deployment.addServlet(Servlets.servlet("SolrConfigRestApi", solrRestApiServletClass)
                    .addInitParam("org.restlet.application", solrRestConfigApiClass.getName())
                    .addMapping("/config/*"))
        }

        if (solrZookeeprServletClass != null) {
            deployment.addServlet(Servlets.servlet("Zookeeper", solrZookeeprServletClass)
                    .addMapping("/zookeeper"))
        }

        log.warn("Initializing Solr, deploying the Servlet Container...")
        solrWarDeployment.htmlDir?.let {
            log.warn("  using static files: ${it.toFile().absolutePath}")
        }
        servletDeploymentMgr = Servlets.defaultContainer().addDeployment(deployment)
        servletDeploymentMgr.deploy()
        val deploymentHandler = servletDeploymentMgr.start()
        log.warn("Solr Servlet Container deployed.")

        // create nested request handlers that route to rate limiting handlers put suffix on inside,
        // and exact matches on outside so they are checked first.

        var wrappedHandlers = deploymentHandler
        cfg.requestLimiters.values.map { RequestLimitHelper(it) }.forEach { rl ->
            wrappedHandlers = rl.nestHandlerInsideRateLimitingBySuffixHandler(wrappedHandlers, deploymentHandler)
        }
        cfg.requestLimiters.values.map { RequestLimitHelper(it) }.forEach { rl ->
            wrappedHandlers = rl.nestHandlerInsideRateLimitingByExactHandler(wrappedHandlers, deploymentHandler)
        }

        val pathHandler = Handlers.path(Handlers.redirect(cfg.solrContextPath.mustEndWith('/')+"index.html"))
                .addPrefixPath(cfg.solrContextPath, wrappedHandlers)

        val redirectToAdmin = if (isSolr6) "index.html" else "admin.html"
        val oldAdminUiFixer = Handlers.path(pathHandler).addExactPath(cfg.solrContextPath, Handlers.redirect(cfg.solrContextPath.mustEndWith('/')+redirectToAdmin))

        if (cfg.httpCompression) {
            val encodingHandler = EncodingHandler.Builder().build(null).wrap(oldAdminUiFixer)
            return ServletDeploymentAndHandler(servletDeploymentMgr, encodingHandler)
        }
        else {
            return ServletDeploymentAndHandler(servletDeploymentMgr, oldAdminUiFixer)
        }
    }

    private class RequestLimitHelper(private val rlCfg: RequestLimitConfig) {
        private val requestLimit = RequestLimit(rlCfg.concurrentRequestLimit, rlCfg.maxQueuedRequestLimit)
        private val exactPredicate = Predicates.paths(*rlCfg.exactPaths.toTypedArray())
        private val suffixPredicate = Predicates.suffixes(*rlCfg.pathSuffixes.toTypedArray())

        private fun hasExactPaths(): Boolean = rlCfg.exactPaths.isNotEmpty()
        private fun hasSuffixes(): Boolean = rlCfg.pathSuffixes.isNotEmpty()

        private fun rpsLimitWrappedHanlder(handlerToRateLimit: HttpHandler): HttpHandler {
            return  if (rlCfg.maxReqPerSecond <= 0) {
                handlerToRateLimit
            }else {
                RequestPerSecondRateLimiter(rlCfg.maxReqPerSecond.toLong(), handlerToRateLimit,
                        minPauseOnBusyMillis = rlCfg.throttledReqPerSecondMinPauseMillis.toLong(),
                        maxPauseOnBusyMillis = rlCfg.throttledReqPerSecondMaxPauseMillis.toLong(),
                        httpCodeWhenBusy = rlCfg.overLimitReqPerSecondHttpErrorCode)
            }
        }

        fun nestHandlerInsideRateLimitingBySuffixHandler(handlerToNest: HttpHandler, handlerToRateLimit: HttpHandler): HttpHandler {
            return if (hasSuffixes()) {
                val innerHandler = rpsLimitWrappedHanlder(handlerToRateLimit)
                Handlers.predicate(suffixPredicate, Handlers.requestLimitingHandler(requestLimit, innerHandler), handlerToNest)
            }
            else handlerToNest
        }

        fun nestHandlerInsideRateLimitingByExactHandler(handlerToNest: HttpHandler, handlerToRateLimit: HttpHandler): HttpHandler {
            return if (hasExactPaths()) {
                val innerHandler = rpsLimitWrappedHanlder(handlerToRateLimit)
                Handlers.predicate(exactPredicate, Handlers.requestLimitingHandler(requestLimit, innerHandler), handlerToNest)
            }
            else handlerToNest
        }
    }
}

// TODO:  Undertow says "This class is deprecated, the default servlet should be configured via context params." but not sure what they mean as the alternative
//        given we are embedded, so think we are stuck with this model.

@Suppress("DEPRECATED_SYMBOL_WITH_MESSAGE")
internal class SolrDefaultServletConfig() : DefaultServletConfig() {
    private val defaultAllowed: Boolean = false
    private val allowed = setOf("ico", "swf", "js", "css", "png", "jpg", "gif", "html", "htm", "txt", "pdf", "svg")
    private val disallowed = setOf("class", "jar", "war", "zip", "xml")

    override fun isDefaultAllowed(): Boolean {
        return defaultAllowed
    }

    override fun getAllowed(): Set<String> {
        return allowed
    }

    override fun getDisallowed(): Set<String> {
        return disallowed
    }
}


