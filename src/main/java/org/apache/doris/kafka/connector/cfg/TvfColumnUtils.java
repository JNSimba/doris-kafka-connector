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

package org.apache.doris.kafka.connector.cfg;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import org.apache.doris.kafka.connector.writer.LoadConstants;

/** Resolves the fixed physical columns used by TVF write mode. */
public final class TvfColumnUtils {
    private static final String COLUMNS_OPTION = "sink.properties.columns";
    private static final Pattern UNQUOTED_IDENTIFIER = Pattern.compile("[A-Za-z_][A-Za-z0-9_$]*");

    private TvfColumnUtils() {}

    public static List<String> resolveColumns(String configuredColumns) {
        if (configuredColumns == null || configuredColumns.trim().isEmpty()) {
            throw new IllegalArgumentException(COLUMNS_OPTION + " must not be empty");
        }

        List<String> columns = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (String rawColumn : configuredColumns.split(",", -1)) {
            String value = rawColumn.trim();
            if (value.isEmpty()) {
                throw new IllegalArgumentException(COLUMNS_OPTION + " contains an empty column");
            }
            String column = resolveIdentifier(value);
            if (LoadConstants.DORIS_DELETE_SIGN.equalsIgnoreCase(column)) {
                throw new IllegalArgumentException(
                        COLUMNS_OPTION + " must not contain " + LoadConstants.DORIS_DELETE_SIGN);
            }
            if (!seen.add(column.toLowerCase(Locale.ROOT))) {
                throw new IllegalArgumentException(
                        COLUMNS_OPTION + " contains duplicate column: " + column);
            }
            columns.add(column);
        }
        return Collections.unmodifiableList(columns);
    }

    private static String resolveIdentifier(String value) {
        if (value.length() >= 2 && value.startsWith("`") && value.endsWith("`")) {
            String identifier = value.substring(1, value.length() - 1).replace("``", "`");
            if (!identifier.isEmpty()) {
                return identifier;
            }
        } else if (UNQUOTED_IDENTIFIER.matcher(value).matches()) {
            return value;
        }
        throw new IllegalArgumentException(
                COLUMNS_OPTION + " only supports fixed physical column identifiers: " + value);
    }
}
