/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0 and the Server Side Public License, v 1; you may not use this file except
 * in compliance with, at your election, the Elastic License 2.0 or the Server
 * Side Public License, v 1.
 */

package org.elasticsearch.cluster.routing.allocation;

import org.elasticsearch.cluster.node.DiscoveryNode;
import org.elasticsearch.cluster.node.DiscoveryNodeRole;
import org.elasticsearch.cluster.routing.allocation.decider.Decision.Type;
import org.elasticsearch.common.collect.Iterators;
import org.elasticsearch.common.io.stream.StreamInput;
import org.elasticsearch.common.io.stream.StreamOutput;
import org.elasticsearch.common.io.stream.Writeable;
import org.elasticsearch.common.xcontent.ChunkedToXContentHelper;
import org.elasticsearch.common.xcontent.ChunkedToXContentObject;
import org.elasticsearch.core.Nullable;
import org.elasticsearch.xcontent.ToXContent;
import org.elasticsearch.xcontent.XContentBuilder;

import java.io.IOException;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import static org.elasticsearch.TransportVersions.DESIRED_NODE;

/**
 * An abstract class for representing various types of allocation decisions.
 */
public abstract class AbstractAllocationDecision implements ChunkedToXContentObject, Writeable {

    @Nullable
    protected final DiscoveryNode targetNode;
    @Nullable
    protected final Boolean targetNodeIsDesired;
    @Nullable
    protected final List<NodeAllocationResult> nodeDecisions;

    protected AbstractAllocationDecision(
        @Nullable DiscoveryNode targetNode,
        @Nullable Boolean targetNodeIsDesired,
        @Nullable List<NodeAllocationResult> nodeDecisions
    ) {
        this.targetNode = targetNode;
        this.targetNodeIsDesired = targetNodeIsDesired;
        this.nodeDecisions = nodeDecisions != null ? sortNodeDecisions(nodeDecisions) : null;
    }

    protected AbstractAllocationDecision(StreamInput in) throws IOException {
        targetNode = in.readOptionalWriteable(DiscoveryNode::new);
        targetNodeIsDesired = in.getTransportVersion().onOrAfter(DESIRED_NODE) ? in.readOptionalBoolean() : null;
        nodeDecisions = in.readBoolean() ? in.readCollectionAsImmutableList(NodeAllocationResult::new) : null;
    }

    /**
     * Returns {@code true} if a decision was taken by the allocator, {@code false} otherwise.
     * If no decision was taken, then the rest of the fields in this object cannot be accessed and will
     * throw an {@code IllegalStateException}.
     */
    public abstract boolean isDecisionTaken();

    /**
     * Get the node that the allocator will assign the shard to, returning {@code null} if there is no node to
     * which the shard will be assigned or moved.  If {@link #isDecisionTaken()} returns {@code false}, then
     * invoking this method will throw an {@code IllegalStateException}.
     */
    @Nullable
    public DiscoveryNode getTargetNode() {
        checkDecisionState();
        return targetNode;
    }

    /**
     * Gets the sorted list of individual node-level decisions that went into making the ultimate decision whether
     * to allocate or move the shard.  If {@link #isDecisionTaken()} returns {@code false}, then
     * invoking this method will throw an {@code IllegalStateException}.
     */
    @Nullable
    public List<NodeAllocationResult> getNodeDecisions() {
        checkDecisionState();
        return nodeDecisions;
    }

    /**
     * Gets the explanation for the decision.  If {@link #isDecisionTaken()} returns {@code false}, then invoking
     * this method will throw an {@code IllegalStateException}.
     */
    public abstract String getExplanation();

    @Override
    public void writeTo(StreamOutput out) throws IOException {
        out.writeOptionalWriteable(targetNode);
        if (out.getTransportVersion().onOrAfter(DESIRED_NODE)) {
            out.writeOptionalBoolean(targetNodeIsDesired);
        }
        if (nodeDecisions != null) {
            out.writeBoolean(true);
            out.writeCollection(nodeDecisions);
        } else {
            out.writeBoolean(false);
        }
    }

    protected void checkDecisionState() {
        if (isDecisionTaken() == false) {
            throw new IllegalStateException("decision was not taken, individual object fields cannot be accessed");
        }
    }

    /**
     * Generates X-Content for a {@link DiscoveryNode} that leaves off some of the non-critical fields.
     */
    public static XContentBuilder discoveryNodeToXContent(
        DiscoveryNode node,
        Boolean desired,
        boolean outerObjectWritten,
        XContentBuilder builder
    ) throws IOException {

        builder.field(outerObjectWritten ? "id" : "node_id", node.getId());
        builder.field(outerObjectWritten ? "name" : "node_name", node.getName());
        if (desired != null) {
            builder.field("desired", desired);
        }
        builder.field("transport_address", node.getAddress().toString());
        if (node.getAttributes().isEmpty() == false) {
            builder.startObject(outerObjectWritten ? "attributes" : "node_attributes");
            for (Map.Entry<String, String> entry : node.getAttributes().entrySet()) {
                builder.field(entry.getKey(), entry.getValue());
            }
            builder.endObject();
        }
        builder.startArray("roles");
        for (DiscoveryNodeRole role : node.getRoles()) {
            builder.value(role.roleName());
        }
        builder.endArray();
        return builder;
    }

    /**
     * Sorts a list of node level decisions by the decision type, then by weight ranking, and finally by node id.
     */
    public static List<NodeAllocationResult> sortNodeDecisions(List<NodeAllocationResult> nodeDecisions) {
        return nodeDecisions.stream().sorted().toList();
    }

    /**
     * Generates X-Content chunks for the node-level decisions, creating the outer "node_allocation_decisions" object
     * in which they are serialized.
     */
    public static Iterator<ToXContent> nodeDecisionsToXContentChunked(List<NodeAllocationResult> nodeDecisions) {
        if (nodeDecisions == null || nodeDecisions.isEmpty()) {
            return Collections.emptyIterator();
        }

        return Iterators.concat(
            ChunkedToXContentHelper.startArray("node_allocation_decisions"),
            nodeDecisions.iterator(),
            ChunkedToXContentHelper.endArray()
        );
    }

    /**
     * Returns {@code true} if there is at least one node that returned a {@link Type#YES} decision for allocating this shard.
     */
    protected boolean atLeastOneNodeWithYesDecision() {
        if (nodeDecisions == null) {
            return false;
        }
        for (NodeAllocationResult result : nodeDecisions) {
            if (result.getNodeDecision() == AllocationDecision.YES) {
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (other == null || other instanceof AbstractAllocationDecision == false) {
            return false;
        }
        AbstractAllocationDecision that = (AbstractAllocationDecision) other;
        return Objects.equals(targetNode, that.targetNode)
            && Objects.equals(targetNodeIsDesired, that.targetNodeIsDesired)
            && Objects.equals(nodeDecisions, that.nodeDecisions);
    }

    @Override
    public int hashCode() {
        return Objects.hash(targetNode, targetNodeIsDesired, nodeDecisions);
    }

    protected static List<NodeAllocationResult> decisionsWithDesiredNodes(
        List<NodeAllocationResult> decisions,
        Set<String> desiredNodeIds
    ) {
        return decisions != null
            ? decisions.stream().map(decision -> decision.withDesired(nodeIsDesired(decision.getNode(), desiredNodeIds))).toList()
            : null;
    }

    protected static Boolean nodeIsDesired(@Nullable DiscoveryNode node, Set<String> desiredNodeIds) {
        return node == null ? null : desiredNodeIds.contains(node.getId());
    }
}
