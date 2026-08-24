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

import java.util.Arrays;
import org.apache.doris.kafka.connector.exception.DataFormatException;
import org.junit.Assert;
import org.junit.Test;

public class S3TvfRecordSerializerTest {

    @Test
    public void testSelectsColumnsAndNormalizesDeleteSign() {
        S3TvfRecordSerializer serializer =
                new S3TvfRecordSerializer(Arrays.asList("id", "name"), true);

        String serialized =
                serializer.serialize(
                        "{\"extra\":9,\"name\":null,\"id\":7,\"__DORIS_DELETE_SIGN__\":\"1\"}");

        Assert.assertEquals("{\"id\":7,\"name\":null,\"__doris_delete_sign__\":\"1\"}", serialized);
    }

    @Test
    public void testFillsMissingColumnsAndDefaultsDeleteSign() {
        S3TvfRecordSerializer serializer =
                new S3TvfRecordSerializer(Arrays.asList("id", "name"), true);

        Assert.assertEquals(
                "{\"id\":7,\"name\":null,\"__doris_delete_sign__\":\"0\"}",
                serializer.serialize("{\"id\":7}"));
    }

    @Test(expected = DataFormatException.class)
    public void testRejectsMultipleJsonObjects() {
        new S3TvfRecordSerializer(Arrays.asList("id", "name"), false)
                .serialize("{\"id\":1}\n{\"name\":\"doris\",\"id\":2}");
    }

    @Test(expected = DataFormatException.class)
    public void testRejectsNonObjectJsonLine() {
        new S3TvfRecordSerializer(Arrays.asList("id"), false).serialize("[1,2]");
    }
}
