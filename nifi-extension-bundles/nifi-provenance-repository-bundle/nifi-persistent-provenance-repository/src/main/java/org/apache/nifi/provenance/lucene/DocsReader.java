/*
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
package org.apache.nifi.provenance.lucene;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.lucene.document.Document;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.index.IndexableField;
import org.apache.lucene.index.StoredFields;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.TopDocs;
import org.apache.nifi.provenance.ProvenanceEventRecord;
import org.apache.nifi.provenance.SearchableFields;
import org.apache.nifi.provenance.StandardProvenanceEventRecord;
import org.apache.nifi.provenance.authorization.EventAuthorizer;
import org.apache.nifi.provenance.serialization.RecordReader;
import org.apache.nifi.provenance.serialization.RecordReaders;
import org.apache.nifi.provenance.toc.TocReader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class DocsReader {
    private final Logger logger = LoggerFactory.getLogger(DocsReader.class);

    public Set<ProvenanceEventRecord> read(final TopDocs topDocs, final EventAuthorizer authorizer, final IndexReader indexReader, final Collection<Path> allProvenanceLogFiles,
            final AtomicInteger retrievalCount, final int maxResults, final int maxAttributeChars) throws IOException {
        if (retrievalCount.get() >= maxResults) {
            return Collections.emptySet();
        }

        final long start = System.nanoTime();
        final ScoreDoc[] scoreDocs = topDocs.scoreDocs;
        final int numDocs = Math.min(scoreDocs.length, maxResults);
        final List<Document> docs = new ArrayList<>(numDocs);
        final StoredFields storedFields = indexReader.storedFields();

        for (int i = numDocs - 1; i >= 0; i--) {
            final int docId = scoreDocs[i].doc;
            final Document d = storedFields.document(docId);
            docs.add(d);
        }

        final long readDocuments = System.nanoTime() - start;
        logger.debug("Reading {} Lucene Documents took {} millis", docs.size(), TimeUnit.NANOSECONDS.toMillis(readDocuments));
        return read(docs, authorizer, allProvenanceLogFiles, retrievalCount, maxResults, maxAttributeChars);
    }


    private long getByteOffset(final Document d, final RecordReader reader) {
        final IndexableField blockField = d.getField(FieldNames.BLOCK_INDEX);
        if ( blockField != null ) {
            final int blockIndex = blockField.numericValue().intValue();
            final TocReader tocReader = reader.getTocReader();
            return tocReader.getBlockOffset(blockIndex);
        }

        return d.getField(FieldNames.STORAGE_FILE_OFFSET).numericValue().longValue();
    }


    private ProvenanceEventRecord getRecord(final Document d, final RecordReader reader) throws IOException {
        final IndexableField blockField = d.getField(FieldNames.BLOCK_INDEX);
        if ( blockField == null ) {
            reader.skipTo(getByteOffset(d, reader));
        } else {
            reader.skipToBlock(blockField.numericValue().intValue());
        }

        StandardProvenanceEventRecord record;
        while ( (record = reader.nextRecord()) != null) {
            final IndexableField idField = d.getField(SearchableFields.Identifier.getSearchableFieldName());
            if ( idField == null || idField.numericValue().longValue() == record.getEventId() ) {
                break;
            }
        }

        if (record == null) {
            logger.warn("Failed to read Provenance Event for '{}'. The event file may be missing or corrupted", d);
        }

        return record;
    }

    public Set<ProvenanceEventRecord> read(final List<Document> docs, final EventAuthorizer authorizer, final Collection<Path> allProvenanceLogFiles,
            final AtomicInteger retrievalCount, final int maxResults, final int maxAttributeChars) throws IOException {

        if (retrievalCount.get() >= maxResults) {
            return Collections.emptySet();
        }

        final long start = System.nanoTime();
        final Set<ProvenanceEventRecord> matchingRecords = new LinkedHashSet<>();
        final Map<String, List<Document>> byStorageNameDocGroups = LuceneUtil.groupDocsByStorageFileName(docs);

        int eventsReadThisFile = 0;
        int logFileCount = 0;

        for (String storageFileName : byStorageNameDocGroups.keySet()) {
            final File provenanceEventFile = LuceneUtil.getProvenanceLogFile(storageFileName, allProvenanceLogFiles);
            if (provenanceEventFile == null) {
                logger.warn("Could not find Provenance Log File with "
                    + "basename {} in the Provenance Repository; assuming "
                    + "file has expired and continuing without it", storageFileName);
                continue;
            }

            try (final RecordReader reader = RecordReaders.newRecordReader(provenanceEventFile, allProvenanceLogFiles, maxAttributeChars)) {
                final Iterator<Document> docIter = byStorageNameDocGroups.get(storageFileName).iterator();
                while (docIter.hasNext() && retrievalCount.getAndIncrement() < maxResults) {
                    final ProvenanceEventRecord event = getRecord(docIter.next(), reader);
                    if (event != null && authorizer.isAuthorized(event)) {
                        matchingRecords.add(event);
                        eventsReadThisFile++;
                    }
                }
            } catch (final Exception e) {
                logger.warn("Failed to read Provenance Events. The event file '"
                    + provenanceEventFile.getAbsolutePath() + "' may be missing or corrupt.", e);
            }
        }

        logger.debug("Read {} records from previous file", eventsReadThisFile);
        final long millis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
        logger.debug("Took {} ms to read {} events from {} prov log files", millis, matchingRecords.size(),
                logFileCount);

        return matchingRecords;
    }
}
