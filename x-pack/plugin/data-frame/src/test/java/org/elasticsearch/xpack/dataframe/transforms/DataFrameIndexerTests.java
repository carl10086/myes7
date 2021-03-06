/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */

package org.elasticsearch.xpack.dataframe.transforms;

import org.apache.lucene.search.TotalHits;
import org.elasticsearch.action.ActionListener;
import org.elasticsearch.action.bulk.BulkItemResponse;
import org.elasticsearch.action.bulk.BulkRequest;
import org.elasticsearch.action.bulk.BulkResponse;
import org.elasticsearch.action.search.SearchPhaseExecutionException;
import org.elasticsearch.action.search.SearchRequest;
import org.elasticsearch.action.search.SearchResponse;
import org.elasticsearch.action.search.ShardSearchFailure;
import org.elasticsearch.client.Client;
import org.elasticsearch.common.breaker.CircuitBreaker.Durability;
import org.elasticsearch.common.breaker.CircuitBreakingException;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.util.concurrent.ThreadContext;
import org.elasticsearch.search.SearchHit;
import org.elasticsearch.search.SearchHits;
import org.elasticsearch.search.internal.InternalSearchResponse;
import org.elasticsearch.search.profile.SearchProfileShardResults;
import org.elasticsearch.search.suggest.Suggest;
import org.elasticsearch.test.ESTestCase;
import org.elasticsearch.threadpool.ThreadPool;
import org.elasticsearch.xpack.core.dataframe.transforms.DataFrameIndexerPosition;
import org.elasticsearch.xpack.core.dataframe.transforms.DataFrameIndexerTransformStats;
import org.elasticsearch.xpack.core.dataframe.transforms.DataFrameTransformCheckpoint;
import org.elasticsearch.xpack.core.dataframe.transforms.DataFrameTransformConfig;
import org.elasticsearch.xpack.core.dataframe.transforms.pivot.AggregationConfigTests;
import org.elasticsearch.xpack.core.dataframe.transforms.pivot.GroupConfigTests;
import org.elasticsearch.xpack.core.dataframe.transforms.pivot.PivotConfig;
import org.elasticsearch.xpack.core.indexing.IndexerState;
import org.elasticsearch.xpack.core.indexing.IterationResult;
import org.elasticsearch.xpack.dataframe.notifications.DataFrameAuditor;
import org.elasticsearch.xpack.dataframe.transforms.pivot.Pivot;
import org.junit.Before;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Function;

