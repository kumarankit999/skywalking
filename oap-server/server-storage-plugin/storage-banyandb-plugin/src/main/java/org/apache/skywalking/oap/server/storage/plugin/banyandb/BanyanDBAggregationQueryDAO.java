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
 *
 */

package org.apache.skywalking.oap.server.storage.plugin.banyandb;

import com.google.common.collect.ImmutableSet;
import org.apache.skywalking.banyandb.v1.client.DataPoint;
import org.apache.skywalking.banyandb.v1.client.MeasureQuery;
import org.apache.skywalking.banyandb.v1.client.MeasureQueryResponse;
import org.apache.skywalking.banyandb.v1.client.TimestampRange;
import org.apache.skywalking.banyandb.v1.client.TopNQueryResponse;
import org.apache.skywalking.oap.server.core.analysis.metrics.Metrics;
import org.apache.skywalking.oap.server.core.query.enumeration.Order;
import org.apache.skywalking.oap.server.core.query.input.Duration;
import org.apache.skywalking.oap.server.core.query.input.TopNCondition;
import org.apache.skywalking.oap.server.core.query.type.KeyValue;
import org.apache.skywalking.oap.server.core.query.type.SelectedRecord;
import org.apache.skywalking.oap.server.core.storage.query.IAggregationQueryDAO;
import org.apache.skywalking.oap.server.library.util.CollectionUtils;
import org.apache.skywalking.oap.server.storage.plugin.banyandb.stream.AbstractBanyanDBDAO;
import org.apache.skywalking.oap.server.storage.plugin.banyandb.util.ByteUtil;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

public class BanyanDBAggregationQueryDAO extends AbstractBanyanDBDAO implements IAggregationQueryDAO {
    private static final Set<String> TAGS = ImmutableSet.of(Metrics.ENTITY_ID);

    public BanyanDBAggregationQueryDAO(BanyanDBStorageClient client) {
        super(client);
    }

    @Override
    public List<SelectedRecord> sortMetrics(TopNCondition condition, String valueColumnName, Duration duration, List<KeyValue> additionalConditions) throws IOException {
        final String modelName = condition.getName();
        final TimestampRange timestampRange = new TimestampRange(duration.getStartTimestamp(), duration.getEndTimestamp());
        // fast-path: BanyanDB server-side TopN support
        MetadataRegistry.Schema schema = MetadataRegistry.INSTANCE.findMetadata(modelName, duration.getStep());
        if (schema == null) {
            throw new IOException("schema is not registered");
        }

        MetadataRegistry.ColumnSpec spec = schema.getSpec(valueColumnName);
        if (spec == null) {
            throw new IOException("field spec is not registered");
        }

        if (schema.hasTopNAggregation()) {
            TopNQueryResponse resp = null;
            if (condition.getOrder() == Order.DES) {
                resp = topN(schema, timestampRange, condition.getTopN());
            } else {
                resp = bottomN(schema, timestampRange, condition.getTopN());
            }

            if (resp.getTopNLists().isEmpty()) {
                return Collections.emptyList();
            } else if (resp.getTopNLists().size() > 1) { // since we have done aggregation, i.e. MEAN
                throw new IOException("invalid TopN response");
            }

            final List<SelectedRecord> topNList = new ArrayList<>();
            for (TopNQueryResponse.Item item : resp.getTopNLists().get(0).getItems()) {
                SelectedRecord record = new SelectedRecord();
                record.setId(item.getName());
                record.setValue(extractFieldValueAsString(spec, item.getValue()));
                topNList.add(record);
            }

            return topNList;
        }

        // slow-path: TopN using vanilla Measure query
        MeasureQueryResponse resp = query(modelName, TAGS, Collections.singleton(valueColumnName),
                timestampRange, new QueryBuilder<MeasureQuery>() {
                    @Override
                    protected void apply(MeasureQuery query) {
                        query.meanBy(valueColumnName, ImmutableSet.of(Metrics.ENTITY_ID));
                        if (condition.getOrder() == Order.DES) {
                            query.topN(condition.getTopN(), valueColumnName);
                        } else {
                            query.bottomN(condition.getTopN(), valueColumnName);
                        }
                        if (CollectionUtils.isNotEmpty(additionalConditions)) {
                            additionalConditions.forEach(additionalCondition -> query
                                    .and(eq(
                                            additionalCondition.getKey(),
                                            additionalCondition.getValue()
                                    )));
                        }
                    }
                });

        if (resp.size() == 0) {
            return Collections.emptyList();
        }

        final List<SelectedRecord> topNList = new ArrayList<>();
        for (DataPoint dataPoint : resp.getDataPoints()) {
            SelectedRecord record = new SelectedRecord();
            record.setId(dataPoint.getTagValue(Metrics.ENTITY_ID));
            record.setValue(extractFieldValueAsString(spec, valueColumnName, dataPoint));
            topNList.add(record);
        }

        return topNList;
    }

    private static String extractFieldValueAsString(MetadataRegistry.ColumnSpec spec, String fieldName, DataPoint dataPoint) throws IOException {
        return extractFieldValueAsString(spec, dataPoint.getFieldValue(fieldName));
    }

    private static String extractFieldValueAsString(MetadataRegistry.ColumnSpec spec, Object fieldValue) throws IOException {
        if (double.class.equals(spec.getColumnClass())) {
            return String.valueOf(ByteUtil.bytes2Double((byte[]) fieldValue).longValue());
        } else if (String.class.equals(spec.getColumnClass())) {
            return (String) fieldValue;
        } else {
            return String.valueOf(((Number) fieldValue).longValue());
        }
    }
}
