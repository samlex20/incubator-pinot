/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.pinot.core.segment.creator.impl;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import com.google.common.collect.Iterables;
import java.io.File;
import java.io.IOException;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.apache.commons.configuration.ConfigurationException;
import org.apache.commons.configuration.PropertiesConfiguration;
import org.apache.pinot.common.utils.FileUtils;
import org.apache.pinot.core.data.partition.PartitionFunction;
import org.apache.pinot.core.indexsegment.generator.SegmentGeneratorConfig;
import org.apache.pinot.core.io.compression.ChunkCompressorFactory;
import org.apache.pinot.core.io.util.PinotDataBitSet;
import org.apache.pinot.core.io.writer.impl.BaseChunkSVForwardIndexWriter;
import org.apache.pinot.core.segment.creator.ColumnIndexCreationInfo;
import org.apache.pinot.core.segment.creator.ForwardIndexCreator;
import org.apache.pinot.core.segment.creator.InvertedIndexCreator;
import org.apache.pinot.core.segment.creator.SegmentCreator;
import org.apache.pinot.core.segment.creator.SegmentIndexCreationInfo;
import org.apache.pinot.core.segment.creator.TextIndexType;
import org.apache.pinot.core.segment.creator.impl.fwd.MultiValueUnsortedForwardIndexCreator;
import org.apache.pinot.core.segment.creator.impl.fwd.SingleValueFixedByteRawIndexCreator;
import org.apache.pinot.core.segment.creator.impl.fwd.SingleValueSortedForwardIndexCreator;
import org.apache.pinot.core.segment.creator.impl.fwd.SingleValueUnsortedForwardIndexCreator;
import org.apache.pinot.core.segment.creator.impl.fwd.SingleValueVarByteRawIndexCreator;
import org.apache.pinot.core.segment.creator.impl.inv.OffHeapBitmapInvertedIndexCreator;
import org.apache.pinot.core.segment.creator.impl.inv.OnHeapBitmapInvertedIndexCreator;
import org.apache.pinot.core.segment.creator.impl.inv.text.LuceneTextIndexCreator;
import org.apache.pinot.core.segment.creator.impl.nullvalue.NullValueVectorCreator;
import org.apache.pinot.spi.config.table.FieldConfig;
import org.apache.pinot.spi.data.DateTimeFieldSpec;
import org.apache.pinot.spi.data.FieldSpec;
import org.apache.pinot.spi.data.FieldSpec.DataType;
import org.apache.pinot.spi.data.FieldSpec.FieldType;
import org.apache.pinot.spi.data.Schema;
import org.apache.pinot.spi.data.readers.GenericRow;
import org.apache.pinot.spi.utils.TimeUtils;
import org.joda.time.DateTimeZone;
import org.joda.time.Interval;
import org.joda.time.format.DateTimeFormat;
import org.joda.time.format.DateTimeFormatter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.apache.pinot.core.segment.creator.impl.V1Constants.MetadataKeys.Column.*;
import static org.apache.pinot.core.segment.creator.impl.V1Constants.MetadataKeys.Segment.*;


/**
 * Segment creator which writes data in a columnar form.
 */
// TODO: check resource leaks
public class SegmentColumnarIndexCreator implements SegmentCreator {
  // TODO Refactor class name to match interface name
  private static final Logger LOGGER = LoggerFactory.getLogger(SegmentColumnarIndexCreator.class);
  private SegmentGeneratorConfig config;
  private Map<String, ColumnIndexCreationInfo> indexCreationInfoMap;
  private Map<String, SegmentDictionaryCreator> _dictionaryCreatorMap = new HashMap<>();
  private Map<String, ForwardIndexCreator> _forwardIndexCreatorMap = new HashMap<>();
  private Map<String, InvertedIndexCreator> _invertedIndexCreatorMap = new HashMap<>();
  private Map<String, InvertedIndexCreator> _textIndexCreatorMap = new HashMap<>();
  private Map<String, NullValueVectorCreator> _nullValueVectorCreatorMap = new HashMap<>();
  private String segmentName;
  private Schema schema;
  private File _indexDir;
  private int totalDocs;
  private int docIdCounter;
  private boolean _nullHandlingEnabled;
  private boolean _convertMapToArray;

  private final Set<String> _textIndexColumns = new HashSet<>();

