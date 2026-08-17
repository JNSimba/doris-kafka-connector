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

import java.util.Arrays;
import org.junit.Assert;
import org.junit.Test;

public class TvfColumnUtilsTest {

    @Test
    public void testResolveFixedPhysicalColumns() {
        Assert.assertEquals(
                Arrays.asList("id", "order name", "create_time"),
                TvfColumnUtils.resolveColumns("id,`order name`,create_time"));
    }

    @Test(expected = IllegalArgumentException.class)
    public void testRejectMissingColumns() {
        TvfColumnUtils.resolveColumns(null);
    }

    @Test(expected = IllegalArgumentException.class)
    public void testRejectEmptyColumn() {
        TvfColumnUtils.resolveColumns("id,,name");
    }

    @Test(expected = IllegalArgumentException.class)
    public void testRejectDuplicateColumn() {
        TvfColumnUtils.resolveColumns("id,id");
    }

    @Test(expected = IllegalArgumentException.class)
    public void testRejectStreamLoadColumnExpression() {
        TvfColumnUtils.resolveColumns("id,total=price*quantity");
    }
}
