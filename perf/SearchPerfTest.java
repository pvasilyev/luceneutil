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

import java.io.File;
import java.io.IOException;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.en.EnglishAnalyzer;
import org.apache.lucene.analysis.shingle.ShingleAnalyzerWrapper;
import org.apache.lucene.analysis.shingle.ShingleFilter;
import org.apache.lucene.analysis.standard.ClassicAnalyzer;
import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.analysis.util.CharArraySet;
import org.apache.lucene.codecs.Codec;
import org.apache.lucene.codecs.PostingsFormat;
import org.apache.lucene.codecs.lucene41.Lucene41Codec;
import org.apache.lucene.facet.taxonomy.TaxonomyReader;
import org.apache.lucene.facet.taxonomy.directory.DirectoryTaxonomyReader;
import org.apache.lucene.index.AtomicReader;
import org.apache.lucene.index.ConcurrentMergeScheduler;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexCommit;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.index.MultiFields;
import org.apache.lucene.index.NoDeletionPolicy;
import org.apache.lucene.index.Term;
import org.apache.lucene.index.TieredMergePolicy;
import org.apache.lucene.queryparser.classic.QueryParser;
import org.apache.lucene.search.Filter;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.ReferenceManager;
import org.apache.lucene.search.SearcherFactory;
import org.apache.lucene.search.SearcherManager;
import org.apache.lucene.search.TermQuery;
import org.apache.lucene.search.similarities.Similarity;
import org.apache.lucene.search.spell.DirectSpellChecker;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.FileSwitchDirectory;
import org.apache.lucene.store.IOContext;
import org.apache.lucene.store.MMapDirectory;
import org.apache.lucene.store.NIOFSDirectory;
import org.apache.lucene.store.NRTCachingDirectory;
//import org.apache.lucene.store.NativePosixMMapDirectory;
import org.apache.lucene.store.RAMDirectory;
import org.apache.lucene.store.SimpleFSDirectory;
import org.apache.lucene.util.Constants;
import org.apache.lucene.util.InfoStream;
import org.apache.lucene.util.PrintStreamInfoStream;
import org.apache.lucene.util.RamUsageEstimator;
import org.apache.lucene.util.Version;

// TODO
//   - post queries on pao
//   - fix pk lookup to tolerate deletes
//   - get regexp title queries
//   - test shingle at search time

// commits: single, multi, delsingle, delmulti

