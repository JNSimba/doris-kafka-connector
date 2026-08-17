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

package org.apache.doris.kafka.connector.writer;

import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.apache.doris.kafka.connector.cfg.DorisOptions;
import org.apache.doris.kafka.connector.cfg.DorisSinkConnectorConfig;
import org.apache.doris.kafka.connector.connection.ConnectionProvider;
import org.apache.doris.kafka.connector.converter.RecordService;
import org.apache.doris.kafka.connector.exception.DorisException;
import org.apache.doris.kafka.connector.metrics.DorisConnectMonitor;
import org.apache.doris.kafka.connector.service.DorisSystemService;
import org.apache.doris.kafka.connector.writer.s3.S3ObjectStore;
import org.apache.doris.kafka.connector.writer.s3.S3TvfLoad;
import org.apache.kafka.connect.sink.SinkRecord;
import org.junit.Assert;
import org.junit.Test;
import org.mockito.ArgumentCaptor;

public class AsyncS3TvfWriterTest {
    private static final String BATCH_UUID = "550e8400-e29b-41d4-a716-446655440000";
    private static final String COMPACT_UUID = "550e8400e29b41d4a716446655440000";

    @Test
    public void testUsesTaskIdAndOneBatchLabelForAllFiles() throws Exception {
        DorisOptions options = options(1024, 1);
        RecordingObjectStore store = new RecordingObjectStore();
        S3TvfLoad load = mock(S3TvfLoad.class);
        RecordService records = mock(RecordService.class);
        SinkRecord first = TestRecordBuffer.newSinkRecord("ignored", 1);
        SinkRecord second = TestRecordBuffer.newSinkRecord("ignored", 2);
        when(records.getProcessedRecord(first)).thenReturn("{\"id\":1,\"name\":\"first\"}");
        when(records.getProcessedRecord(second)).thenReturn("{\"id\":2,\"name\":\"second\"}");

        AsyncS3TvfWriter writer = writer(options, store, load, records);
        writer.insert(first);
        writer.insert(second);
        writer.commitFlush();

        String label = "tvf_demo_orders_" + COMPACT_UUID;
        String directory = "objects/tvf/demo_orders/" + COMPACT_UUID + "/";
        Assert.assertEquals(2, store.objects.size());
        Assert.assertTrue(store.objects.containsKey(directory + label + "_7_0.json"));
        Assert.assertTrue(store.objects.containsKey(directory + label + "_7_1.json"));

        ArgumentCaptor<List> objectKeys = ArgumentCaptor.forClass(List.class);
        verify(load).load(org.mockito.ArgumentMatchers.eq(label), objectKeys.capture());
        Assert.assertEquals(
                Arrays.asList(directory + label + "_7_0.json", directory + label + "_7_1.json"),
                objectKeys.getValue());
        writer.close();
    }

    @Test
    public void testCommitFlushWritesResidualBufferAndNormalizesRows() throws Exception {
        DorisOptions options = options(1024, 100);
        RecordingObjectStore store = new RecordingObjectStore();
        S3TvfLoad load = mock(S3TvfLoad.class);
        RecordService records = mock(RecordService.class);
        SinkRecord record = TestRecordBuffer.newSinkRecord("ignored", 1);
        when(records.getProcessedRecord(record)).thenReturn("{\"id\":1,\"extra\":\"drop\"}");

        AsyncS3TvfWriter writer = writer(options, store, load, records);
        writer.insert(record);
        Assert.assertTrue(store.objects.isEmpty());

        writer.commitFlush();

        Assert.assertEquals(1, store.objects.size());
        String content =
                new String(store.objects.values().iterator().next(), StandardCharsets.UTF_8);
        Assert.assertEquals("{\"id\":1,\"name\":null}\n", content);
        verify(load).load(anyString(), anyList());
        writer.close();
    }

    @Test
    public void testSizeThresholdSubmitsFileBeforeCommit() throws Exception {
        DorisOptions options = options(10, 100);
        BlockingObjectStore store = new BlockingObjectStore();
        S3TvfLoad load = mock(S3TvfLoad.class);
        RecordService records = mock(RecordService.class);
        SinkRecord record = TestRecordBuffer.newSinkRecord("ignored", 1);
        when(records.getProcessedRecord(record)).thenReturn("{\"id\":1,\"name\":\"first\"}");
        AsyncS3TvfWriter writer = writer(options, store, load, records);

        try {
            writer.insert(record);

            Assert.assertTrue(store.uploadStarted.await(5, TimeUnit.SECONDS));
            Assert.assertTrue(store.objects.isEmpty());
            verify(load, never()).load(anyString(), anyList());

            store.continueUpload.countDown();
            writer.commitFlush();

            Assert.assertEquals(1, store.objects.size());
            verify(load).load(anyString(), anyList());
        } finally {
            store.continueUpload.countDown();
            writer.close();
        }
    }

