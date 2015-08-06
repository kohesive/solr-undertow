Tuning Solr-Undertow
====================

There are not many tunable paremeters, mostly the IO and Worker threads, the JDK Gabage Collection, the Directory implementation used by Solr, and JMV Heap Sizes.

#### Java Garbage Collection

If using JDK 1.7 start with no garbage collection parameters.  Trust G1 until it fails (unless on 32 bit then do NOT use G1 as it causes problems with Lucene), then tune from there.  For JDK 1.8 read this article: https://wiki.apache.org/solr/ShawnHeisey.  A lot of other Solr information online is relevante for JDK 1.6 and not as much newer JVMs.  Be warned, Solr 6 will only support JDK 1.8 and higher.

Watch for JVM heaps that are larger than needed.  Start with a good size heap, monitor with JConsole to see where the heap size is after GC, and give it some head room for transient data used during queries.  Better to start high and tune downwards.

#### httpIoThreads and httpWorkerThreads

The following settings are defaulted as:

|Setting|Default|
|---|---|
|httpIoThreads|number of system cores, as returned by Runtime.getRuntime().availableProcessors()|
|httpWorkerThreads|8 * number of system cores|
|accessLogEnableRequestTiming|true|

It is rare that you would ever adjust `httpIoThreads` (between `2` and `CPU cores * 2`).  The front-end of this server uses non-blocking IO and all IO is done separately from the worker threads. 

For `httpWorkerThreads` you should *be conservative* but not starve Solr either.  In the Solr distribution, you see that Jetty is configured with a max worker thread count of *10,000*.  **Do not immediately jump to this setting.**  Some user accounts have a file handle limit that this could exceed causing issues.  And this is an excesively high number.  Therefore be conservative with your thread count.  Start with the defaults, go upwards if not using all CPU, and downwards until CPU hovers below 90% (plus more headroom to allow for index commits and warming new searchers using CPU).  Find the sweet spot by running typical load with something like [JMeter](http://jmeter.apache.org).  It is better to queue users or reject them than to crush and kill your Solr instance.

The `accessLogEnableRequestTiming` option times the overall request time for each request for the access logs, and adds slight overhead, it can be set to false to squeeze out a bit more performance.

**As a real-world example** for worker threads, we had a search cluster that a vendor for Solr support suggested should raise the thread count from 1000 to 10,000 to get more than 750 queries-per-second on a 6 node cluster (32 core, 64G memory), but running on solr-undertow the actual sweet spot on these boxes was `httpIoThreads` 16 (higher didn't help), and `httpWorkerThreads` 100 which along with other tuning changes brought the throughput to near 1600 queries per second, and with more manageable CPU that would not overload.  Hence 3.125 threads per core (6.25 per IO thread which is probably not relevant). Our default in solr-undertow would have been 256 which is too high for this use case, but let us see max CPU to tune downwards. 

So "more threads" is not always the answer. 

#### Solr Directory Implementation

Check `NRTCachingDirectoryFactory` vs `MMapDirectoryFactory` vs `NIOFSDirectoryFactory` since you could be surprised which one is fastest for your use case (MMap can be slower on some systems, and NRTCaching is based on MMap so might be slower as well), and watch out for `HDFSDirectoryFactory` which can be significantly slower so be sure you have value from being on HDFS to offset this lost of speed. Check your file system (SSD is the only one true answer, or try to fit your index into memory or  OS disk cache).

#### Other Tuning

Check your queries that they are sufficiently cacheable, and that your cache eviction rates are not too high.  

Disk/Network load and JVM GC are other factors that will pull down performance. Monitor with JConsole, YourKit, VisualVM, New Relic, or some other monitoring software.  _Test to find your own answer._  