  @Override
  public void init(SegmentGeneratorConfig segmentCreationSpec, SegmentIndexCreationInfo segmentIndexCreationInfo,
      Map<String, ColumnIndexCreationInfo> indexCreationInfoMap, Schema schema, File outDir)
      throws Exception {
    docIdCounter = 0;
    config = segmentCreationSpec;
    this.indexCreationInfoMap = indexCreationInfoMap;

    // Check that the output directory does not exist
    Preconditions.checkState(!outDir.exists(), "Segment output directory: %s already exists", outDir);

    Preconditions.checkState(outDir.mkdirs(), "Failed to create output directory: %s", outDir);
    _indexDir = outDir;

    this.schema = schema;
    this.totalDocs = segmentIndexCreationInfo.getTotalDocs();

    Collection<FieldSpec> fieldSpecs = schema.getAllFieldSpecs();
    Set<String> invertedIndexColumns = new HashSet<>();
    for (String columnName : config.getInvertedIndexCreationColumns()) {
      Preconditions.checkState(schema.hasColumn(columnName),
          "Cannot create inverted index for column: %s because it is not in schema", columnName);
      invertedIndexColumns.add(columnName);
    }

    for (String columnName : config.getTextIndexCreationColumns()) {
      Preconditions.checkState(schema.hasColumn(columnName),
          "Cannot create text index for column: %s because it is not in schema", columnName);
      _textIndexColumns.add(columnName);
    }

    // Initialize creators for dictionary, forward index and inverted index
    for (FieldSpec fieldSpec : fieldSpecs) {
      // Ignore virtual columns
      if (fieldSpec.isVirtualColumn()) {
        continue;
      }

      String columnName = fieldSpec.getName();
      ColumnIndexCreationInfo indexCreationInfo = indexCreationInfoMap.get(columnName);
      Preconditions.checkNotNull(indexCreationInfo, "Missing index creation info for column: %s", columnName);

      if (createDictionaryForColumn(indexCreationInfo, segmentCreationSpec, fieldSpec)) {
        // Create dictionary-encoded index

        // Initialize dictionary creator
        SegmentDictionaryCreator dictionaryCreator =
            new SegmentDictionaryCreator(indexCreationInfo.getSortedUniqueElementsArray(), fieldSpec, _indexDir,
                indexCreationInfo.isUseVarLengthDictionary());
        _dictionaryCreatorMap.put(columnName, dictionaryCreator);

        // Create dictionary
        try {
          dictionaryCreator.build();
        } catch (Exception e) {
          LOGGER.error("Error building dictionary for field: {}, cardinality: {}, number of bytes per entry: {}",
              fieldSpec.getName(), indexCreationInfo.getDistinctValueCount(), dictionaryCreator.getNumBytesPerEntry());
          throw e;
        }

        // Initialize forward index creator
        int cardinality = indexCreationInfo.getDistinctValueCount();
        if (fieldSpec.isSingleValueField()) {
          if (indexCreationInfo.isSorted()) {
            _forwardIndexCreatorMap
                .put(columnName, new SingleValueSortedForwardIndexCreator(_indexDir, columnName, cardinality));
          } else {
            _forwardIndexCreatorMap.put(columnName,
                new SingleValueUnsortedForwardIndexCreator(_indexDir, columnName, cardinality, totalDocs));
          }
        } else {
          _forwardIndexCreatorMap.put(columnName,
              new MultiValueUnsortedForwardIndexCreator(_indexDir, columnName, cardinality, totalDocs,
                  indexCreationInfo.getTotalNumberOfEntries()));
        }

        // Initialize inverted index creator; skip creating inverted index if sorted
        if (invertedIndexColumns.contains(columnName) && !indexCreationInfo.isSorted()) {
          if (segmentCreationSpec.isOnHeap()) {
            _invertedIndexCreatorMap
                .put(columnName, new OnHeapBitmapInvertedIndexCreator(_indexDir, columnName, cardinality));
          } else {
            _invertedIndexCreatorMap.put(columnName,
                new OffHeapBitmapInvertedIndexCreator(_indexDir, fieldSpec, cardinality, totalDocs,
                    indexCreationInfo.getTotalNumberOfEntries()));
          }
        }
      } else {
        // Create raw index

        // TODO: add support to multi-value column and inverted index
        Preconditions.checkState(fieldSpec.isSingleValueField(), "Cannot create raw index for multi-value column: %s",
            columnName);
        Preconditions.checkState(!invertedIndexColumns.contains(columnName),
            "Cannot create inverted index for raw index column: %s", columnName);

        ChunkCompressorFactory.CompressionType compressionType =
            getColumnCompressionType(segmentCreationSpec, fieldSpec);

        // Initialize forward index creator
        boolean deriveNumDocsPerChunk =
            shouldDeriveNumDocsPerChunk(columnName, segmentCreationSpec.getColumnProperties());
        int writerVersion = rawIndexWriterVersion(columnName, segmentCreationSpec.getColumnProperties());
        _forwardIndexCreatorMap.put(columnName,
            getRawIndexCreatorForColumn(_indexDir, compressionType, columnName, fieldSpec.getDataType(), totalDocs,
                indexCreationInfo.getLengthOfLongestEntry(), deriveNumDocsPerChunk, writerVersion));
      }

      if (_textIndexColumns.contains(columnName)) {
        // Initialize text index creator
        Preconditions.checkState(fieldSpec.isSingleValueField(),
            "Text index is currently only supported on single-value columns");
        Preconditions.checkState(fieldSpec.getDataType() == DataType.STRING,
            "Text index is currently only supported on STRING type columns");
        _textIndexCreatorMap
            .put(columnName, new LuceneTextIndexCreator(columnName, _indexDir, true /* commitOnClose */));
      }

      _nullHandlingEnabled = config.isNullHandlingEnabled();
      if (_nullHandlingEnabled) {
        // Initialize Null value vector map
        _nullValueVectorCreatorMap.put(columnName, new NullValueVectorCreator(_indexDir, columnName));
      }
      _convertMapToArray = false;
    }
  }