    @Test
    public void testSizeThresholdFlushesAfterAppendingWholeRecord() throws Exception {
        DorisOptions options = options(30, 100);
        RecordingObjectStore store = new RecordingObjectStore();
        S3TvfLoad load = mock(S3TvfLoad.class);
        RecordService records = mock(RecordService.class);
        SinkRecord first = TestRecordBuffer.newSinkRecord("ignored", 1);
        SinkRecord second = TestRecordBuffer.newSinkRecord("ignored", 2);
        when(records.getProcessedRecord(first)).thenReturn("{\"id\":1,\"name\":\"a\"}");
        when(records.getProcessedRecord(second)).thenReturn("{\"id\":2,\"name\":\"b\"}");
        AsyncS3TvfWriter writer = writer(options, store, load, records);

        try {
            writer.insert(first);
            writer.insert(second);
            writer.commitFlush();

            Assert.assertEquals(1, store.objects.size());
            Assert.assertEquals(
                    "{\"id\":1,\"name\":\"a\"}\n{\"id\":2,\"name\":\"b\"}\n",
                    new String(store.objects.values().iterator().next(), StandardCharsets.UTF_8));
        } finally {
            writer.close();
        }
    }

    @Test
    public void testEmptyCommitDoesNotCreateFileOrLoad() throws Exception {
        RecordingObjectStore store = new RecordingObjectStore();
        S3TvfLoad load = mock(S3TvfLoad.class);
        AsyncS3TvfWriter writer =
                writer(options(1024, 100), store, load, mock(RecordService.class));

        writer.commitFlush();

        Assert.assertTrue(store.objects.isEmpty());
        verify(load, never()).load(anyString(), anyList());
        writer.close();
    }

    @Test(expected = DorisException.class)
    public void testUploadFailurePreventsLoad() throws Exception {
        RecordingObjectStore store = new RecordingObjectStore();
        store.putFailure = new IOException("upload failed");
        S3TvfLoad load = mock(S3TvfLoad.class);
        RecordService records = mock(RecordService.class);
        SinkRecord record = TestRecordBuffer.newSinkRecord("ignored", 1);
        when(records.getProcessedRecord(record)).thenReturn("{\"id\":1,\"name\":\"first\"}");
        AsyncS3TvfWriter writer = writer(options(1024, 100), store, load, records);
        try {
            writer.insert(record);
            writer.commitFlush();
        } finally {
            verify(load, never()).load(anyString(), anyList());
            writer.close();
        }
    }

    @Test
    public void testUploadFailureCanBeResetForRetry() throws Exception {
        RecordingObjectStore store = new RecordingObjectStore();
        store.putFailure = new IOException("upload failed");
        S3TvfLoad load = mock(S3TvfLoad.class);
        RecordService records = mock(RecordService.class);
        SinkRecord record = TestRecordBuffer.newSinkRecord("ignored", 1);
        when(records.getProcessedRecord(record)).thenReturn("{\"id\":1,\"name\":\"first\"}");
        AsyncS3TvfWriter writer = writer(options(1024, 100), store, load, records);
        try {
            writer.insert(record);
            try {
                writer.commitFlush();
                Assert.fail("Expected upload to fail");
            } catch (DorisException expected) {
                // Reset the failed batch before Kafka Connect retries the same records.
            }

            writer.resetAfterUploadFailure();
            store.putFailure = null;
            writer.insert(record);
            writer.commitFlush();

            Assert.assertEquals(1, store.objects.size());
            verify(load).load(anyString(), anyList());
        } finally {
            writer.close();
        }
    }

    @Test
    public void testCommittedObjectsRemainStaged() throws Exception {
        RecordingObjectStore store = new RecordingObjectStore();
        S3TvfLoad load = mock(S3TvfLoad.class);
        RecordService records = mock(RecordService.class);
        SinkRecord record = TestRecordBuffer.newSinkRecord("ignored", 1);
        when(records.getProcessedRecord(record)).thenReturn("{\"id\":1,\"name\":\"first\"}");
        AsyncS3TvfWriter writer = writer(options(1024, 100), store, load, records);

        writer.insert(record);
        writer.commitFlush();

        verify(load).load(anyString(), anyList());
        Assert.assertEquals(1, store.objects.size());
        writer.close();
    }