import static org.elasticsearch.xpack.core.dataframe.transforms.DestConfigTests.randomDestConfig;
import static org.elasticsearch.xpack.core.dataframe.transforms.SourceConfigTests.randomSourceConfig;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.nullValue;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThan;
import static org.mockito.Matchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class DataFrameIndexerTests extends ESTestCase {

    private Client client;

    class MockedDataFrameIndexer extends DataFrameIndexer {

        private final Function<SearchRequest, SearchResponse> searchFunction;
        private final Function<BulkRequest, BulkResponse> bulkFunction;
        private final Consumer<Exception> failureConsumer;

        // used for synchronizing with the test
        private CountDownLatch latch;

        MockedDataFrameIndexer(
                Executor executor,
                DataFrameTransformConfig transformConfig,
                Map<String, String> fieldMappings,
                DataFrameAuditor auditor,
                AtomicReference<IndexerState> initialState,
                DataFrameIndexerPosition initialPosition,
                DataFrameIndexerTransformStats jobStats,
                Function<SearchRequest, SearchResponse> searchFunction,
                Function<BulkRequest, BulkResponse> bulkFunction,
                Consumer<Exception> failureConsumer) {
            super(executor, auditor, transformConfig, fieldMappings, initialState, initialPosition, jobStats,
                    /* DataFrameTransformProgress */ null, DataFrameTransformCheckpoint.EMPTY, DataFrameTransformCheckpoint.EMPTY);
            this.searchFunction = searchFunction;
            this.bulkFunction = bulkFunction;
            this.failureConsumer = failureConsumer;
        }

        public CountDownLatch newLatch(int count) {
            return latch = new CountDownLatch(count);
        }

        @Override
        protected void createCheckpoint(ActionListener<DataFrameTransformCheckpoint> listener) {
            listener.onResponse(DataFrameTransformCheckpoint.EMPTY);
        }

        @Override
        protected String getJobId() {
            return transformConfig.getId();
        }

        @Override
        protected void doNextSearch(SearchRequest request, ActionListener<SearchResponse> nextPhase) {
            assert latch != null;
            try {
                latch.await();
            } catch (InterruptedException e) {
                throw new IllegalStateException(e);
            }

            try {
                SearchResponse response = searchFunction.apply(request);
                nextPhase.onResponse(response);
            } catch (Exception e) {
                nextPhase.onFailure(e);
            }
        }

        @Override
        protected void doNextBulk(BulkRequest request, ActionListener<BulkResponse> nextPhase) {
            assert latch != null;
            try {
                latch.await();
            } catch (InterruptedException e) {
                throw new IllegalStateException(e);
            }

            try {
                BulkResponse response = bulkFunction.apply(request);
                nextPhase.onResponse(response);
            } catch (Exception e) {
                nextPhase.onFailure(e);
            }
        }

        @Override
        protected void doSaveState(IndexerState state, DataFrameIndexerPosition position, Runnable next) {
            assert state == IndexerState.STARTED || state == IndexerState.INDEXING || state == IndexerState.STOPPED;
            next.run();
        }

        @Override
        protected void onFailure(Exception exc) {
            try {
                // mimic same behavior as {@link DataFrameTransformTask}
                if (handleCircuitBreakingException(exc)) {
                    return;
                }

                failureConsumer.accept(exc);
            } catch (Exception e) {
                fail("Internal error: " + e.getMessage());
            }
        }

        @Override
        protected void onFinish(ActionListener<Void> listener) {
            super.onFinish(listener);
            listener.onResponse(null);
        }

        @Override
        protected void onAbort() {
            fail("onAbort should not be called");
        }

        @Override
        protected void failIndexer(String message) {
            fail("failIndexer should not be called, received error: " + message);
        }

        @Override
        protected boolean sourceHasChanged() {
            return false;
        }

    }

    @Before
    public void setUpMocks() {
        client = mock(Client.class);
        ThreadPool threadPool = mock(ThreadPool.class);
        when(client.threadPool()).thenReturn(threadPool);
        when(threadPool.getThreadContext()).thenReturn(new ThreadContext(Settings.EMPTY));
    }

    public void testPageSizeAdapt() throws InterruptedException {
        Integer pageSize = randomBoolean() ? null : randomIntBetween(500, 10_000);
        DataFrameTransformConfig config = new DataFrameTransformConfig(randomAlphaOfLength(10),
            randomSourceConfig(),
            randomDestConfig(),
            null,
            null,
            null,
            new PivotConfig(GroupConfigTests.randomGroupConfig(), AggregationConfigTests.randomAggregationConfig(), pageSize),
            randomBoolean() ? null : randomAlphaOfLengthBetween(1, 1000));
        AtomicReference<IndexerState> state = new AtomicReference<>(IndexerState.STOPPED);
        final long initialPageSize = pageSize == null ? Pivot.DEFAULT_INITIAL_PAGE_SIZE : pageSize;
        Function<SearchRequest, SearchResponse> searchFunction = searchRequest -> {
            throw new SearchPhaseExecutionException("query", "Partial shards failure", new ShardSearchFailure[] {
                    new ShardSearchFailure(new CircuitBreakingException("to much memory", 110, 100, Durability.TRANSIENT)) });
        };

        Function<BulkRequest, BulkResponse> bulkFunction = bulkRequest -> new BulkResponse(new BulkItemResponse[0], 100);

        Consumer<Exception> failureConsumer = e -> {
            final StringWriter sw = new StringWriter();
            final PrintWriter pw = new PrintWriter(sw, true);
            e.printStackTrace(pw);
            fail("expected circuit breaker exception to be handled, got:" + e + " Trace: " + sw.getBuffer().toString());
        };

        final ExecutorService executor = Executors.newFixedThreadPool(1);
        try {
            DataFrameAuditor auditor = new DataFrameAuditor(client, "node_1");

            MockedDataFrameIndexer indexer = new MockedDataFrameIndexer(executor, config, Collections.emptyMap(), auditor, state, null,
                    new DataFrameIndexerTransformStats(config.getId()), searchFunction, bulkFunction, failureConsumer);
            final CountDownLatch latch = indexer.newLatch(1);
            indexer.start();
            assertThat(indexer.getState(), equalTo(IndexerState.STARTED));
            assertTrue(indexer.maybeTriggerAsyncJob(System.currentTimeMillis()));
            assertThat(indexer.getState(), equalTo(IndexerState.INDEXING));
            latch.countDown();
            awaitBusy(() -> indexer.getState() == IndexerState.STOPPED);
            long pageSizeAfterFirstReduction = indexer.getPageSize();
            assertThat(initialPageSize, greaterThan(pageSizeAfterFirstReduction));
            assertThat(pageSizeAfterFirstReduction, greaterThan((long)DataFrameIndexer.MINIMUM_PAGE_SIZE));

            // run indexer a 2nd time
            final CountDownLatch secondRunLatch = indexer.newLatch(1);
            indexer.start();
            assertEquals(pageSizeAfterFirstReduction, indexer.getPageSize());
            assertThat(indexer.getState(), equalTo(IndexerState.STARTED));
            assertTrue(indexer.maybeTriggerAsyncJob(System.currentTimeMillis()));
            assertThat(indexer.getState(), equalTo(IndexerState.INDEXING));
            secondRunLatch.countDown();
            awaitBusy(() -> indexer.getState() == IndexerState.STOPPED);

            // assert that page size has been reduced again
            assertThat(pageSizeAfterFirstReduction, greaterThan((long)indexer.getPageSize()));
            assertThat(pageSizeAfterFirstReduction, greaterThan((long)DataFrameIndexer.MINIMUM_PAGE_SIZE));

        } finally {
            executor.shutdownNow();
        }
    }

    public void testDoProcessAggNullCheck() {
        Integer pageSize = randomBoolean() ? null : randomIntBetween(500, 10_000);
        DataFrameTransformConfig config = new DataFrameTransformConfig(randomAlphaOfLength(10),
            randomSourceConfig(),
            randomDestConfig(),
            null,
            null,
            null,
            new PivotConfig(GroupConfigTests.randomGroupConfig(), AggregationConfigTests.randomAggregationConfig(), pageSize),
            randomBoolean() ? null : randomAlphaOfLengthBetween(1, 1000));
        SearchResponse searchResponse = new SearchResponse(new InternalSearchResponse(
            new SearchHits(
                new SearchHit[0], new TotalHits(0L, TotalHits.Relation.EQUAL_TO), 0.0f),
            // Simulate completely null aggs
            null,
            new Suggest(Collections.emptyList()),
            new SearchProfileShardResults(Collections.emptyMap()), false, false, 1),
            "", 1, 1, 0, 0, ShardSearchFailure.EMPTY_ARRAY, SearchResponse.Clusters.EMPTY);
        AtomicReference<IndexerState> state = new AtomicReference<>(IndexerState.STOPPED);
        Function<SearchRequest, SearchResponse> searchFunction = searchRequest -> searchResponse;
        Function<BulkRequest, BulkResponse> bulkFunction = bulkRequest -> new BulkResponse(new BulkItemResponse[0], 100);

        Consumer<Exception> failureConsumer = e -> {
            final StringWriter sw = new StringWriter();
            final PrintWriter pw = new PrintWriter(sw, true);
            e.printStackTrace(pw);
            fail(e.getMessage());
        };

        final ExecutorService executor = Executors.newFixedThreadPool(1);
        try {
            DataFrameAuditor auditor = mock(DataFrameAuditor.class);

            MockedDataFrameIndexer indexer = new MockedDataFrameIndexer(executor, config, Collections.emptyMap(), auditor, state, null,
                DataFrameIndexerTransformStats.withDefaultTransformId(), searchFunction, bulkFunction, failureConsumer);

            IterationResult<DataFrameIndexerPosition> newPosition = indexer.doProcess(searchResponse);
            assertThat(newPosition.getToIndex(), is(empty()));
            assertThat(newPosition.getPosition(), is(nullValue()));
            assertThat(newPosition.isDone(), is(true));
            verify(auditor, times(1)).info(anyString(), anyString());
        } finally {
            executor.shutdownNow();
        }
    }

}