  public static boolean shouldDeriveNumDocsPerChunk(String columnName,
      Map<String, Map<String, String>> columnProperties) {
    if (columnProperties != null) {
      Map<String, String> properties = columnProperties.get(columnName);
      return properties != null && Boolean
          .parseBoolean(properties.get(FieldConfig.DERIVE_NUM_DOCS_PER_CHUNK_RAW_INDEX_KEY));
    }
    return false;
  }

  public static int rawIndexWriterVersion(String columnName, Map<String, Map<String, String>> columnProperties) {
    if (columnProperties != null && columnProperties.get(columnName) != null) {
      Map<String, String> properties = columnProperties.get(columnName);
      String version = properties.get(FieldConfig.RAW_INDEX_WRITER_VERSION);
      if (version == null) {
        return BaseChunkSVForwardIndexWriter.DEFAULT_VERSION;
      }
      return Integer.parseInt(version);
    }
    return BaseChunkSVForwardIndexWriter.DEFAULT_VERSION;
  }

  /**
   * Helper method that returns compression type to use based on segment creation spec and field type.
   * <ul>
   *   <li> Returns compression type from segment creation spec, if specified there.</li>
   *   <li> Else, returns PASS_THROUGH for metrics, and SNAPPY for dimensions. This is because metrics are likely
   *        to be spread in different chunks after applying predicates. Same could be true for dimensions, but in that
   *        case, clients are expected to explicitly specify the appropriate compression type in the spec. </li>
   * </ul>
   * @param segmentCreationSpec Segment creation spec
   * @param fieldSpec Field spec for the column
   * @return Compression type to use
   */
  private ChunkCompressorFactory.CompressionType getColumnCompressionType(SegmentGeneratorConfig segmentCreationSpec,
      FieldSpec fieldSpec) {
    ChunkCompressorFactory.CompressionType compressionType =
        segmentCreationSpec.getRawIndexCompressionType().get(fieldSpec.getName());

    if (compressionType == null) {
      if (fieldSpec.getFieldType().equals(FieldType.METRIC)) {
        return ChunkCompressorFactory.CompressionType.PASS_THROUGH;
      } else {
        return ChunkCompressorFactory.CompressionType.SNAPPY;
      }
    } else {
      return compressionType;
    }
  }

