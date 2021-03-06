/*
 * Licensed to Elasticsearch under one or more contributor
 * license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright
 * ownership. Elasticsearch licenses this file to you under
 * the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.elasticsearch.upgrades;

import org.apache.http.util.EntityUtils;
import org.elasticsearch.Version;
import org.elasticsearch.action.support.PlainActionFuture;
import org.elasticsearch.client.Request;
import org.elasticsearch.client.Response;
import org.elasticsearch.client.ResponseException;
import org.elasticsearch.cluster.metadata.IndexMetaData;
import org.elasticsearch.cluster.metadata.MetaDataIndexStateService;
import org.elasticsearch.common.Booleans;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.util.concurrent.AbstractRunnable;
import org.elasticsearch.common.xcontent.support.XContentMapValues;
import org.elasticsearch.index.IndexSettings;
import org.elasticsearch.rest.RestStatus;
import org.elasticsearch.rest.action.document.RestGetAction;
import org.elasticsearch.rest.action.document.RestIndexAction;
import org.elasticsearch.rest.action.document.RestUpdateAction;
import org.elasticsearch.test.rest.yaml.ObjectPath;
import org.hamcrest.Matcher;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

import static com.carrotsearch.randomizedtesting.RandomizedTest.randomAsciiOfLength;
import static org.elasticsearch.cluster.routing.UnassignedInfo.INDEX_DELAYED_NODE_LEFT_TIMEOUT_SETTING;
import static org.elasticsearch.cluster.routing.allocation.decider.EnableAllocationDecider.INDEX_ROUTING_ALLOCATION_ENABLE_SETTING;
import static org.elasticsearch.cluster.routing.allocation.decider.MaxRetryAllocationDecider.SETTING_ALLOCATION_MAX_RETRY;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.isIn;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;

/**
 * In depth testing of the recovery mechanism during a rolling restart.
 */
public class RecoveryIT extends AbstractRollingTestCase {

    public void testHistoryUUIDIsGenerated() throws Exception {
        final String index = "index_history_uuid";
        if (CLUSTER_TYPE == ClusterType.OLD) {
            Settings.Builder settings = Settings.builder()
                .put(IndexMetaData.INDEX_NUMBER_OF_SHARDS_SETTING.getKey(), 1)
                .put(IndexMetaData.INDEX_NUMBER_OF_REPLICAS_SETTING.getKey(), 1)
                // if the node with the replica is the first to be restarted, while a replica is still recovering
                // then delayed allocation will kick in. When the node comes back, the master will search for a copy
                // but the recovering copy will be seen as invalid and the cluster health won't return to GREEN
                // before timing out
                .put(INDEX_DELAYED_NODE_LEFT_TIMEOUT_SETTING.getKey(), "100ms");
            createIndex(index, settings.build());
        } else if (CLUSTER_TYPE == ClusterType.UPGRADED) {
            ensureGreen(index);
            Request shardStatsRequest = new Request("GET", index + "/_stats");
            shardStatsRequest.addParameter("level", "shards");
            Response response = client().performRequest(shardStatsRequest);
            ObjectPath objectPath = ObjectPath.createFromResponse(response);
            List<Object> shardStats = objectPath.evaluate("indices." + index + ".shards.0");
            assertThat(shardStats, hasSize(2));
            String expectHistoryUUID = null;
            for (int shard = 0; shard < 2; shard++) {
                String nodeID = objectPath.evaluate("indices." + index + ".shards.0." + shard + ".routing.node");
                String historyUUID = objectPath.evaluate("indices." + index + ".shards.0." + shard + ".commit.user_data.history_uuid");
                assertThat("no history uuid found for shard on " + nodeID, historyUUID, notNullValue());
                if (expectHistoryUUID == null) {
                    expectHistoryUUID = historyUUID;
                } else {
                    assertThat("different history uuid found for shard on " + nodeID, historyUUID, equalTo(expectHistoryUUID));
                }
            }
        }
    }

    private int indexDocs(String index, final int idStart, final int numDocs) throws IOException {
        for (int i = 0; i < numDocs; i++) {
            final int id = idStart + i;
            Request indexDoc = new Request("PUT", index + "/test/" + id);
            indexDoc.setJsonEntity("{\"test\": \"test_" + randomAsciiOfLength(2) + "\"}");
            indexDoc.setOptions(expectWarnings(RestIndexAction.TYPES_DEPRECATION_MESSAGE));
            client().performRequest(indexDoc);
        }
        return numDocs;
    }