    @Test
    public void testLoadFailureKeepsSameBatchForRetry() throws Exception {
        RecordingObjectStore store = new RecordingObjectStore();
        S3TvfLoad load = mock(S3TvfLoad.class);
        doThrow(new DorisException("failed")).doNothing().when(load).load(anyString(), anyList());
        RecordService records = mock(RecordService.class);
        SinkRecord record = TestRecordBuffer.newSinkRecord("ignored", 1);
        when(records.getProcessedRecord(record)).thenReturn("{\"id\":1,\"name\":\"first\"}");
        AsyncS3TvfWriter writer = writer(options(1024, 100), store, load, records);
        writer.insert(record);

        try {
            writer.commitFlush();
            Assert.fail("Expected first commit to fail");
        } catch (DorisException expected) {
            // Retry the same batch so label reconciliation remains possible.
        }
        writer.commitFlush();

        ArgumentCaptor<String> labels = ArgumentCaptor.forClass(String.class);
        verify(load, org.mockito.Mockito.times(2)).load(labels.capture(), anyList());
        Assert.assertEquals(labels.getAllValues().get(0), labels.getAllValues().get(1));
        Assert.assertEquals(1, store.objects.size());
        writer.close();
    }

    private static AsyncS3TvfWriter writer(
            DorisOptions options, S3ObjectStore store, S3TvfLoad load, RecordService records) {
        return new AsyncS3TvfWriter(
                "demo.orders",
                "orders-topic",
                -1,
                options,
                mock(ConnectionProvider.class),
                mock(DorisSystemService.class),
                mock(DorisConnectMonitor.class),
                records,
                store,
                load,
                () -> BATCH_UUID,
                Executors.newSingleThreadExecutor());
    }

    private static DorisOptions options(int bufferSize, int recordCount) throws IOException {
        InputStream stream =
                AsyncS3TvfWriterTest.class
                        .getClassLoader()
                        .getResourceAsStream("doris-connector-sink.properties");
        Properties properties = new Properties();
        properties.load(stream);
        DorisSinkConnectorConfig.setDefaultValues((Map) properties);
        properties.put("task_id", "7");
        properties.put(DorisSinkConnectorConfig.NAME, "connector");
        properties.put(DorisSinkConnectorConfig.DORIS_DATABASE, "");
        properties.put(DorisSinkConnectorConfig.LABEL_PREFIX, "tvf");
        properties.put(DorisSinkConnectorConfig.LOAD_MODEL, "tvf");
        properties.put(DorisSinkConnectorConfig.ENABLE_COMBINE_FLUSH, "true");
        properties.put(DorisSinkConnectorConfig.DELIVERY_GUARANTEE, "at_least_once");
        properties.put(DorisSinkConnectorConfig.BUFFER_SIZE_BYTES, String.valueOf(bufferSize));
        properties.put(DorisSinkConnectorConfig.BUFFER_COUNT_RECORDS, String.valueOf(recordCount));
        properties.put(DorisSinkConnectorConfig.SINK_S3_ENDPOINT, "https://s3.example.com");
        properties.put(DorisSinkConnectorConfig.SINK_S3_REGION, "us-east-1");
        properties.put(DorisSinkConnectorConfig.SINK_S3_BUCKET, "staging");
        properties.put(DorisSinkConnectorConfig.SINK_S3_PREFIX, "objects");
        properties.put(DorisSinkConnectorConfig.SINK_S3_ACCESS_KEY, "access-key");
        properties.put(DorisSinkConnectorConfig.SINK_S3_SECRET_KEY, "secret-key");
        properties.put(DorisSinkConnectorConfig.STREAM_LOAD_PROP_PREFIX + "columns", "id,name");
        return new DorisOptions((Map) properties);
    }

    private static class RecordingObjectStore implements S3ObjectStore {
        protected final Map<String, byte[]> objects = new LinkedHashMap<>();
        private IOException putFailure;

        @Override
        public synchronized void put(String objectKey, byte[] content) throws IOException {
            if (putFailure != null) {
                throw putFailure;
            }
            objects.put(objectKey, content);
        }

        @Override
        public void close() {}
    }

    private static class BlockingObjectStore extends RecordingObjectStore {
        private final CountDownLatch uploadStarted = new CountDownLatch(1);
        private final CountDownLatch continueUpload = new CountDownLatch(1);

        @Override
        public void put(String objectKey, byte[] content) throws IOException {
            uploadStarted.countDown();
            try {
                continueUpload.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("Upload interrupted", e);
            }
            super.put(objectKey, content);
        }
    }
}