  /**
   * Returns true if dictionary should be created for a column, false otherwise.
   * Currently there are two sources for this config:
   * <ul>
   *   <li> ColumnIndexCreationInfo (this is currently hard-coded to always return dictionary). </li>
   *   <li> SegmentGeneratorConfig</li>
   * </ul>
   *
   * This method gives preference to the SegmentGeneratorConfig first.
   *
   * @param info Column index creation info
   * @param config Segment generation config
   * @param spec Field spec for the column
   * @return True if dictionary should be created for the column, false otherwise
   */
  private boolean createDictionaryForColumn(ColumnIndexCreationInfo info, SegmentGeneratorConfig config,
      FieldSpec spec) {
    String column = spec.getName();

    if (config.getRawIndexCreationColumns().contains(column) || config.getRawIndexCompressionType()
        .containsKey(column)) {
      if (!spec.isSingleValueField()) {
        throw new RuntimeException(
            "Creation of indices without dictionaries is supported for single valued columns only.");
      }
      return false;
    } else if (spec.getDataType().equals(DataType.BYTES) && !info.isFixedLength()) {
      return false;
    }
    return info.isCreateDictionary();
  }

  @Override
  public void indexRow(GenericRow row) {
    _convertMapToArray |= Boolean.parseBoolean((String) row.getValue(GenericRow.CONVERT_MAP_VALUE_TO_ARRAY_VALUE_KEY));
    for (Map.Entry<String, ForwardIndexCreator> entry : _forwardIndexCreatorMap.entrySet()) {
      String columnName = entry.getKey();
      ForwardIndexCreator forwardIndexCreator = entry.getValue();

      Object columnValueToIndex = row.getValue(columnName);
      if (columnValueToIndex == null) {
        throw new RuntimeException("Null value for column:" + columnName);
      }

      boolean isSingleValue = schema.getFieldSpecFor(columnName).isSingleValueField();
      SegmentDictionaryCreator dictionaryCreator = _dictionaryCreatorMap.get(columnName);

      if (isSingleValue) {
        // SV column
        if (dictionaryCreator != null) {
          // dictionary encoded SV column
          // get dictID from dictionary
          int dictId = dictionaryCreator.indexOfSV(columnValueToIndex);
          // store the docID -> dictID mapping in forward index
          forwardIndexCreator.putDictId(dictId);
          InvertedIndexCreator invertedIndexCreator = _invertedIndexCreatorMap.get(columnName);
          if (invertedIndexCreator != null) {
            // if inverted index enabled during segment creation,
            // then store dictID -> docID mapping in inverted index
            invertedIndexCreator.add(dictId);
          }
        } else {
          // non-dictionary encoded SV column
          // store the docId -> raw value mapping in forward index
          switch (forwardIndexCreator.getValueType()) {
            case INT:
              forwardIndexCreator.putInt((int) columnValueToIndex);
              break;
            case LONG:
              forwardIndexCreator.putLong((long) columnValueToIndex);
              break;
            case FLOAT:
              forwardIndexCreator.putFloat((float) columnValueToIndex);
              break;
            case DOUBLE:
              forwardIndexCreator.putDouble((double) columnValueToIndex);
              break;
            case STRING:
              forwardIndexCreator.putString((String) columnValueToIndex);
              break;
            case BYTES:
              forwardIndexCreator.putBytes((byte[]) columnValueToIndex);
              break;
            default:
              throw new IllegalStateException();
          }
        }
        // text-index enabled SV column
        if (_textIndexColumns.contains(columnName)) {
          InvertedIndexCreator textInvertedIndexCreator = _textIndexCreatorMap.get(columnName);
          // add the column value to lucene index
          textInvertedIndexCreator.addDoc(columnValueToIndex, docIdCounter);
        }
      } else {
        // MV column (always dictionary encoded)
        int[] dictIds = dictionaryCreator.indexOfMV(columnValueToIndex);
        forwardIndexCreator.putDictIdMV(dictIds);
        InvertedIndexCreator invertedIndexCreator = _invertedIndexCreatorMap.get(columnName);
        if (invertedIndexCreator != null) {
          invertedIndexCreator.add(dictIds, dictIds.length);
        }
      }

      if (_nullHandlingEnabled) {
        // If row has null value for given column name, add to null value vector
        if (row.isNullValue(columnName)) {
          _nullValueVectorCreatorMap.get(columnName).setNull(docIdCounter);
        }
      }
    }
    docIdCounter++;
  }

  @Override
  public void setSegmentName(String segmentName) {
    this.segmentName = segmentName;
  }

  @Override
  public void seal()
      throws ConfigurationException, IOException {
    for (InvertedIndexCreator invertedIndexCreator : _invertedIndexCreatorMap.values()) {
      invertedIndexCreator.seal();
    }
    for (NullValueVectorCreator nullValueVectorCreator : _nullValueVectorCreatorMap.values()) {
      nullValueVectorCreator.seal();
    }
    writeMetadata();
  }