    private Future<Void> asyncIndexDocs(String index, final int idStart, final int numDocs) throws IOException {
        PlainActionFuture<Void> future = new PlainActionFuture<>();
        Thread background = new Thread(new AbstractRunnable() {
            @Override
            public void onFailure(Exception e) {
                future.onFailure(e);
            }

            @Override
            protected void doRun() throws Exception {
                indexDocs(index, idStart, numDocs);
                future.onResponse(null);
            }
        });
        background.start();
        return future;
    }

    public void testRecoveryWithConcurrentIndexing() throws Exception {
        final String index = "recovery_with_concurrent_indexing";
        Response response = client().performRequest(new Request("GET", "_nodes"));
        ObjectPath objectPath = ObjectPath.createFromResponse(response);
        final Map<String, Object> nodeMap = objectPath.evaluate("nodes");
        List<String> nodes = new ArrayList<>(nodeMap.keySet());

        switch (CLUSTER_TYPE) {
            case OLD:
                Settings.Builder settings = Settings.builder()
                    .put(IndexMetaData.INDEX_NUMBER_OF_SHARDS_SETTING.getKey(), 1)
                    .put(IndexMetaData.INDEX_NUMBER_OF_REPLICAS_SETTING.getKey(), 2)
                    // if the node with the replica is the first to be restarted, while a replica is still recovering
                    // then delayed allocation will kick in. When the node comes back, the master will search for a copy
                    // but the recovering copy will be seen as invalid and the cluster health won't return to GREEN
                    // before timing out
                    .put(INDEX_DELAYED_NODE_LEFT_TIMEOUT_SETTING.getKey(), "100ms")
                    .put(SETTING_ALLOCATION_MAX_RETRY.getKey(), "0"); // fail faster
                createIndex(index, settings.build());
                indexDocs(index, 0, 10);
                ensureGreen(index);
                // make sure that we can index while the replicas are recovering
                updateIndexSettings(index, Settings.builder().put(INDEX_ROUTING_ALLOCATION_ENABLE_SETTING.getKey(), "primaries"));
                break;
            case MIXED:
                updateIndexSettings(index, Settings.builder().put(INDEX_ROUTING_ALLOCATION_ENABLE_SETTING.getKey(), (String)null));
                asyncIndexDocs(index, 10, 50).get();
                ensureGreen(index);
                client().performRequest(new Request("POST", index + "/_refresh"));
                assertCount(index, "_only_nodes:" + nodes.get(0), 60);
                assertCount(index, "_only_nodes:" + nodes.get(1), 60);
                assertCount(index, "_only_nodes:" + nodes.get(2), 60);
                // make sure that we can index while the replicas are recovering
                updateIndexSettings(index, Settings.builder().put(INDEX_ROUTING_ALLOCATION_ENABLE_SETTING.getKey(), "primaries"));
                break;
            case UPGRADED:
                updateIndexSettings(index, Settings.builder().put(INDEX_ROUTING_ALLOCATION_ENABLE_SETTING.getKey(), (String)null));
                asyncIndexDocs(index, 60, 45).get();
                ensureGreen(index);
                client().performRequest(new Request("POST", index + "/_refresh"));
                assertCount(index, "_only_nodes:" + nodes.get(0), 105);
                assertCount(index, "_only_nodes:" + nodes.get(1), 105);
                assertCount(index, "_only_nodes:" + nodes.get(2), 105);
                break;
            default:
                throw new IllegalStateException("unknown type " + CLUSTER_TYPE);
        }
    }

    private void assertDocCountOnAllCopies(String index, int expectedCount) throws Exception {
        assertBusy(() -> {
            Map<String, ?> state = entityAsMap(client().performRequest(new Request("GET", "/_cluster/state")));
            String xpath = "routing_table.indices." + index + ".shards.0.node";
            @SuppressWarnings("unchecked") List<String> assignedNodes = (List<String>) XContentMapValues.extractValue(xpath, state);
            assertNotNull(state.toString(), assignedNodes);
            for (String assignedNode : assignedNodes) {
                try {
                    assertCount(index, "_only_nodes:" + assignedNode, expectedCount);
                } catch (ResponseException e) {
                    if (e.getMessage().contains("no data nodes with criteria [" + assignedNode + "found for shard: [" + index + "][0]")) {
                        throw new AssertionError(e); // shard is relocating - ask assert busy to retry
                    }
                    throw e;
                }
            }
        });
    }

