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

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.Closeable;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.text.ParsePosition;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.concurrent.atomic.AtomicLong;

import org.apache.lucene.document.*;
import org.apache.lucene.facet.index.FacetFields;
import org.apache.lucene.facet.index.categorypolicy.OrdinalPolicy;
import org.apache.lucene.facet.index.params.CategoryListParams;
//import org.apache.lucene.facet.index.params.DefaultFacetIndexingParams;
import org.apache.lucene.facet.taxonomy.CategoryPath;
import org.apache.lucene.facet.taxonomy.TaxonomyWriter;
import org.apache.lucene.index.DocValues;
import org.apache.lucene.index.FieldInfo.IndexOptions;
import org.apache.lucene.index.FieldInfo;
import org.apache.lucene.index.IndexableField;
import org.apache.lucene.util.BytesRef;

public class LineFileDocs implements Closeable {

  private BufferedReader reader;
  private final static int BUFFER_SIZE = 1 << 16;     // 64K
  private final boolean doRepeat;
  private final String path;
  private final boolean storeBody;
  private final boolean tvsBody;
  private final boolean bodyPostingsOffsets;
  private final AtomicLong bytesIndexed = new AtomicLong();
  private final boolean doClone;
  private final TaxonomyWriter facetWriter;

  public LineFileDocs(String path, boolean doRepeat, boolean storeBody, boolean tvsBody, boolean bodyPostingsOffsets, boolean doClone,
                      TaxonomyWriter facetWriter) throws IOException {
    this.path = path;
    this.storeBody = storeBody;
    this.tvsBody = tvsBody;
    this.bodyPostingsOffsets = bodyPostingsOffsets;
    this.doClone = doClone;
    this.doRepeat = doRepeat;
    this.facetWriter = facetWriter;
    open();
  }

  public long getBytesIndexed() {
    return bytesIndexed.get();
  }

  private void open() throws IOException {
    InputStream is = new FileInputStream(path);
    reader = new BufferedReader(new InputStreamReader(is, "UTF-8"), BUFFER_SIZE);
    String firstLine = reader.readLine();
    if (firstLine.startsWith("FIELDS_HEADER_INDICATOR")) {
      if (!firstLine.trim().equals("FIELDS_HEADER_INDICATOR###	doctitle	docdate	body")) {
        throw new IllegalArgumentException("unrecognized header in line docs file: " + firstLine.trim());
      }
      // Skip header
    } else {
      // Old format: no header
      reader.close();
      is = new FileInputStream(path);
      reader = new BufferedReader(new InputStreamReader(is, "UTF-8"), BUFFER_SIZE);
    }
  }

  public synchronized void close() throws IOException {
    if (reader != null) {
      reader.close();
      reader = null;
    }
  }

  public static String intToID(int id) {
    // Base 36, prefixed with 0s to be length 6 (= 2.2 B)
    final String s = String.format("%6s", Integer.toString(id, Character.MAX_RADIX)).replace(' ', '0');
    //System.out.println("fromint: " + id + " -> " + s);
    return s;
  }

  public static int idToInt(BytesRef id) {
    // Decode base 36
    int accum = 0;
    int downTo = id.length + id.offset - 1;
    int multiplier = 1;
    while(downTo >= id.offset) {
      final char ch = (char) (id.bytes[downTo--]&0xff);
      final int digit;
      if (ch >= '0' && ch <= '9') {
        digit = ch - '0';
      } else if (ch >= 'a' && ch <= 'z') {
        digit = 10 + (ch-'a');
      } else {
        assert false;
        digit = -1;
      }
      accum += multiplier * digit;
      multiplier *= 36;
    }

    //System.out.println("toint: " + id.utf8ToString() + " -> " + accum);
    return accum;
  }

  public static int idToInt(String id) {
    // Decode base 36
    int accum = 0;
    int downTo = id.length() - 1;
    int multiplier = 1;
    while(downTo >= 0) {
      final char ch = id.charAt(downTo--);
      final int digit;
      if (ch >= '0' && ch <= '9') {
        digit = ch - '0';
      } else if (ch >= 'a' && ch <= 'z') {
        digit = 10 + (ch-'a');
      } else {
        assert false;
        digit = -1;
      }
      accum += multiplier * digit;
      multiplier *= 36;
    }

    //System.out.println("toint: " + id + " -> " + accum);
    return accum;
  }

  private final static char SEP = '\t';

  public static final class DocState {
    final Document doc;
    final Field titleTokenized;
    final Field title;
    final Field titleDV;
    final Field body;
    final Field id;
    final Field date;
    final LongField dateMSec;
    final IntField timeSec;
    final SimpleDateFormat dateParser = new SimpleDateFormat("dd-MMM-yyyy HH:mm:ss", Locale.US);
    final Calendar dateCal = Calendar.getInstance();
    final ParsePosition datePos = new ParsePosition(0);
    final FacetFields facetBuilder;