// trunk:
//   javac -Xlint -Xlint:deprecation -cp .:$LUCENE_HOME/build/core/classes/java:$LUCENE_HOME/build/test-framework/classes/java:$LUCENE_HOME/build/queryparser/classes/java:$LUCENE_HOME/build/suggest/classes/java:$LUCENE_HOME/build/analysis/common/classes/java:$LUCENE_HOME/build/grouping/classes/java perf/SearchPerfTest.java perf/LineFileDocs.java perf/RandomFilter.java
//   java -cp .:$LUCENE_HOME/build/highlighter/classes/java:$LUCENE_HOME/build/codecs/classes/java:$LUCENE_HOME/build/core/classes/java:$LUCENE_HOME/build/test-framework/classes/java:$LUCENE_HOME/build/queryparser/classes/java:$LUCENE_HOME/build/suggest/classes/java:$LUCENE_HOME/build/analysis/common/classes/java:$LUCENE_HOME/build/grouping/classes/java perf.SearchPerfTest -dirImpl MMapDirectory -indexPath /l/scratch/indices/wikimedium10m.lucene.trunk2.Lucene41.nd10M/index -analyzer StandardAnalyzerNoStopWords -taskSource term.tasks -searchThreadCount 2 -field body -topN 10 -staticSeed 0 -seed 0 -similarity DefaultSimilarity -commit multi -hiliteImpl FastVectorHighlighter -log search.log -nrt -indexThreadCount 1 -docsPerSecPerThread 10 -reopenEverySec 5 -postingsFormat Lucene41 -idFieldPostingsFormat Lucene41 -taskRepeatCount 1000 -tasksPerCat 5 -lineDocsFile /lucenedata/enwiki/enwiki-20120502-lines-1k.txt

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

    // args: dirImpl indexPath numThread numIterPerThread
    // eg java SearchPerfTest /path/to/index 4 100
    final Args args = new Args(clArgs);

    Directory dir0;
    final boolean doFacets = args.getFlag("-facets");
    final String dirPath = args.getString("-indexPath") + "/index";
    final String dirImpl = args.getString("-dirImpl");

    OpenDirectory od = OpenDirectory.get(dirImpl);

    /*
    } else if (dirImpl.equals("NativePosixMMapDirectory")) {
      dir0 = new NativePosixMMapDirectory(new File(dirPath));
      ramDir = null;
      if (doFacets) {
        facetsDir = new NativePosixMMapDirectory(new File(facetsDirPath));
      }
    } else if (dirImpl.equals("CachingDirWrapper")) {
      dir0 = new CachingRAMDirectory(new MMapDirectory(new File(dirPath)));
      ramDir = null;
    } else if (dirImpl.equals("RAMExceptDirectPostingsDirectory")) {
      // Load only non-postings files into RAMDir (assumes
      // Lucene40PF is the wrapped PF):
      Set<String> postingsExtensions = new HashSet<String>();
      postingsExtensions.add("frq");
      postingsExtensions.add("prx");
      postingsExtensions.add("tip");
      postingsExtensions.add("tim");
      
      ramDir =  new RAMDirectory();
      Directory fsDir = new MMapDirectory(new File(dirPath));
      for (String file : fsDir.listAll()) {
        int idx = file.indexOf('.');
        if (idx != -1 && postingsExtensions.contains(file.substring(idx+1, file.length()))) {
          continue;
        }

        fsDir.copy(ramDir, file, file, IOContext.READ);
      }
      dir0 = new FileSwitchDirectory(postingsExtensions,
                                     fsDir,
                                     ramDir,
                                     true);
      if (doFacets) {
        facetsDir = new RAMDirectory(new SimpleFSDirectory(new File(facetsDirPath)), IOContext.READ);
      }
      */

    final RAMDirectory ramDir;
    dir0 = od.open(new File(dirPath));
    if (dir0 instanceof RAMDirectory) {
      ramDir = (RAMDirectory) dir0;
    } else {
      ramDir = null;
    }

    // TODO: NativeUnixDir?

    final String analyzer = args.getString("-analyzer");
    final String tasksFile = args.getString("-taskSource");
    final int searchThreadCount = args.getInt("-searchThreadCount");
    final String fieldName = args.getString("-field");
    final boolean printHeap = args.getFlag("-printHeap");
    final boolean doPKLookup = args.getFlag("-pk");
    final int topN = args.getInt("-topN");
    final boolean doStoredLoads = args.getFlag("-loadStoredFields");

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

    System.out.println("Using dir impl " + dir0.getClass().getName());
    System.out.println("Analyzer " + analyzer);
    System.out.println("Similarity " + similarity);
    System.out.println("Search thread count " + searchThreadCount);
    System.out.println("JVM " + (Constants.JRE_IS_64BIT ? "is" : "is not") + " 64bit");
    System.out.println("Pointer is " + RamUsageEstimator.NUM_BYTES_OBJECT_REF + " bytes");
 
    final Analyzer a;
    if (analyzer.equals("EnglishAnalyzer")) {
      a = new EnglishAnalyzer(Version.LUCENE_50);
    } else if (analyzer.equals("ClassicAnalyzer")) {
      a = new ClassicAnalyzer(Version.LUCENE_50);
    } else if (analyzer.equals("StandardAnalyzer")) {
      a = new StandardAnalyzer(Version.LUCENE_50);
    } else if (analyzer.equals("StandardAnalyzerNoStopWords")) {
      a = new StandardAnalyzer(Version.LUCENE_50, CharArraySet.EMPTY_SET);
    } else if (analyzer.equals("ShingleStandardAnalyzer")) {
      a = new ShingleAnalyzerWrapper(new StandardAnalyzer(Version.LUCENE_50, CharArraySet.EMPTY_SET),
                                     2, 2, ShingleFilter.TOKEN_SEPARATOR, true, true);
    } else {
      throw new RuntimeException("unknown analyzer " + analyzer);
    } 

    final ReferenceManager<IndexSearcher> mgr;
    final IndexWriter writer;
    final Directory dir;

    final String commit = args.getString("-commit");
    final String hiliteImpl = args.getString("-hiliteImpl");

    final String logFile = args.getString("-log");

    final long tSearcherStart = System.currentTimeMillis();

    final boolean verifyCheckSum = !args.getFlag("-skipVerifyChecksum");
    final boolean recacheFilterDeletes = args.getFlag("-recacheFilterDeletes");

    if (recacheFilterDeletes) {
      throw new UnsupportedOperationException("recacheFilterDeletes was deprecated");
    }

    if (args.getFlag("-nrt")) {
      // TODO: get taxoReader working here too
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
      
      if (!dirImpl.equals("RAMDirectory") && !dirImpl.equals("RAMExceptDirectPostingsDirectory")) {
        System.out.println("Wrap NRTCachingDirectory");
        dir0 = new NRTCachingDirectory(dir0, 20, 400.0);
      }

      dir = dir0;

      final IndexWriterConfig iwc = new IndexWriterConfig(Version.LUCENE_50, a);
      iwc.setOpenMode(IndexWriterConfig.OpenMode.APPEND);
      iwc.setRAMBufferSizeMB(256.0);
      iwc.setIndexDeletionPolicy(NoDeletionPolicy.INSTANCE);

      // TODO: also RAMDirExceptDirect...?  need to
      // ... block deletes against wrapped FSDir?
      if (dirImpl.equals("RAMDirectory")) {
        // Let IW remove files only referenced by starting commit:
        iwc.setIndexDeletionPolicy(new KeepNoCommitsDeletionPolicy());
      }
      
      if (commit != null && commit.length() > 0) {
        System.out.println("Opening writer on commit=" + commit);
        iwc.setIndexCommit(findCommitPoint(commit, dir));
      }

      ((TieredMergePolicy) iwc.getMergePolicy()).setUseCompoundFile(useCFS);
      //((TieredMergePolicy) iwc.getMergePolicy()).setMaxMergedSegmentMB(1024);
      //((TieredMergePolicy) iwc.getMergePolicy()).setReclaimDeletesWeight(3.0);
      //((TieredMergePolicy) iwc.getMergePolicy()).setMaxMergeAtOnce(4);

      final Codec codec = new Lucene41Codec() {
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
            System.out.println("warm segment=" + reader + " numDocs=" + reader.numDocs() + ": took " + (t1-t0) + " msec");
          }
        });
      
      writer = new IndexWriter(dir, iwc);
      System.out.println("Initial writer.maxDoc()=" + writer.maxDoc());

      // TODO: add -nrtBodyPostingsOffsets instead of
      // hardwired false:
      IndexThreads threads = new IndexThreads(new Random(17), writer, null, null, lineDocsFile, storeBody, tvsBody,
                                              false,
                                              indexThreadCount, -1,
                                              false, false, true, docsPerSecPerThread, cloneDocs);
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
                IndexSearcher s = mgr.acquire();
                try {
                  if (ramDir != null) {
                    System.out.println(String.format(Locale.ENGLISH, "%.1fs: index: %d bytes in RAMDir; writer.maxDoc()=%d; searcher.maxDoc()=%d; searcher.numDocs()=%d",
                                                     (System.currentTimeMillis() - startMS)/1000.0, ramDir.sizeInBytes(),
                                                     writer.maxDoc(), s.getIndexReader().maxDoc(), s.getIndexReader().numDocs()));
                    //String[] l = ramDir.listAll();
                    //Arrays.sort(l);
                    //for(String f : l) {
                    //System.out.println("  " + f + ": " + ramDir.fileLength(f));
                    //}
                  } else {
                    System.out.println(String.format(Locale.ENGLISH, "%.1fs: done reopen; writer.maxDoc()=%d; searcher.maxDoc()=%d; searcher.numDocs()=%d",
                                                     (System.currentTimeMillis() - startMS)/1000.0,
                                                     writer.maxDoc(), s.getIndexReader().maxDoc(),
                                                     s.getIndexReader().numDocs()));
                  }
                } finally {
                  mgr.release(s);
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
        System.out.println("maxDoc=" + reader.maxDoc());
      } else {
        // open last commit
        reader = DirectoryReader.open(dir);
      }
      IndexSearcher s = new IndexSearcher(reader);
      s.setSimilarity(sim);
      
      mgr = new SingleIndexSearcher(s);
    }
    System.out.println((System.currentTimeMillis() - tSearcherStart) + " msec to init searcher/NRT");

    //System.out.println("searcher=" + searcher);

    Map<String,TaxonomyReader> taxoReaders = null;

    List<FacetGroup> facetGroups = new ArrayList<FacetGroup>();
    if (doFacets) {
      IndexSearcher s = mgr.acquire();
      try {
        // EG: -facetGroup onlyDate:noparents:Date -facetGroup hierarchies:allparents:Date,characterCount ...
        for(String arg: args.getStrings("-facetGroup")) {
          FacetGroup fg = new FacetGroup(arg);
          facetGroups.add(fg);
          if (MultiFields.getTerms(s.getIndexReader(), "$"+fg.groupName) == null) {
            throw new IllegalArgumentException("this index doesn't have facet group named \"" + fg.groupName + "\"");
          }
        }

        if (facetGroups.size() != 1) {
          // TODO: fix this limitation!
          throw new IllegalArgumentException("can only run with one facet group now");
        }

      } finally {
        mgr.release(s);
      }

      // TODO: need to fix this to handle NRT:
      File f = new File(args.getString("-indexPath"), "facets");
      if (!f.exists()) {
        // Private taxo reader per group:
        taxoReaders = new HashMap<String,TaxonomyReader>();
        for(String sub : new File(args.getString("-indexPath")).list()) {
          if (sub.startsWith("facets.")) {
            String groupName = sub.substring(7);
            Directory taxoDir = od.open(new File(args.getString("-indexPath"), sub));
            TaxonomyReader tr = new DirectoryTaxonomyReader(taxoDir);
            System.out.println("Taxonomy for facet group \"" + groupName + "\" has " + tr.getSize() + " ords");
            taxoReaders.put(groupName, tr);
          }
        }
      } else {
        // Global taxo reader:
        Directory taxoDir = od.open(f);
        TaxonomyReader tr = new DirectoryTaxonomyReader(taxoDir);
        System.out.println("Taxonomy has " + tr.getSize() + " ords");
        for(FacetGroup fg : facetGroups) {
          taxoReaders.put(fg.groupName, tr);
        }
      }
    }

    final Random staticRandom = new Random(staticRandomSeed);
    final Random random = new Random(randomSeed);

    final DirectSpellChecker spellChecker = new DirectSpellChecker();
    final IndexState indexState = new IndexState(mgr, taxoReaders, fieldName, spellChecker, hiliteImpl, facetGroups);

    Map<Double,Filter> filters = new HashMap<Double,Filter>();
    final QueryParser queryParser = new QueryParser(Version.LUCENE_50, "body", a);
    queryParser.setLowercaseExpandedTerms(false);
    TaskParser taskParser = new TaskParser(queryParser, fieldName, filters, topN, staticRandom, doStoredLoads);

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
      tasks = new LocalTaskSource(indexState, taskParser, tasksFile, staticRandom, random, numTaskPerCat, taskRepeatCount, doPKLookup);
      System.out.println("Task repeat count " + taskRepeatCount);
      System.out.println("Tasks file " + tasksFile);
      System.out.println("Num task per cat " + numTaskPerCat);
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

    PrintStream out = new PrintStream(logFile);

    if (allTasks != null) {
      // Tasks were local: verify checksums:

      indexState.setDocIDToID();

      final Map<Task,Task> tasksSeen = new HashMap<Task,Task>();

      out.println("\nResults for " + allTasks.size() + " tasks:");
      for(final Task task : allTasks) {
        if (verifyCheckSum) {
          final Task other = tasksSeen.get(task);
          if (other != null) {
            if (task.checksum() != other.checksum()) {
              System.out.println("\nTASK:");
              task.printResults(System.out, indexState);
              System.out.println("\nOTHER TASK:");
              other.printResults(System.out, indexState);
              throw new RuntimeException("task " + task + " hit different checksums: " + task.checksum() + " vs " + other.checksum() + " other=" + other);
            }
          } else {
            tasksSeen.put(task, task);
          }
        }
        out.println("\nTASK: " + task);
        out.println("  " + (task.runTimeNanos/1000000.0) + " msec");
        out.println("  thread " + task.threadID);
        task.printResults(out, indexState);
      }

      allTasks.clear();
    }

    mgr.close();

    for(TaxonomyReader tr : taxoReaders.values()) {
      tr.close();
    }

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
      out.println("\nHEAP: " + usedMemory(runtime));
    }
    out.close();
  }

  private static long usedMemory(Runtime runtime) {
    return runtime.totalMemory() - runtime.freeMemory();
  }
}