    private void assertCount(final String index, final String preference, final int expectedCount) throws IOException {
        final int actualDocs;
        try {
            final Request request = new Request("GET", index + "/_count");
            if (preference != null) {
                request.addParameter("preference", preference);
            }
            final Response response = client().performRequest(request);
            actualDocs = Integer.parseInt(ObjectPath.createFromResponse(response).evaluate("count").toString());
        } catch (ResponseException e) {
            try {
                final Response recoveryStateResponse = client().performRequest(new Request("GET", index + "/_recovery"));
                fail("failed to get doc count for index [" + index + "] with preference [" + preference + "]" + " response [" + e + "]"
                    + " recovery [" + EntityUtils.toString(recoveryStateResponse.getEntity()) + "]");
            } catch (Exception inner) {
                e.addSuppressed(inner);
            }
            throw e;
        }
        assertThat("preference [" + preference + "]", actualDocs, equalTo(expectedCount));
    }

    private String getNodeId(Predicate<Version> versionPredicate) throws IOException {
        Response response = client().performRequest(new Request("GET", "_nodes"));
        ObjectPath objectPath = ObjectPath.createFromResponse(response);
        Map<String, Object> nodesAsMap = objectPath.evaluate("nodes");
        for (String id : nodesAsMap.keySet()) {
            Version version = Version.fromString(objectPath.evaluate("nodes." + id + ".version"));
            if (versionPredicate.test(version)) {
                return id;
            }
        }
        return null;
    }

    public void testRelocationWithConcurrentIndexing() throws Exception {
        final String index = "relocation_with_concurrent_indexing";
        switch (CLUSTER_TYPE) {
            case OLD:
                Settings.Builder settings = Settings.builder()
                    .put(IndexMetaData.INDEX_NUMBER_OF_SHARDS_SETTING.getKey(), 1)
                    .put(IndexMetaData.INDEX_NUMBER_OF_REPLICAS_SETTING.getKey(), 2)
                    // if the node with the replica is the first to be restarted, while a replica is still recovering
                    // then delayed allocation will kick in. When the node comes back, the master will search for a copy
                    // but the recovering copy will be seen as invalid and the cluster health won't return to GREEN
                    // before timing out
                    .put(INDEX_DELAYED_NODE_LEFT_TIMEOUT_SETTING.getKey(), "100ms")
                    .put(SETTING_ALLOCATION_MAX_RETRY.getKey(), "0"); // fail faster
                createIndex(index, settings.build());
                indexDocs(index, 0, 10);
                ensureGreen(index);
                // make sure that no shards are allocated, so we can make sure the primary stays on the old node (when one
                // node stops, we lose the master too, so a replica will not be promoted)
                updateIndexSettings(index, Settings.builder().put(INDEX_ROUTING_ALLOCATION_ENABLE_SETTING.getKey(), "none"));
                break;
            case MIXED:
                final String newNode = getNodeId(v -> v.equals(Version.CURRENT));
                final String oldNode = getNodeId(v -> v.before(Version.CURRENT));
                // remove the replica and guaranteed the primary is placed on the old node
                updateIndexSettings(index, Settings.builder()
                    .put(IndexMetaData.INDEX_NUMBER_OF_REPLICAS_SETTING.getKey(), 0)
                    .put(INDEX_ROUTING_ALLOCATION_ENABLE_SETTING.getKey(), (String)null)
                    .put("index.routing.allocation.include._id", oldNode)
                );
                ensureGreen(index); // wait for the primary to be assigned
                ensureNoInitializingShards(); // wait for all other shard activity to finish
                updateIndexSettings(index, Settings.builder().put("index.routing.allocation.include._id", newNode));
                asyncIndexDocs(index, 10, 50).get();
                // ensure the relocation from old node to new node has occurred; otherwise ensureGreen can
                // return true even though shards haven't moved to the new node yet (allocation was throttled).
                assertBusy(() -> {
                    Map<String, ?> state = entityAsMap(client().performRequest(new Request("GET", "/_cluster/state")));
                    String xpath = "routing_table.indices." + index + ".shards.0.node";
                    @SuppressWarnings("unchecked") List<String> assignedNodes = (List<String>) XContentMapValues.extractValue(xpath, state);
                    assertNotNull(state.toString(), assignedNodes);
                    assertThat(state.toString(), newNode, isIn(assignedNodes));
                }, 60, TimeUnit.SECONDS);
                ensureGreen(index);
                client().performRequest(new Request("POST", index + "/_refresh"));
                assertCount(index, "_only_nodes:" + newNode, 60);
                break;
            case UPGRADED:
                updateIndexSettings(index, Settings.builder()
                    .put(IndexMetaData.INDEX_NUMBER_OF_REPLICAS_SETTING.getKey(), 2)
                    .put("index.routing.allocation.include._id", (String)null)
                );
                asyncIndexDocs(index, 60, 45).get();
                ensureGreen(index);
                client().performRequest(new Request("POST", index + "/_refresh"));
                Response response = client().performRequest(new Request("GET", "_nodes"));
                ObjectPath objectPath = ObjectPath.createFromResponse(response);
                final Map<String, Object> nodeMap = objectPath.evaluate("nodes");
                List<String> nodes = new ArrayList<>(nodeMap.keySet());

                assertCount(index, "_only_nodes:" + nodes.get(0), 105);
                assertCount(index, "_only_nodes:" + nodes.get(1), 105);
                assertCount(index, "_only_nodes:" + nodes.get(2), 105);
                break;
            default:
                throw new IllegalStateException("unknown type " + CLUSTER_TYPE);
        }
    }

