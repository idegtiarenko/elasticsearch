/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0 and the Server Side Public License, v 1; you may not use this file except
 * in compliance with, at your election, the Elastic License 2.0 or the Server
 * Side Public License, v 1.
 */

package org.elasticsearch.cluster.routing.allocation.decider;

import org.elasticsearch.Version;
import org.elasticsearch.cluster.ClusterName;
import org.elasticsearch.cluster.ClusterState;
import org.elasticsearch.cluster.ESAllocationTestCase;
import org.elasticsearch.cluster.metadata.IndexMetadata;
import org.elasticsearch.cluster.metadata.Metadata;
import org.elasticsearch.cluster.metadata.NodesShutdownMetadata;
import org.elasticsearch.cluster.metadata.SingleNodeShutdownMetadata;
import org.elasticsearch.cluster.node.DiscoveryNode;
import org.elasticsearch.cluster.node.DiscoveryNodeRole;
import org.elasticsearch.cluster.node.DiscoveryNodes;
import org.elasticsearch.cluster.routing.RecoverySource;
import org.elasticsearch.cluster.routing.RoutingNode;
import org.elasticsearch.cluster.routing.RoutingNodesHelper;
import org.elasticsearch.cluster.routing.ShardRouting;
import org.elasticsearch.cluster.routing.UnassignedInfo;
import org.elasticsearch.cluster.routing.allocation.RoutingAllocation;
import org.elasticsearch.common.settings.ClusterSettings;
import org.elasticsearch.core.TimeValue;
import org.elasticsearch.index.shard.ShardId;

import java.util.List;
import java.util.Set;

import static org.elasticsearch.common.settings.ClusterSettings.createBuiltInClusterSettings;
import static org.hamcrest.Matchers.equalTo;

public class NodeShutdownAllocationDeciderTests extends ESAllocationTestCase {
    private static final DiscoveryNode DATA_NODE = newNode("node-data", Set.of(DiscoveryNodeRole.DATA_ROLE));
    private final ShardRouting shard = ShardRouting.newUnassigned(
        new ShardId("myindex", "myindex", 0),
        true,
        RecoverySource.EmptyStoreRecoverySource.INSTANCE,
        new UnassignedInfo(UnassignedInfo.Reason.INDEX_CREATED, "index created"),
        ShardRouting.Role.DEFAULT
    );
    private final ClusterSettings clusterSettings = createBuiltInClusterSettings();
    private final NodeShutdownAllocationDecider decider = new NodeShutdownAllocationDecider();
    private final AllocationDeciders allocationDeciders = new AllocationDeciders(
        List.of(decider, new SameShardAllocationDecider(clusterSettings), new ReplicaAfterPrimaryActiveAllocationDecider())
    );

    private final String idxName = "test-idx";
    private final String idxUuid = "test-idx-uuid";
    private final IndexMetadata indexMetadata = IndexMetadata.builder(idxName)
        .settings(indexSettings(Version.CURRENT, 1, 0).put(IndexMetadata.SETTING_INDEX_UUID, idxUuid))
        .build();

    private static final List<SingleNodeShutdownMetadata.Type> REMOVE_SHUTDOWN_TYPES = List.of(
        SingleNodeShutdownMetadata.Type.REPLACE,
        SingleNodeShutdownMetadata.Type.REMOVE,
        SingleNodeShutdownMetadata.Type.SIGTERM
    );

    public void testCanAllocateShardsToRestartingNode() {
        ClusterState state = prepareState(SingleNodeShutdownMetadata.Type.RESTART);
        RoutingNode routingNode = RoutingNodesHelper.routingNode(DATA_NODE.getId(), DATA_NODE, shard);

        assertThatDecision(
            decider.canAllocate(shard, routingNode, createRoutingAllocation(state)),
            Decision.Type.YES,
            "node [" + DATA_NODE.getId() + "] is preparing to restart, but will remain in the cluster"
        );
    }

    public void testCannotAllocateShardsToRemovingNode() {
        for (SingleNodeShutdownMetadata.Type type : REMOVE_SHUTDOWN_TYPES) {
            ClusterState state = prepareState(type);
            RoutingNode routingNode = RoutingNodesHelper.routingNode(DATA_NODE.getId(), DATA_NODE, shard);

            assertThatDecision(
                decider.canAllocate(shard, routingNode, createRoutingAllocation(state)),
                Decision.Type.NO,
                "node [" + DATA_NODE.getId() + "] is preparing to be removed from the cluster"
            );
        }
    }