  private void writeMetadata()
      throws ConfigurationException {
    PropertiesConfiguration properties =
        new PropertiesConfiguration(new File(_indexDir, V1Constants.MetadataKeys.METADATA_FILE_NAME));

    properties.setProperty(SEGMENT_CREATOR_VERSION, config.getCreatorVersion());
    properties.setProperty(SEGMENT_PADDING_CHARACTER, String.valueOf(V1Constants.Str.DEFAULT_STRING_PAD_CHAR));
    properties.setProperty(SEGMENT_NAME, segmentName);
    properties.setProperty(TABLE_NAME, config.getTableName());
    properties.setProperty(DIMENSIONS, config.getDimensions());
    properties.setProperty(METRICS, config.getMetrics());
    properties.setProperty(DATETIME_COLUMNS, config.getDateTimeColumnNames());
    String timeColumnName = config.getTimeColumnName();
    properties.setProperty(TIME_COLUMN_NAME, timeColumnName);
    properties.setProperty(SEGMENT_TOTAL_DOCS, String.valueOf(totalDocs));
    properties.setProperty(SEGMENT_CONVERT_MAP_VALUE_TO_ARRAY_VALUE, _convertMapToArray);

    // Write time related metadata (start time, end time, time unit)
    if (timeColumnName != null) {
      ColumnIndexCreationInfo timeColumnIndexCreationInfo = indexCreationInfoMap.get(timeColumnName);
      if (timeColumnIndexCreationInfo != null) {
        long startTime;
        long endTime;
        TimeUnit timeUnit;

        // Use start/end time in config if defined
        if (config.getStartTime() != null) {
          startTime = Long.parseLong(config.getStartTime());
          endTime = Long.parseLong(config.getEndTime());
          timeUnit = Preconditions.checkNotNull(config.getSegmentTimeUnit());
        } else {
          String startTimeStr = timeColumnIndexCreationInfo.getMin().toString();
          String endTimeStr = timeColumnIndexCreationInfo.getMax().toString();

          if (config.getTimeColumnType() == SegmentGeneratorConfig.TimeColumnType.SIMPLE_DATE) {
            // For TimeColumnType.SIMPLE_DATE_FORMAT, convert time value into millis since epoch
            DateTimeFormatter dateTimeFormatter = DateTimeFormat.forPattern(config.getSimpleDateFormat());
            startTime = dateTimeFormatter.parseMillis(startTimeStr);
            endTime = dateTimeFormatter.parseMillis(endTimeStr);
            timeUnit = TimeUnit.MILLISECONDS;
          } else {
            // by default, time column type is TimeColumnType.EPOCH
            startTime = Long.parseLong(startTimeStr);
            endTime = Long.parseLong(endTimeStr);
            timeUnit = Preconditions.checkNotNull(config.getSegmentTimeUnit());
          }
        }

        if (!config.isSkipTimeValueCheck()) {
          Interval timeInterval =
              new Interval(timeUnit.toMillis(startTime), timeUnit.toMillis(endTime), DateTimeZone.UTC);
          Preconditions.checkState(TimeUtils.isValidTimeInterval(timeInterval),
              "Invalid segment start/end time: %s (in millis: %s/%s) for time column: %s, must be between: %s",
              timeInterval, timeInterval.getStartMillis(), timeInterval.getEndMillis(), timeColumnName,
              TimeUtils.VALID_TIME_INTERVAL);
        }

        properties.setProperty(SEGMENT_START_TIME, startTime);
        properties.setProperty(SEGMENT_END_TIME, endTime);
        properties.setProperty(TIME_UNIT, timeUnit);
      }
    }

    for (Map.Entry<String, String> entry : config.getCustomProperties().entrySet()) {
      properties.setProperty(entry.getKey(), entry.getValue());
    }

    for (Map.Entry<String, ColumnIndexCreationInfo> entry : indexCreationInfoMap.entrySet()) {
      String column = entry.getKey();
      ColumnIndexCreationInfo columnIndexCreationInfo = entry.getValue();
      SegmentDictionaryCreator dictionaryCreator = _dictionaryCreatorMap.get(column);
      int dictionaryElementSize = (dictionaryCreator != null) ? dictionaryCreator.getNumBytesPerEntry() : 0;

      // TODO: after fixing the server-side dependency on HAS_INVERTED_INDEX and deployed, set HAS_INVERTED_INDEX properly
      // The hasInvertedIndex flag in segment metadata is picked up in ColumnMetadata, and will be used during the query
      // plan phase. If it is set to false, then inverted indexes are not used in queries even if they are created via table
      // configs on segment load. So, we set it to true here for now, until we fix the server to update the value inside
      // ColumnMetadata, export information to the query planner that the inverted index available is current and can be used.
      //
      //    boolean hasInvertedIndex = invertedIndexCreatorMap.containsKey();
      boolean hasInvertedIndex = true;

      boolean hasTextIndex = _textIndexColumns.contains(column);
      // for new generated segment we write as NONE if text index does not exist
      // for reading existing segments that don't have this property, non-existence
      // of this property will be treated as NONE. See the builder in ColumnMetadata
      TextIndexType textIndexType = hasTextIndex ? TextIndexType.LUCENE : TextIndexType.NONE;

      addColumnMetadataInfo(properties, column, columnIndexCreationInfo, totalDocs, schema.getFieldSpecFor(column),
          _dictionaryCreatorMap.containsKey(column), dictionaryElementSize, hasInvertedIndex, textIndexType);
    }

    properties.save();
  }