    /**
     * This test ensures that peer recovery won't get stuck in a situation where the recovery target and recovery source
     * have an identical sync id but different local checkpoint in the commit in particular the target does not have
     * sequence numbers yet. This is possible if the primary is on 6.x while the replica was on 5.x and some write
     * operations with sequence numbers have taken place. If this is not the case, then peer recovery should utilize
     * syncId and skip copying files.
     */
    public void testRecoverSyncedFlushIndex() throws Exception {
        final String index = "recover_synced_flush_index";
        if (CLUSTER_TYPE == ClusterType.OLD) {
            Settings.Builder settings = Settings.builder()
                .put(IndexMetaData.INDEX_NUMBER_OF_SHARDS_SETTING.getKey(), 1)
                .put(IndexMetaData.INDEX_NUMBER_OF_REPLICAS_SETTING.getKey(), 2);
            if (randomBoolean()) {
                settings.put(IndexSettings.INDEX_TRANSLOG_RETENTION_AGE_SETTING.getKey(), "-1")
                    .put(IndexSettings.INDEX_TRANSLOG_RETENTION_SIZE_SETTING.getKey(), "-1")
                    .put(IndexSettings.INDEX_TRANSLOG_GENERATION_THRESHOLD_SIZE_SETTING.getKey(), "256b");
            }
            createIndex(index, settings.build());
            ensureGreen(index);
            indexDocs(index, 0, 40);
            syncedFlush(index);
        } else if (CLUSTER_TYPE == ClusterType.MIXED) {
            ensureGreen(index);
            if (firstMixedRound) {
                assertPeerRecoveredFiles("peer recovery with syncId should not copy files", index, "upgraded-node-0", equalTo(0));
                assertDocCountOnAllCopies(index, 40);
                indexDocs(index, 40, 50);
                syncedFlush(index);
            } else {
                assertPeerRecoveredFiles("peer recovery with syncId should not copy files", index, "upgraded-node-1", equalTo(0));
                assertDocCountOnAllCopies(index, 90);
                indexDocs(index, 90, 60);
                syncedFlush(index);
                // exclude node-2 from allocation-filter so we can trim translog on the primary before node-2 starts recover
                if (randomBoolean()) {
                    updateIndexSettings(index, Settings.builder().put("index.routing.allocation.include._name", "upgraded-*"));
                }
            }
        } else {
            final int docsAfterUpgraded = randomIntBetween(0, 100);
            indexDocs(index, 150, docsAfterUpgraded);
            ensureGreen(index);
            assertPeerRecoveredFiles("peer recovery with syncId should not copy files", index, "upgraded-node-2", equalTo(0));
            assertDocCountOnAllCopies(index, 150 + docsAfterUpgraded);
        }
    }