    public void testShardsCanRemainOnRestartingNode() {
        ClusterState state = prepareState(SingleNodeShutdownMetadata.Type.RESTART);
        RoutingNode routingNode = RoutingNodesHelper.routingNode(DATA_NODE.getId(), DATA_NODE, shard);

        assertThatDecision(
            decider.canRemain(null, shard, routingNode, createRoutingAllocation(state)),
            Decision.Type.YES,
            "node [" + DATA_NODE.getId() + "] is preparing to restart, but will remain in the cluster"
        );
    }

    public void testShardsCannotRemainOnRemovingNode() {
        for (SingleNodeShutdownMetadata.Type type : REMOVE_SHUTDOWN_TYPES) {
            ClusterState state = prepareState(type);
            RoutingNode routingNode = RoutingNodesHelper.routingNode(DATA_NODE.getId(), DATA_NODE, shard);

            assertThatDecision(
                decider.canRemain(null, shard, routingNode, createRoutingAllocation(state)),
                Decision.Type.NO,
                "node [" + DATA_NODE.getId() + "] is preparing to be removed from the cluster"
            );
        }
    }

    public void testCanAutoExpandToRestartingNode() {
        ClusterState state = prepareState(SingleNodeShutdownMetadata.Type.RESTART);

        assertThatDecision(
            decider.shouldAutoExpandToNode(indexMetadata, DATA_NODE, createRoutingAllocation(state)),
            Decision.Type.YES,
            "node [" + DATA_NODE.getId() + "] is preparing to restart, but will remain in the cluster"
        );
    }

    public void testCanAutoExpandToNodeIfNoNodesShuttingDown() {

        assertThatDecision(
            decider.shouldAutoExpandToNode(indexMetadata, DATA_NODE, createRoutingAllocation(ClusterState.EMPTY_STATE)),
            Decision.Type.YES,
            "no nodes are shutting down"
        );
    }

    public void testCanAutoExpandToNodeThatIsNotShuttingDown() {
        for (SingleNodeShutdownMetadata.Type type : REMOVE_SHUTDOWN_TYPES) {
            ClusterState state = prepareState(type, "other-node-id");

            assertThatDecision(
                decider.shouldAutoExpandToNode(indexMetadata, DATA_NODE, createRoutingAllocation(state)),
                Decision.Type.YES,
                "this node is not shutting down"
            );
        }
    }

    public void testCannotAutoExpandToRemovingNode() {
        for (SingleNodeShutdownMetadata.Type type : REMOVE_SHUTDOWN_TYPES) {
            ClusterState state = prepareState(type);

            assertThatDecision(
                decider.shouldAutoExpandToNode(indexMetadata, DATA_NODE, createRoutingAllocation(state)),
                Decision.Type.NO,
                "node [" + DATA_NODE.getId() + "] is preparing to be removed from the cluster"
            );
        }
    }

    private ClusterState prepareState(SingleNodeShutdownMetadata.Type shutdownType) {
        return prepareState(shutdownType, DATA_NODE.getId());
    }

    private ClusterState prepareState(SingleNodeShutdownMetadata.Type shutdownType, String nodeId) {
        return ClusterState.builder(ClusterName.DEFAULT)
            .nodes(DiscoveryNodes.builder().add(DATA_NODE).build())
            .metadata(
                Metadata.builder()
                    .put(IndexMetadata.builder(indexMetadata))
                    .putCustom(NodesShutdownMetadata.TYPE, createNodesShutdownMetadata(shutdownType, nodeId))
            )
            .build();
    }

    private NodesShutdownMetadata createNodesShutdownMetadata(SingleNodeShutdownMetadata.Type shutdownType, String nodeId) {
        final String targetNodeName = shutdownType == SingleNodeShutdownMetadata.Type.REPLACE ? randomAlphaOfLengthBetween(10, 20) : null;
        return NodesShutdownMetadata.EMPTY.putSingleNodeMetadata(
            SingleNodeShutdownMetadata.builder()
                .setNodeId(nodeId)
                .setType(shutdownType)
                .setReason(this.getTestName())
                .setStartedAtMillis(1L)
                .setTargetNodeName(targetNodeName)
                .setGracePeriod(
                    shutdownType == SingleNodeShutdownMetadata.Type.SIGTERM
                        ? TimeValue.parseTimeValue(randomTimeValue(), this.getTestName())
                        : null
                )
                .build()
        );
    }

    private RoutingAllocation createRoutingAllocation(ClusterState state) {
        var allocation = new RoutingAllocation(allocationDeciders, state, null, null, 0);
        allocation.debugDecision(true);
        return allocation;
    }

    private void assertThatDecision(Decision decision, Decision.Type type, String explanation) {
        assertThat(decision.type(), equalTo(type));
        assertThat(decision.getExplanation(), equalTo(explanation));
    }
}
