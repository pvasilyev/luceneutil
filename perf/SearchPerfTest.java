package perf;

/**
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

// TODO
//  - be able to quickly run a/b tests again
//  - absorb nrt, pklokup, search, indexing into one tool?
//  - switch to named cmd line args
//  - get pk lookup working w/ remote tasks

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.UnsupportedEncodingException;
import java.lang.reflect.Constructor;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.lucene.analysis.*;
import org.apache.lucene.analysis.en.EnglishAnalyzer;
import org.apache.lucene.analysis.shingle.ShingleAnalyzerWrapper;
import org.apache.lucene.analysis.shingle.ShingleFilter;
import org.apache.lucene.analysis.standard.*;
import org.apache.lucene.analysis.util.CharArraySet;
import org.apache.lucene.codecs.Codec;
import org.apache.lucene.codecs.PostingsFormat;
import org.apache.lucene.codecs.lucene40.Lucene40Codec;
import org.apache.lucene.index.*;
import org.apache.lucene.index.AtomicReaderContext;
//import org.apache.lucene.index.codecs.mocksep.MockSepCodec;
import org.apache.lucene.queryparser.classic.ParseException;
import org.apache.lucene.queryparser.classic.QueryParser;
import org.apache.lucene.search.*;
import org.apache.lucene.search.grouping.*;
import org.apache.lucene.search.grouping.term.*;
import org.apache.lucene.search.similarities.Similarity;
import org.apache.lucene.search.spans.*;
import org.apache.lucene.search.spell.DirectSpellChecker;
import org.apache.lucene.search.spell.DirectSpellChecker;
import org.apache.lucene.search.spell.SuggestMode;
import org.apache.lucene.search.spell.SuggestMode;
import org.apache.lucene.search.spell.SuggestWord;
import org.apache.lucene.search.spell.SuggestWord;
import org.apache.lucene.store.*;
import org.apache.lucene.util.*;

// TODO
//   - post queries on pao
//   - fix pk lookup to tolerate deletes
//   - get regexp title queries
//   - test shingle at search time

// commits: single, multi, delsingle, delmulti

// trunk:
//   javac -Xlint -Xlint:deprecation -cp build/core/classes/java:build/test-framework/classes/java:build/queryparser/classes/java:build/suggest/classes/java:build/analysis/common/classes/java:build/grouping/classes/java perf/SearchPerfTest.java perf/LineFileDocs.java perf/RandomFilter.java
//   java -cp .:build/core/classes/java:build/test-framework/classes/java:build/queryparser/classes/java:build/suggest/classes/java:build/analysis/common/classes/java:build/grouping/classes/java perf.SearchPerfTest MMapDirectory /indices/fullwiki StandardAnalyzer server:localhost:7777 6 1 body -1 no 0 0 DefaultSimilarity multi

public class SearchPerfTest {

  // ReferenceManager that never changes its searcher:
  private static class SingleIndexSearcher extends ReferenceManager<IndexSearcher> {

    public SingleIndexSearcher(IndexSearcher s) {
      this.current = s;
    }

    @Override
    public void decRef(IndexSearcher ref) throws IOException {
      ref.getIndexReader().decRef();
    }

    @Override
    protected IndexSearcher refreshIfNeeded(IndexSearcher ref) {
      return null;
    }

    @Override
    protected boolean tryIncRef(IndexSearcher ref) {
      return ref.getIndexReader().tryIncRef();
    }
  }
  
  private static IndexCommit findCommitPoint(String commit, Directory dir) throws IOException {
    List<IndexCommit> commits = DirectoryReader.listCommits(dir);
    Collections.reverse(commits);
    
    for (final IndexCommit ic : commits) {
      Map<String,String> map = ic.getUserData();
      String ud = null;
      if (map != null) {
        ud = map.get("userData");
        System.out.println("found commit=" + ud);
        if (ud != null && ud.equals(commit)) {
          return ic;
        }
      }
    }
    throw new RuntimeException("could not find commit '" + commit + "'");
  }

  public static void main(String[] clArgs) throws Exception {

    System.out.println("Pointer is " + RamUsageEstimator.NUM_BYTES_OBJECT_REF + " bytes");
 
    // args: dirImpl indexPath numThread numIterPerThread
    // eg java SearchPerfTest /path/to/index 4 100
    final Args args = new Args(clArgs);

    Directory dir0;
    final String dirPath = args.getString("-indexPath");
    final String dirImpl = args.getString("-dirImpl");
    if (dirImpl.equals("MMapDirectory")) {
      dir0 = new MMapDirectory(new File(dirPath));
    } else if (dirImpl.equals("NIOFSDirectory")) {
      dir0 = new NIOFSDirectory(new File(dirPath));
    } else if (dirImpl.equals("SimpleFSDirectory")) {
      dir0 = new SimpleFSDirectory(new File(dirPath));
    } else if (dirImpl.equals("RAMDirectory")) {
      final long t0 = System.currentTimeMillis();
      dir0 = new RAMDirectory(new SimpleFSDirectory(new File(dirPath)), IOContext.READ);
      System.out.println((System.currentTimeMillis() - t0) + " msec to load RAMDir; sizeInBytes=" + ((RAMDirectory) dir0).sizeInBytes());
    } else {
      throw new RuntimeException("unknown directory impl \"" + dirImpl + "\"");
    }

    // TODO: NativeUnixDir?

    final String analyzer = args.getString("-analyzer");
    final String tasksFile = args.getString("-taskSource");
    final int searchThreadCount = args.getInt("-searchThreadCount");
    final String fieldName = args.getString("-field");
    final boolean printHeap = args.getFlag("-printHeap");

    // Used to choose which random subset of tasks we will
    // run, to generate the PKLookup tasks, and to generate
    // any random pct filters:
    final long staticRandomSeed = args.getLong("-staticSeed");

    // Used to shuffle the random subset of tasks:
    final long randomSeed = args.getLong("-seed");

    // TODO: this could be way better.
    final String similarity = args.getString("-similarity");
    // now reflect
    final Class<? extends Similarity> simClazz = 
      Class.forName("org.apache.lucene.search.similarities." + similarity).asSubclass(Similarity.class);
    final Similarity sim = simClazz.newInstance();

    final Analyzer a;
    if (analyzer.equals("EnglishAnalyzer")) {
      a = new EnglishAnalyzer(Version.LUCENE_40);
    } else if (analyzer.equals("ClassicAnalyzer")) {
      a = new ClassicAnalyzer(Version.LUCENE_40);
    } else if (analyzer.equals("StandardAnalyzer")) {
      a = new StandardAnalyzer(Version.LUCENE_40, CharArraySet.EMPTY_SET);
    } else if (analyzer.equals("ShingleStandardAnalyzer")) {
      a = new ShingleAnalyzerWrapper(new StandardAnalyzer(Version.LUCENE_40, CharArraySet.EMPTY_SET),
                                     2, 2, ShingleFilter.TOKEN_SEPARATOR, true, true);
    } else {
      throw new RuntimeException("unknown analyzer " + analyzer);
    } 

    final ReferenceManager<IndexSearcher> mgr;
    final IndexWriter writer;
    final Directory dir;

    final String commit = args.getString("-commit");

    if (args.getFlag("-nrt")) {
      // TODO: factor out & share this CL processing w/ Indexer
      final int indexThreadCount = args.getInt("-indexThreadCount");
      final String lineDocsFile = args.getString("-lineDocsFile");
      final float docsPerSecPerThread = args.getFloat("-docsPerSecPerThread");
      final float reopenEverySec = args.getFloat("-reopenEverySec");
      final boolean storeBody = args.getFlag("-store");
      final boolean tvsBody = args.getFlag("-tvs");
      final boolean useCFS = args.getFlag("-cfs");
      final String defaultPostingsFormat = args.getString("-postingsFormat");
      final String idFieldPostingsFormat = args.getString("-idFieldPostingsFormat");
      final boolean verbose = args.getFlag("-verbose");
      final boolean cloneDocs = args.getFlag("-cloneDocs");

      final long reopenEveryMS = (long) (1000 * reopenEverySec);

      if (verbose) {
        InfoStream.setDefault(new PrintStreamInfoStream(System.out));
      }
      
      if (!dirImpl.equals("RAMDirectory")) {
        System.out.println("Wrap NRTCachingDirectory");
        dir0 = new NRTCachingDirectory(dir0, 20, 400.0);
      }

      dir = dir0;

      final IndexWriterConfig iwc = new IndexWriterConfig(Version.LUCENE_40, a);
      iwc.setOpenMode(IndexWriterConfig.OpenMode.APPEND);
      iwc.setRAMBufferSizeMB(256.0);
      iwc.setIndexDeletionPolicy(NoDeletionPolicy.INSTANCE);

      if (commit != null && commit.length() > 0) {
        System.out.println("Opening writer on commit=" + commit);
        iwc.setIndexCommit(findCommitPoint(commit, dir));
      }

      ((TieredMergePolicy) iwc.getMergePolicy()).setUseCompoundFile(useCFS);

      final Codec codec = new Lucene40Codec() {
          @Override
          public PostingsFormat getPostingsFormatForField(String field) {
            return PostingsFormat.forName(field.equals("id") ?
                                          idFieldPostingsFormat : defaultPostingsFormat);
          }
        };
      iwc.setCodec(codec);

      final ConcurrentMergeScheduler cms = (ConcurrentMergeScheduler) iwc.getMergeScheduler();
      // Make sure merges run @ higher prio than indexing:
      cms.setMergeThreadPriority(Thread.currentThread().getPriority()+2);
      // Only let one merge run at a time...
      cms.setMaxThreadCount(1);
      // ... but queue up up to 4, before index thread is stalled:
      cms.setMaxMergeCount(4);

      iwc.setMergedSegmentWarmer(new IndexWriter.IndexReaderWarmer() {
          @Override
          public void warm(AtomicReader reader) throws IOException {
            final long t0 = System.currentTimeMillis();
            //System.out.println("DO WARM: " + reader);
            IndexSearcher s = new IndexSearcher(reader);
            s.search(new TermQuery(new Term(fieldName, "united")), 10);
            final long t1 = System.currentTimeMillis();
            System.out.println("warm took " + (t1-t0) + " msec");
          }
        });
      
      writer = new IndexWriter(dir, iwc);

      IndexThreads threads = new IndexThreads(new Random(17), writer, lineDocsFile, storeBody, tvsBody, indexThreadCount, -1, false, false, true, docsPerSecPerThread, cloneDocs);
      threads.start();

      mgr = new SearcherManager(writer, true, new SearcherFactory() {
          @Override
          public IndexSearcher newSearcher(IndexReader reader) {
            IndexSearcher s = new IndexSearcher(reader);
            s.setSimilarity(sim);
            return s;
          }
        });

      System.out.println("reopen every " + reopenEverySec);

      Thread reopenThread = new Thread() {
          @Override
          public void run() {
            try {
              final long startMS = System.currentTimeMillis();

              int reopenCount = 1;
              while (true) {
                final long nextReopenMS = startMS + (reopenCount * reopenEveryMS);
                final long sleepMS = Math.max(100, nextReopenMS - System.currentTimeMillis());
                Thread.sleep(sleepMS);
                mgr.maybeRefresh();
                reopenCount++;
                if (dir instanceof RAMDirectory) {
                  System.out.println(String.format(Locale.ENGLISH, "%.1fs: index: %d bytes", (System.currentTimeMillis() - startMS)/1000.0, ((RAMDirectory) dir).sizeInBytes()));
                } else {
                  System.out.println(String.format(Locale.ENGLISH, "%.1fs: done reopen", (System.currentTimeMillis() - startMS)/1000.0));
                }
              }
            } catch (Exception e) {
              throw new RuntimeException(e);
            }
          }
        };
      reopenThread.setName("ReopenThread");
      reopenThread.setPriority(4+Thread.currentThread().getPriority());
      reopenThread.start();

    } else {
      dir = dir0;
      writer = null;
      final DirectoryReader reader;
      if (commit != null && commit.length() > 0) {
        System.out.println("Opening searcher on commit=" + commit);
        reader = DirectoryReader.open(findCommitPoint(commit, dir));
      } else {
        // open last commit
        reader = DirectoryReader.open(dir);
      }
      IndexSearcher s = new IndexSearcher(reader);
      s.setSimilarity(sim);
      
      mgr = new SingleIndexSearcher(s);
    }

    //System.out.println("searcher=" + searcher);

    final Random staticRandom = new Random(staticRandomSeed);
    final Random random = new Random(randomSeed);

    final DirectSpellChecker spellChecker = new DirectSpellChecker();
    final IndexState indexState = new IndexState(mgr, fieldName, spellChecker);

    Map<Double,Filter> filters = new HashMap<Double,Filter>();
    // TODO: populate filters the next time we want to test
    // filter perf:
    /*
    filter = new CachingWrapperFilter(new PreComputedRandomFilter(reader, random, filterPct));
    */

    final QueryParser queryParser = new QueryParser(Version.LUCENE_40, "body", a);
    queryParser.setLowercaseExpandedTerms(false);
    TaskParser taskParser = new TaskParser(queryParser, fieldName, filters);

    final TaskSource tasks;

    if (tasksFile.startsWith("server:")) {
      int idx = tasksFile.indexOf(':', 8);
      if (idx == -1) {
        throw new RuntimeException("server is missing the port; should be server:interface:port (got: " + tasksFile + ")");
      }
      String iface = tasksFile.substring(7, idx);
      int port = Integer.valueOf(tasksFile.substring(1+idx));
      RemoteTaskSource remoteTasks = new RemoteTaskSource(iface, port, searchThreadCount, taskParser);

      // nocommit must stop thread?
      tasks = remoteTasks;
    } else {
      // Load the tasks from a file:
      final int taskRepeatCount = args.getInt("-taskRepeatCount");
      final int numTaskPerCat = args.getInt("-tasksPerCat");
      tasks = new LocalTaskSource(indexState, taskParser, tasksFile, staticRandom, random, numTaskPerCat, taskRepeatCount);
    }

    args.check();

    // Evil respeller:
    //spellChecker.setMinPrefix(0);
    //spellChecker.setMaxInspections(1024);
    final TaskThreads taskThreads = new TaskThreads(tasks, indexState, searchThreadCount);
    Thread.sleep(10);

    final long startNanos = System.nanoTime();
    taskThreads.start();
    taskThreads.finish();
    final long endNanos = System.nanoTime();

    System.out.println("\n" + ((endNanos - startNanos)/1000000.0) + " msec total");

    final List<Task> allTasks = tasks.getAllTasks();

    if (allTasks != null) {
      // Tasks were local: verify checksums:

      indexState.setDocIDToID();

      final Map<Task,Task> tasksSeen = new HashMap<Task,Task>();

      System.out.println("\nResults for " + allTasks.size() + " tasks:");
      for(final Task task : allTasks) {
        final Task other = tasksSeen.get(task);
        if (other != null) {
          if (task.checksum() != other.checksum()) {
            System.out.println("\nTASK:");
            task.printResults(indexState);
            System.out.println("\nOTHER TASK:");
            other.printResults(indexState);
            throw new RuntimeException("task " + task + " hit different checksums: " + task.checksum() + " vs " + other.checksum() + " other=" + other);
          }
        } else {
          tasksSeen.put(task, task);
        }
        System.out.println("\nTASK: " + task);
        System.out.println("  " + (task.runTimeNanos/1000000.0) + " msec");
        System.out.println("  thread " + task.threadID);
        task.printResults(indexState);
      }

      allTasks.clear();
    }

    mgr.close();

    if (writer != null) {
      // Don't actually commit any index changes:
      writer.rollback();
    }

    dir.close();

    if (printHeap) {

      // Try to get RAM usage -- some ideas poached from http://www.javaworld.com/javaworld/javatips/jw-javatip130.html
      final Runtime runtime = Runtime.getRuntime();
      long usedMem1 = usedMemory(runtime);
      long usedMem2 = Long.MAX_VALUE;
      for(int iter=0;iter<10;iter++) {
        runtime.runFinalization();
        runtime.gc();
        Thread.yield();
        Thread.sleep(100);
        usedMem2 = usedMem1;
        usedMem1 = usedMemory(runtime);
      }
      System.out.println("\nHEAP: " + usedMemory(runtime));
    }
  }

  private static long usedMemory(Runtime runtime) {
    return runtime.totalMemory() - runtime.freeMemory();
  }
}
