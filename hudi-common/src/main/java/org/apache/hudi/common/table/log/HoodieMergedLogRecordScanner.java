/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.hudi.common.table.log;

import org.apache.hudi.common.config.HoodieCommonConfig;
import org.apache.hudi.common.model.DeleteRecord;
import org.apache.hudi.common.model.HoodieAvroRecord;
import org.apache.hudi.common.model.HoodieKey;
import org.apache.hudi.common.model.HoodieOperation;
import org.apache.hudi.common.model.HoodieRecord;
import org.apache.hudi.common.model.HoodieRecordPayload;
import org.apache.hudi.common.util.DefaultSizeEstimator;
import org.apache.hudi.common.util.HoodieRecordSizeEstimator;
import org.apache.hudi.common.util.HoodieTimer;
import org.apache.hudi.common.util.Option;
import org.apache.hudi.common.util.ReflectionUtils;
import org.apache.hudi.common.util.SpillableMapUtils;
import org.apache.hudi.common.util.collection.ExternalSpillableMap;
import org.apache.hudi.exception.HoodieIOException;

import org.apache.avro.Schema;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hudi.internal.schema.InternalSchema;
import org.apache.log4j.LogManager;
import org.apache.log4j.Logger;

import java.io.IOException;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import static org.apache.hudi.common.util.ValidationUtils.checkState;

/**
 * Scans through all the blocks in a list of HoodieLogFile and builds up a compacted/merged list of records which will
 * be used as a lookup table when merging the base columnar file with the redo log file.
 * <p>
 * NOTE: If readBlockLazily is turned on, does not merge, instead keeps reading log blocks and merges everything at once
 * This is an optimization to avoid seek() back and forth to read new block (forward seek()) and lazily read content of
 * seen block (reverse and forward seek()) during merge | | Read Block 1 Metadata | | Read Block 1 Data | | | Read Block
 * 2 Metadata | | Read Block 2 Data | | I/O Pass 1 | ..................... | I/O Pass 2 | ................. | | | Read
 * Block N Metadata | | Read Block N Data |
 * <p>
 * This results in two I/O passes over the log file.
 */