    public void testRecoveryWithSoftDeletes() throws Exception {
        final String index = "recover_with_soft_deletes";
        if (CLUSTER_TYPE == ClusterType.OLD) {
            Settings.Builder settings = Settings.builder()
                .put(IndexMetaData.INDEX_NUMBER_OF_SHARDS_SETTING.getKey(), 1)
                .put(IndexMetaData.INDEX_NUMBER_OF_REPLICAS_SETTING.getKey(), 1)
                // if the node with the replica is the first to be restarted, while a replica is still recovering
                // then delayed allocation will kick in. When the node comes back, the master will search for a copy
                // but the recovering copy will be seen as invalid and the cluster health won't return to GREEN
                // before timing out
                .put(INDEX_DELAYED_NODE_LEFT_TIMEOUT_SETTING.getKey(), "100ms")
                .put(SETTING_ALLOCATION_MAX_RETRY.getKey(), "0"); // fail faster
            if (getNodeId(v -> v.onOrAfter(Version.V_6_5_0)) != null && randomBoolean()) {
                settings.put(IndexSettings.INDEX_SOFT_DELETES_SETTING.getKey(), true);
            }
            createIndex(index, settings.build());
            int numDocs = randomInt(10);
            indexDocs(index, 0, numDocs);
            if (randomBoolean()) {
                client().performRequest(new Request("POST", "/" + index + "/_flush"));
            }
            for (int i = 0; i < numDocs; i++) {
                if (randomBoolean()) {
                    indexDocs(index, i, 1); // update
                } else if (randomBoolean()) {
                    if (getNodeId(v -> v.onOrAfter(Version.V_7_0_0)) == null) {
                        client().performRequest(new Request("DELETE", index + "/test/" + i));
                    } else {
                        client().performRequest(new Request("DELETE", index + "/_doc/" + i));
                    }
                }
            }
        }
        ensureGreen(index);
    }

    /**
     * This test creates an index in the non upgraded cluster and closes it. It then checks that the index
     * is effectively closed and potentially replicated (if the version the index was created on supports
     * the replication of closed indices) during the rolling upgrade.
     */
    public void testRecoveryClosedIndex() throws Exception {
        final String indexName = "closed_index_created_on_old";
        if (CLUSTER_TYPE == ClusterType.OLD) {
            createIndex(indexName, Settings.builder()
                .put(IndexMetaData.INDEX_NUMBER_OF_SHARDS_SETTING.getKey(), 1)
                .put(IndexMetaData.INDEX_NUMBER_OF_REPLICAS_SETTING.getKey(), 1)
                // if the node with the replica is the first to be restarted, while a replica is still recovering
                // then delayed allocation will kick in. When the node comes back, the master will search for a copy
                // but the recovering copy will be seen as invalid and the cluster health won't return to GREEN
                // before timing out
                .put(INDEX_DELAYED_NODE_LEFT_TIMEOUT_SETTING.getKey(), "100ms")
                .put(SETTING_ALLOCATION_MAX_RETRY.getKey(), "0") // fail faster
                .build());
            ensureGreen(indexName);
            closeIndex(indexName);
        }

        final Version indexVersionCreated = indexVersionCreated(indexName);
        if (indexVersionCreated.onOrAfter(Version.V_7_2_0)) {
            // index was created on a version that supports the replication of closed indices,
            // so we expect the index to be closed and replicated
            ensureGreen(indexName);
            assertClosedIndex(indexName, true);
        } else {
            assertClosedIndex(indexName, false);
        }
    }

    /**
     * This test creates and closes a new index at every stage of the rolling upgrade. It then checks that the index
     * is effectively closed and potentially replicated if the cluster supports replication of closed indices at the
     * time the index was closed.
     */
    public void testCloseIndexDuringRollingUpgrade() throws Exception {
        final Version minimumNodeVersion = minimumNodeVersion();
        final String indexName =
            String.join("_", "index", CLUSTER_TYPE.toString(), Integer.toString(minimumNodeVersion.id)).toLowerCase(Locale.ROOT);

        final Request indexExistsRequest = new Request("HEAD", "/" + indexName);
        indexExistsRequest.setOptions(allowTypesRemovalWarnings());

        final Response indexExistsResponse = client().performRequest(indexExistsRequest);
        if (RestStatus.OK.getStatus() != indexExistsResponse.getStatusLine().getStatusCode()) {
            createIndex(indexName, Settings.builder()
                .put(IndexMetaData.INDEX_NUMBER_OF_SHARDS_SETTING.getKey(), 1)
                .put(IndexMetaData.INDEX_NUMBER_OF_REPLICAS_SETTING.getKey(), 0)
                .build());
            ensureGreen(indexName);
            closeIndex(indexName);
        }

        if (minimumNodeVersion.onOrAfter(Version.V_7_2_0)) {
            // index is created on a version that supports the replication of closed indices,
            // so we expect the index to be closed and replicated
            ensureGreen(indexName);
            assertClosedIndex(indexName, true);
        } else {
            assertClosedIndex(indexName, false);
        }
    }