  public static void addColumnMetadataInfo(PropertiesConfiguration properties, String column,
      ColumnIndexCreationInfo columnIndexCreationInfo, int totalDocs, FieldSpec fieldSpec, boolean hasDictionary,
      int dictionaryElementSize, boolean hasInvertedIndex, TextIndexType textIndexType) {
    int cardinality = columnIndexCreationInfo.getDistinctValueCount();
    properties.setProperty(getKeyFor(column, CARDINALITY), String.valueOf(cardinality));
    properties.setProperty(getKeyFor(column, TOTAL_DOCS), String.valueOf(totalDocs));
    DataType dataType = fieldSpec.getDataType();
    properties.setProperty(getKeyFor(column, DATA_TYPE), String.valueOf(dataType));
    properties.setProperty(getKeyFor(column, BITS_PER_ELEMENT),
        String.valueOf(PinotDataBitSet.getNumBitsPerValue(cardinality - 1)));
    properties.setProperty(getKeyFor(column, DICTIONARY_ELEMENT_SIZE), String.valueOf(dictionaryElementSize));
    properties.setProperty(getKeyFor(column, COLUMN_TYPE), String.valueOf(fieldSpec.getFieldType()));
    properties.setProperty(getKeyFor(column, IS_SORTED), String.valueOf(columnIndexCreationInfo.isSorted()));
    properties.setProperty(getKeyFor(column, HAS_NULL_VALUE), String.valueOf(columnIndexCreationInfo.hasNulls()));
    properties.setProperty(getKeyFor(column, HAS_DICTIONARY), String.valueOf(hasDictionary));
    properties.setProperty(getKeyFor(column, TEXT_INDEX_TYPE), textIndexType.name());
    properties.setProperty(getKeyFor(column, HAS_INVERTED_INDEX), String.valueOf(hasInvertedIndex));
    properties.setProperty(getKeyFor(column, IS_SINGLE_VALUED), String.valueOf(fieldSpec.isSingleValueField()));
    properties.setProperty(getKeyFor(column, MAX_MULTI_VALUE_ELEMTS),
        String.valueOf(columnIndexCreationInfo.getMaxNumberOfMultiValueElements()));
    properties.setProperty(getKeyFor(column, TOTAL_NUMBER_OF_ENTRIES),
        String.valueOf(columnIndexCreationInfo.getTotalNumberOfEntries()));
    properties
        .setProperty(getKeyFor(column, IS_AUTO_GENERATED), String.valueOf(columnIndexCreationInfo.isAutoGenerated()));

    PartitionFunction partitionFunction = columnIndexCreationInfo.getPartitionFunction();
    if (partitionFunction != null) {
      properties.setProperty(getKeyFor(column, PARTITION_FUNCTION), partitionFunction.toString());
      properties.setProperty(getKeyFor(column, NUM_PARTITIONS), columnIndexCreationInfo.getNumPartitions());
      properties.setProperty(getKeyFor(column, PARTITION_VALUES), columnIndexCreationInfo.getPartitions());
    }

    // datetime field
    if (fieldSpec.getFieldType().equals(FieldType.DATE_TIME)) {
      DateTimeFieldSpec dateTimeFieldSpec = (DateTimeFieldSpec) fieldSpec;
      properties.setProperty(getKeyFor(column, DATETIME_FORMAT), dateTimeFieldSpec.getFormat());
      properties.setProperty(getKeyFor(column, DATETIME_GRANULARITY), dateTimeFieldSpec.getGranularity());
    }

    String minValue = columnIndexCreationInfo.getMin().toString();
    String maxValue = columnIndexCreationInfo.getMax().toString();
    String defaultNullValue = columnIndexCreationInfo.getDefaultNullValue().toString();
    if (dataType == DataType.STRING) {
      // Check special characters for STRING column
      if (isValidPropertyValue(minValue)) {
        properties.setProperty(getKeyFor(column, MIN_VALUE), minValue);
      }
      if (isValidPropertyValue(maxValue)) {
        properties.setProperty(getKeyFor(column, MAX_VALUE), maxValue);
      }
      if (isValidPropertyValue(defaultNullValue)) {
        properties.setProperty(getKeyFor(column, DEFAULT_NULL_VALUE), defaultNullValue);
      }
    } else {
      properties.setProperty(getKeyFor(column, MIN_VALUE), minValue);
      properties.setProperty(getKeyFor(column, MAX_VALUE), maxValue);
      properties.setProperty(getKeyFor(column, DEFAULT_NULL_VALUE), defaultNullValue);
    }
  }