public class HoodieMergedLogRecordScanner extends AbstractHoodieLogRecordReader
    implements Iterable<HoodieRecord<? extends HoodieRecordPayload>> {

  private static final Logger LOG = LogManager.getLogger(HoodieMergedLogRecordScanner.class);
  // A timer for calculating elapsed time in millis
  public final HoodieTimer timer = new HoodieTimer();
  // Final map of compacted/merged records
  protected final ExternalSpillableMap<String, HoodieRecord<? extends HoodieRecordPayload>> records;
  // count of merged records in log
  private long numMergedRecordsInLog;
  private long maxMemorySizeInBytes;
  // Stores the total time taken to perform reading and merging of log blocks
  private long totalTimeTakenToReadAndMergeBlocks;

  @SuppressWarnings("unchecked")
  protected HoodieMergedLogRecordScanner(FileSystem fs, String basePath, List<String> logFilePaths, Schema readerSchema,
                                         String latestInstantTime, Long maxMemorySizeInBytes, boolean readBlocksLazily,
                                         boolean reverseReader, int bufferSize, String spillableMapBasePath,
                                         Option<InstantRange> instantRange,
                                         ExternalSpillableMap.DiskMapType diskMapType,
                                         boolean isBitCaskDiskMapCompressionEnabled,
                                         boolean withOperationField, boolean forceFullScan,
                                         Option<String> partitionName, InternalSchema internalSchema) {
    super(fs, basePath, logFilePaths, readerSchema, latestInstantTime, readBlocksLazily, reverseReader, bufferSize,
        instantRange, withOperationField,
        forceFullScan, partitionName, internalSchema);
    try {
      // Store merged records for all versions for this log file, set the in-memory footprint to maxInMemoryMapSize
      this.records = new ExternalSpillableMap<>(maxMemorySizeInBytes, spillableMapBasePath, new DefaultSizeEstimator(),
          new HoodieRecordSizeEstimator(readerSchema), diskMapType, isBitCaskDiskMapCompressionEnabled);
      this.maxMemorySizeInBytes = maxMemorySizeInBytes;
    } catch (IOException e) {
      throw new HoodieIOException("IOException when creating ExternalSpillableMap at " + spillableMapBasePath, e);
    }

    if (forceFullScan) {
      performScan();
    }
  }

  protected void performScan() {
    // Do the scan and merge
    timer.startTimer();
    scan();
    this.totalTimeTakenToReadAndMergeBlocks = timer.endTimer();
    this.numMergedRecordsInLog = records.size();
    LOG.info("Number of log files scanned => " + logFilePaths.size());
    LOG.info("MaxMemoryInBytes allowed for compaction => " + maxMemorySizeInBytes);
    LOG.info("Number of entries in MemoryBasedMap in ExternalSpillableMap => " + records.getInMemoryMapNumEntries());
    LOG.info(
        "Total size in bytes of MemoryBasedMap in ExternalSpillableMap => " + records.getCurrentInMemoryMapSize());
    LOG.info("Number of entries in BitCaskDiskMap in ExternalSpillableMap => " + records.getDiskBasedMapNumEntries());
    LOG.info("Size of file spilled to disk => " + records.getSizeOfFileOnDiskInBytes());
  }

  @Override
  public Iterator<HoodieRecord<? extends HoodieRecordPayload>> iterator() {
    checkState(forceFullScan, "Record reader has to be in full-scan mode to use this API");
    return records.iterator();
  }

  public Map<String, HoodieRecord<? extends HoodieRecordPayload>> getRecords() {
    checkState(forceFullScan, "Record reader has to be in full-scan mode to use this API");
    return records;
  }

  public long getNumMergedRecordsInLog() {
    return numMergedRecordsInLog;
  }

  /**
   * Returns the builder for {@code HoodieMergedLogRecordScanner}.
   */
  public static HoodieMergedLogRecordScanner.Builder newBuilder() {
    return new Builder();
  }

  @Override
  protected void processNextRecord(HoodieRecord<? extends HoodieRecordPayload> hoodieRecord) throws IOException {
    String key = hoodieRecord.getRecordKey();
    if (records.containsKey(key)) {
      // Merge and store the merged record. The HoodieRecordPayload implementation is free to decide what should be
      // done when a DELETE (empty payload) is encountered before or after an insert/update.

      HoodieRecord<? extends HoodieRecordPayload> oldRecord = records.get(key);
      HoodieRecordPayload oldValue = oldRecord.getData();
      HoodieRecordPayload combinedValue = hoodieRecord.getData().preCombine(oldValue);
      // If combinedValue is oldValue, no need rePut oldRecord
      if (combinedValue != oldValue) {
        HoodieOperation operation = hoodieRecord.getOperation();
        records.put(key, new HoodieAvroRecord<>(new HoodieKey(key, hoodieRecord.getPartitionPath()), combinedValue, operation));
      }
    } else {
      // Put the record as is
      records.put(key, hoodieRecord);
    }
  }

  @Override
  protected void processNextDeletedRecord(DeleteRecord deleteRecord) {
    String key = deleteRecord.getRecordKey();
    HoodieRecord<? extends HoodieRecordPayload> oldRecord = records.get(key);
    if (oldRecord != null) {
      // Merge and store the merged record. The ordering val is taken to decide whether the same key record
      // should be deleted or be kept. The old record is kept only if the DELETE record has smaller ordering val.
      // For same ordering values, uses the natural order(arrival time semantics).

      Comparable curOrderingVal = oldRecord.getData().getOrderingValue();
      Comparable deleteOrderingVal = deleteRecord.getOrderingValue();
      // Checks the ordering value does not equal to 0
      // because we use 0 as the default value which means natural order
      boolean choosePrev = !deleteOrderingVal.equals(0)
          && ReflectionUtils.isSameClass(curOrderingVal, deleteOrderingVal)
          && curOrderingVal.compareTo(deleteOrderingVal) > 0;
      if (choosePrev) {
        // The DELETE message is obsolete if the old message has greater orderingVal.
        return;
      }
    }
    // Put the DELETE record
    records.put(key, SpillableMapUtils.generateEmptyPayload(key,
        deleteRecord.getPartitionPath(), deleteRecord.getOrderingValue(), getPayloadClassFQN()));
  }

  public long getTotalTimeTakenToReadAndMergeBlocks() {
    return totalTimeTakenToReadAndMergeBlocks;
  }

  public void close() {
    if (records != null) {
      records.close();
    }
  }

  /**
   * Builder used to build {@code HoodieUnMergedLogRecordScanner}.
   */
  public static class Builder extends AbstractHoodieLogRecordReader.Builder {
    protected FileSystem fs;
    protected String basePath;
    protected List<String> logFilePaths;
    protected Schema readerSchema;
    private InternalSchema internalSchema = InternalSchema.getEmptyInternalSchema();
    protected String latestInstantTime;
    protected boolean readBlocksLazily;
    protected boolean reverseReader;
    protected int bufferSize;
    // specific configurations
    protected Long maxMemorySizeInBytes;
    protected String spillableMapBasePath;
    protected ExternalSpillableMap.DiskMapType diskMapType = HoodieCommonConfig.SPILLABLE_DISK_MAP_TYPE.defaultValue();
    protected boolean isBitCaskDiskMapCompressionEnabled = HoodieCommonConfig.DISK_MAP_BITCASK_COMPRESSION_ENABLED.defaultValue();
    // incremental filtering
    protected Option<InstantRange> instantRange = Option.empty();
    protected String partitionName;
    // operation field default false
    private boolean withOperationField = false;

    @Override
    public Builder withFileSystem(FileSystem fs) {
      this.fs = fs;
      return this;
    }

    @Override
    public Builder withBasePath(String basePath) {
      this.basePath = basePath;
      return this;
    }

    @Override
    public Builder withLogFilePaths(List<String> logFilePaths) {
      this.logFilePaths = logFilePaths;
      return this;
    }

    @Override
    public Builder withReaderSchema(Schema schema) {
      this.readerSchema = schema;
      return this;
    }

    @Override
    public Builder withLatestInstantTime(String latestInstantTime) {
      this.latestInstantTime = latestInstantTime;
      return this;
    }

    @Override
    public Builder withReadBlocksLazily(boolean readBlocksLazily) {
      this.readBlocksLazily = readBlocksLazily;
      return this;
    }

    @Override
    public Builder withReverseReader(boolean reverseReader) {
      this.reverseReader = reverseReader;
      return this;
    }

    @Override
    public Builder withBufferSize(int bufferSize) {
      this.bufferSize = bufferSize;
      return this;
    }

    @Override
    public Builder withInstantRange(Option<InstantRange> instantRange) {
      this.instantRange = instantRange;
      return this;
    }

    public Builder withMaxMemorySizeInBytes(Long maxMemorySizeInBytes) {
      this.maxMemorySizeInBytes = maxMemorySizeInBytes;
      return this;
    }

    public Builder withSpillableMapBasePath(String spillableMapBasePath) {
      this.spillableMapBasePath = spillableMapBasePath;
      return this;
    }

    public Builder withDiskMapType(ExternalSpillableMap.DiskMapType diskMapType) {
      this.diskMapType = diskMapType;
      return this;
    }

    public Builder withBitCaskDiskMapCompressionEnabled(boolean isBitCaskDiskMapCompressionEnabled) {
      this.isBitCaskDiskMapCompressionEnabled = isBitCaskDiskMapCompressionEnabled;
      return this;
    }

    public Builder withInternalSchema(InternalSchema internalSchema) {
      this.internalSchema = internalSchema == null ? InternalSchema.getEmptyInternalSchema() : internalSchema;
      return this;
    }

    public Builder withOperationField(boolean withOperationField) {
      this.withOperationField = withOperationField;
      return this;
    }

    @Override
    public Builder withPartition(String partitionName) {
      this.partitionName = partitionName;
      return this;
    }

    @Override
    public HoodieMergedLogRecordScanner build() {
      return new HoodieMergedLogRecordScanner(fs, basePath, logFilePaths, readerSchema,
          latestInstantTime, maxMemorySizeInBytes, readBlocksLazily, reverseReader,
          bufferSize, spillableMapBasePath, instantRange,
          diskMapType, isBitCaskDiskMapCompressionEnabled, withOperationField, true,
          Option.ofNullable(partitionName), internalSchema);
    }
  }
}