    /**
     * Returns the version in which the given index has been created
     */
    private static Version indexVersionCreated(final String indexName) throws IOException {
        final Request request = new Request("GET", "/" + indexName + "/_settings");
        final String versionCreatedSetting = indexName + ".settings.index.version.created";
        request.addParameter("filter_path", versionCreatedSetting);

        final Response response = client().performRequest(request);
        return Version.fromId(Integer.parseInt(ObjectPath.createFromResponse(response).evaluate(versionCreatedSetting)));
    }

    /**
     * Returns the minimum node version among all nodes of the cluster
     */
    private static Version minimumNodeVersion() throws IOException {
        final Request request = new Request("GET", "_nodes");
        request.addParameter("filter_path", "nodes.*.version");

        final Response response = client().performRequest(request);
        final Map<String, Object> nodes = ObjectPath.createFromResponse(response).evaluate("nodes");

        Version minVersion = null;
        for (Map.Entry<String, Object> node : nodes.entrySet()) {
            @SuppressWarnings("unchecked")
            Version nodeVersion = Version.fromString((String) ((Map<String, Object>) node.getValue()).get("version"));
            if (minVersion == null || minVersion.after(nodeVersion)) {
                minVersion = nodeVersion;
            }
        }
        assertNotNull(minVersion);
        return minVersion;
    }

    /**
     * Asserts that an index is closed in the cluster state. If `checkRoutingTable` is true, it also asserts
     * that the index has started shards.
     */
    @SuppressWarnings("unchecked")
    private void assertClosedIndex(final String index, final boolean checkRoutingTable) throws IOException {
        final Map<String, ?> state = entityAsMap(client().performRequest(new Request("GET", "/_cluster/state")));

        final Map<String, ?> metadata = (Map<String, Object>) XContentMapValues.extractValue("metadata.indices." + index, state);
        assertThat(metadata, notNullValue());
        assertThat(metadata.get("state"), equalTo("close"));

        final Map<String, ?> blocks = (Map<String, Object>) XContentMapValues.extractValue("blocks.indices." + index, state);
        assertThat(blocks, notNullValue());
        assertThat(blocks.containsKey(String.valueOf(MetaDataIndexStateService.INDEX_CLOSED_BLOCK_ID)), is(true));

        final Map<String, ?> settings = (Map<String, Object>) XContentMapValues.extractValue("settings", metadata);
        assertThat(settings, notNullValue());

        final int numberOfShards = Integer.parseInt((String) XContentMapValues.extractValue("index.number_of_shards", settings));
        final int numberOfReplicas = Integer.parseInt((String) XContentMapValues.extractValue("index.number_of_replicas", settings));

        final Map<String, ?> routingTable = (Map<String, Object>) XContentMapValues.extractValue("routing_table.indices." + index, state);
        if (checkRoutingTable) {
            assertThat(routingTable, notNullValue());
            assertThat(Booleans.parseBoolean((String) XContentMapValues.extractValue("index.verified_before_close", settings)), is(true));

            for (int i = 0; i < numberOfShards; i++) {
                final Collection<Map<String, ?>> shards =
                    (Collection<Map<String, ?>>) XContentMapValues.extractValue("shards." + i, routingTable);
                assertThat(shards, notNullValue());
                assertThat(shards.size(), equalTo(numberOfReplicas + 1));
                for (Map<String, ?> shard : shards) {
                    assertThat(XContentMapValues.extractValue("shard", shard), equalTo(i));
                    assertThat(XContentMapValues.extractValue("state", shard), equalTo("STARTED"));
                    assertThat(XContentMapValues.extractValue("index", shard), equalTo(index));
                }
            }
        } else {
            assertThat(routingTable, nullValue());
            assertThat(XContentMapValues.extractValue("index.verified_before_close", settings), nullValue());
        }
    }

