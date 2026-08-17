/*
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

package org.apache.doris.kafka.connector.writer.s3;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.apache.doris.kafka.connector.exception.DataFormatException;
import org.apache.doris.kafka.connector.writer.LoadConstants;

/** Normalizes processed records to the fixed JSON Lines schema used by S3 TVF. */
public class S3TvfRecordSerializer {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final ObjectReader OBJECT_READER =
            OBJECT_MAPPER.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private static final String TVF_DELETE_SIGN = "__doris_delete_sign__";

    private final List<String> columns;
    private final boolean deleteSignEnabled;

    public S3TvfRecordSerializer(List<String> columns, boolean deleteSignEnabled) {
        this.columns = columns;
        this.deleteSignEnabled = deleteSignEnabled;
    }

    public String serialize(String processedRecord) {
        return serializeRecord(processedRecord);
    }

    private String serializeRecord(String processedRecord) {
        try {
            JsonNode root = OBJECT_READER.readTree(processedRecord);
            if (root == null || !root.isObject()) {
                throw new DataFormatException("S3 TVF records must be JSON objects");
            }
            Map<String, JsonNode> normalized = new LinkedHashMap<>();
            for (String column : columns) {
                normalized.put(column, root.get(column));
            }
            if (deleteSignEnabled) {
                JsonNode deleteSign = root.get(LoadConstants.DORIS_DELETE_SIGN);
                if (deleteSign == null) {
                    deleteSign = root.get(TVF_DELETE_SIGN);
                }
                normalized.put(
                        TVF_DELETE_SIGN,
                        deleteSign == null
                                ? OBJECT_MAPPER
                                        .getNodeFactory()
                                        .textNode(LoadConstants.DORIS_DEL_FALSE)
                                : deleteSign);
            }
            return OBJECT_MAPPER.writeValueAsString(normalized);
        } catch (DataFormatException e) {
            throw e;
        } catch (Exception e) {
            throw new DataFormatException("Failed to normalize an S3 TVF record", e);
        }
    }
}