    DocState(boolean storeBody, boolean tvsBody, boolean bodyPostingsOffsets, TaxonomyWriter facetWriter) {
      doc = new Document();
      
      title = new StringField("title", "", Field.Store.NO);
      doc.add(title);

      titleDV = new SortedBytesDocValuesField("titleDV", new BytesRef(""));
      doc.add(titleDV);

      titleTokenized = new Field("titleTokenized", "", TextField.TYPE_STORED);
      doc.add(titleTokenized);
      
      FieldType bodyFieldType = new FieldType(TextField.TYPE_NOT_STORED);
      if (storeBody) {
        bodyFieldType.setStored(true);
      }

      if (tvsBody) {
        bodyFieldType.setStoreTermVectors(true);
        bodyFieldType.setStoreTermVectorOffsets(true);
        bodyFieldType.setStoreTermVectorPositions(true);
      }

      if (bodyPostingsOffsets) {
        bodyFieldType.setIndexOptions(IndexOptions.DOCS_AND_FREQS_AND_POSITIONS_AND_OFFSETS);
      }

      body = new Field("body", "", bodyFieldType);
      doc.add(body);

      id = new Field("id", "", StringField.TYPE_STORED);
      doc.add(id);

      date = new Field("date", "", StringField.TYPE_STORED);
      doc.add(date);

      dateMSec = new LongField("datenum", 0L, Field.Store.NO);
      doc.add(dateMSec);

      timeSec = new IntField("timesecnum", 0, Field.Store.NO);
      doc.add(timeSec);

      if (facetWriter != null) {
        if (true) {
          facetBuilder = new FacetFields(facetWriter);
        } else {
          /*
          facetBuilder = new CategoryDocumentBuilder(facetWriter,
                                                     new DefaultFacetIndexingParams() {
                                                       @Override
                                                       protected OrdinalPolicy fixedOrdinalPolicy() {
                                                         return OrdinalPolicy.NO_PARENTS;
                                                       }
                                                     });
          */
          facetBuilder = null;
        }
      } else {
        facetBuilder = null;
      }
    }
  }

  public DocState newDocState() {
    return new DocState(storeBody, tvsBody, bodyPostingsOffsets, facetWriter);
  }

  // TODO: is there a pre-existing way to do this!!!
  static Document cloneDoc(Document doc1) {
    final Document doc2 = new Document();
    for(IndexableField f0 : doc1.getFields()) {
      Field f = (Field) f0;
      if (f instanceof LongField) {
        doc2.add(new LongField(f.name(), ((LongField) f).numericValue().longValue(), Field.Store.NO));
      } else if (f instanceof IntField) {
        doc2.add(new IntField(f.name(), ((IntField) f).numericValue().intValue(), Field.Store.NO));
      } else if (f instanceof SortedBytesDocValuesField) {
        doc2.add(new SortedBytesDocValuesField(f.name(), f.binaryValue()));
      } else {
        Field field1 = f;
        Field field2 = new Field(field1.name(),
                                 field1.stringValue(),
                                 field1.fieldType());
        doc2.add(field2);
      }
    }

    return doc2;
  }

  private final ThreadLocal<DocState> threadDocs = new ThreadLocal<DocState>();

  private int readCount;

  public Document nextDoc(DocState doc) throws IOException {
    String line;
    final int myID;
    synchronized(this) {
      myID = readCount++;
      line = reader.readLine();
      if (line == null) {
        if (doRepeat) {
          close();
          open();
          line = reader.readLine();
        } else {
          return null;
        }
      }
    }

    bytesIndexed.addAndGet(line.length());

    int spot = line.indexOf(SEP);
    if (spot == -1) {
      throw new RuntimeException("line: [" + line + "] is in an invalid format !");
    }
    int spot2 = line.indexOf(SEP, 1 + spot);
    if (spot2 == -1) {
      throw new RuntimeException("line: [" + line + "] is in an invalid format !");
    }

    doc.body.setStringValue(line.substring(1+spot2, line.length()));
    final String title = line.substring(0, spot);
    doc.title.setStringValue(title);
    doc.titleDV.setBytesValue(new BytesRef(title));
    doc.titleTokenized.setStringValue(title);
    final String dateString = line.substring(1+spot, spot2);
    doc.date.setStringValue(dateString);
    doc.id.setStringValue(intToID(myID));

    doc.datePos.setIndex(0);
    final Date date = doc.dateParser.parse(dateString, doc.datePos);
    if (date == null) {
      System.out.println("FAILED: " + dateString);
    }
    doc.dateMSec.setLongValue(date.getTime());

    doc.dateCal.setTime(date);
    final int sec = doc.dateCal.get(Calendar.HOUR_OF_DAY)*3600 + doc.dateCal.get(Calendar.MINUTE)*60 + doc.dateCal.get(Calendar.SECOND);
    doc.timeSec.setIntValue(sec);

    if (doc.facetBuilder != null) {
      // TODO: is there a way to "reuse" a field w/ facets
      doc.doc.removeFields(CategoryListParams.DEFAULT_TERM.field());
      List<CategoryPath> paths = Collections.singletonList(new CategoryPath("Date",
                                                                            ""+doc.dateCal.get(Calendar.YEAR),
                                                                            ""+doc.dateCal.get(Calendar.MONTH),
                                                                            ""+doc.dateCal.get(Calendar.DAY_OF_MONTH)));
      doc.facetBuilder.addFields(doc.doc, paths);
      //doc.doc.add(new DocValuesFacetField(paths, facetWriter));
    }

    if (doClone) {
      return cloneDoc(doc.doc);
    } else {
      return doc.doc;
    }
  }
}