    private void syncedFlush(String index) throws Exception {
        // We have to spin synced-flush requests here because we fire the global checkpoint sync for the last write operation.
        // A synced-flush request considers the global checkpoint sync as an going operation because it acquires a shard permit.
        assertBusy(() -> {
            try {
                Response resp = client().performRequest(new Request("POST", index + "/_flush/synced"));
                Map<String, Object> result = ObjectPath.createFromResponse(resp).evaluate("_shards");
                assertThat(result.get("failed"), equalTo(0));
            } catch (ResponseException ex) {
                throw new AssertionError(ex); // cause assert busy to retry
            }
        });
        // ensure the global checkpoint is synced; otherwise we might trim the commit with syncId
        ensureGlobalCheckpointSynced(index);
    }

    @SuppressWarnings("unchecked")
    private void assertPeerRecoveredFiles(String reason, String index, String targetNode, Matcher<Integer> sizeMatcher) throws IOException {
        Map<?, ?> recoveryStats = entityAsMap(client().performRequest(new Request("GET", index + "/_recovery")));
        List<Map<?, ?>> shards = (List<Map<?, ?>>) XContentMapValues.extractValue(index + "." + "shards", recoveryStats);
        for (Map<?, ?> shard : shards) {
            if (Objects.equals(XContentMapValues.extractValue("type", shard), "PEER")) {
                if (Objects.equals(XContentMapValues.extractValue("target.name", shard), targetNode)) {
                    Integer recoveredFileSize = (Integer) XContentMapValues.extractValue("index.files.recovered", shard);
                    assertThat(reason + " target node [" + targetNode + "] stats [" + recoveryStats + "]", recoveredFileSize, sizeMatcher);
                }
            }
        }
    }

    @SuppressWarnings("unchecked")
    private void ensureGlobalCheckpointSynced(String index) throws Exception {
        assertBusy(() -> {
            Map<?, ?> stats = entityAsMap(client().performRequest(new Request("GET", index + "/_stats?level=shards")));
            List<Map<?, ?>> shardStats = (List<Map<?, ?>>) XContentMapValues.extractValue("indices." + index + ".shards.0", stats);
            shardStats.stream()
                .map(shard -> (Map<?, ?>) XContentMapValues.extractValue("seq_no", shard))
                .filter(Objects::nonNull)
                .forEach(seqNoStat -> {
                    long globalCheckpoint = ((Number) XContentMapValues.extractValue("global_checkpoint", seqNoStat)).longValue();
                    long localCheckpoint = ((Number) XContentMapValues.extractValue("local_checkpoint", seqNoStat)).longValue();
                    long maxSeqNo = ((Number) XContentMapValues.extractValue("max_seq_no", seqNoStat)).longValue();
                    assertThat(shardStats.toString(), localCheckpoint, equalTo(maxSeqNo));
                    assertThat(shardStats.toString(), globalCheckpoint, equalTo(maxSeqNo));
                });
        }, 60, TimeUnit.SECONDS);
    }

    /** Ensure that we can always execute update requests regardless of the version of cluster */
    public void testUpdateDoc() throws Exception {
        final String index = "test_update_doc";
        if (CLUSTER_TYPE == ClusterType.OLD) {
            Settings.Builder settings = Settings.builder()
                .put(IndexMetaData.INDEX_NUMBER_OF_SHARDS_SETTING.getKey(), 1)
                .put(IndexMetaData.INDEX_NUMBER_OF_REPLICAS_SETTING.getKey(), 2);
            createIndex(index, settings.build());
            indexDocs(index, 0, 100);
        }
        if (randomBoolean()) {
            ensureGreen(index);
        }
        Map<Integer, Long> updates = new HashMap<>();
        for (int docId = 0; docId < 100; docId++) {
            final int times = randomIntBetween(0, 2);
            for (int i = 0; i < times; i++) {
                long value = randomNonNegativeLong();
                Request update = new Request("POST", index + "/test/" + docId + "/_update");
                update.setOptions(expectWarnings(RestUpdateAction.TYPES_DEPRECATION_MESSAGE));
                update.setJsonEntity("{\"doc\": {\"updated_field\": " + value + "}}");
                client().performRequest(update);
                updates.put(docId, value);
            }
        }
        client().performRequest(new Request("POST", index + "/_refresh"));
        for (int docId : updates.keySet()) {
            Request get = new Request("GET", index + "/test/" + docId);
            get.setOptions(expectWarnings(RestGetAction.TYPES_DEPRECATION_MESSAGE));
            Map<String, Object> doc = entityAsMap(client().performRequest(get));
            assertThat(XContentMapValues.extractValue("_source.updated_field", doc), equalTo(updates.get(docId)));
        }
        if (randomBoolean()) {
            syncedFlush(index);
        }
    }
}