  public static void addColumnMinMaxValueInfo(PropertiesConfiguration properties, String column, String minValue,
      String maxValue) {
    // Check special characters for STRING column
    if (isValidPropertyValue(minValue)) {
      properties.setProperty(getKeyFor(column, MIN_VALUE), minValue);
    }
    if (isValidPropertyValue(maxValue)) {
      properties.setProperty(getKeyFor(column, MAX_VALUE), maxValue);
    }
  }

  /**
   * Helper method to check whether the given value is a valid property value.
   * <p>Value is invalid iff:
   * <ul>
   *   <li>It contains leading/trailing whitespace</li>
   *   <li>It contains list separator (',')</li>
   * </ul>
   */
  @VisibleForTesting
  static boolean isValidPropertyValue(String value) {
    int length = value.length();
    if (length == 0) {
      return true;
    }
    if (Character.isWhitespace(value.charAt(0)) || Character.isWhitespace(value.charAt(length - 1))) {
      return false;
    }
    return value.indexOf(',') == -1;
  }

  public static void removeColumnMetadataInfo(PropertiesConfiguration properties, String column) {
    properties.subset(COLUMN_PROPS_KEY_PREFIX + column).clear();
  }

  /**
   * Helper method to build the raw index creator for the column.
   * Assumes that column to be indexed is single valued.
   *
   * @param file Output index file
   * @param column Column name
   * @param totalDocs Total number of documents to index
   * @param lengthOfLongestEntry Length of longest entry
   * @param deriveNumDocsPerChunk true if varbyte writer should auto-derive the number of rows per chunk
   * @param writerVersion version to use for the raw index writer
   * @return raw index creator
   * @throws IOException
   */
  public static ForwardIndexCreator getRawIndexCreatorForColumn(File file,
      ChunkCompressorFactory.CompressionType compressionType, String column, DataType dataType, int totalDocs,
      int lengthOfLongestEntry, boolean deriveNumDocsPerChunk, int writerVersion)
      throws IOException {
    switch (dataType) {
      case INT:
      case LONG:
      case FLOAT:
      case DOUBLE:
        return new SingleValueFixedByteRawIndexCreator(file, compressionType, column, totalDocs, dataType,
            writerVersion);
      case STRING:
      case BYTES:
        return new SingleValueVarByteRawIndexCreator(file, compressionType, column, totalDocs, dataType,
            lengthOfLongestEntry, deriveNumDocsPerChunk, writerVersion);
      default:
        throw new UnsupportedOperationException("Data type not supported for raw indexing: " + dataType);
    }
  }

  @Override
  public void close()
      throws IOException {
    FileUtils.close(Iterables
        .concat(_dictionaryCreatorMap.values(), _forwardIndexCreatorMap.values(), _invertedIndexCreatorMap.values(),
            _nullValueVectorCreatorMap.values(), _textIndexCreatorMap.values()));
  }
}
