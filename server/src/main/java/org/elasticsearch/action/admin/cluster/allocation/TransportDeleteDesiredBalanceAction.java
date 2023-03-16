/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0 and the Server Side Public License, v 1; you may not use this file except
 * in compliance with, at your election, the Elastic License 2.0 or the Server
 * Side Public License, v 1.
 */

package org.elasticsearch.action.admin.cluster.allocation;

import org.elasticsearch.ResourceNotFoundException;
import org.elasticsearch.action.ActionListener;
import org.elasticsearch.action.ActionResponse;
import org.elasticsearch.action.support.ActionFilters;
import org.elasticsearch.action.support.master.TransportMasterNodeAction;
import org.elasticsearch.cluster.ClusterState;
import org.elasticsearch.cluster.ClusterStateTaskExecutor;
import org.elasticsearch.cluster.ClusterStateTaskListener;
import org.elasticsearch.cluster.block.ClusterBlockException;
import org.elasticsearch.cluster.block.ClusterBlockLevel;
import org.elasticsearch.cluster.metadata.IndexNameExpressionResolver;
import org.elasticsearch.cluster.routing.allocation.AllocationService;
import org.elasticsearch.cluster.routing.allocation.allocator.DesiredBalanceShardsAllocator;
import org.elasticsearch.cluster.routing.allocation.allocator.ShardsAllocator;
import org.elasticsearch.cluster.service.ClusterService;
import org.elasticsearch.cluster.service.MasterServiceTaskQueue;
import org.elasticsearch.common.Priority;
import org.elasticsearch.common.inject.Inject;
import org.elasticsearch.core.Nullable;
import org.elasticsearch.tasks.Task;
import org.elasticsearch.threadpool.ThreadPool;
import org.elasticsearch.transport.TransportService;

public class TransportDeleteDesiredBalanceAction extends TransportMasterNodeAction<DesiredBalanceRequest, ActionResponse.Empty> {

    private final MasterServiceTaskQueue<ResetDesiredBalanceTask> resetDesiredBalanceTaskQueue;

    @Inject
    public TransportDeleteDesiredBalanceAction(
        TransportService transportService,
        ClusterService clusterService,
        ThreadPool threadPool,
        ActionFilters actionFilters,
        IndexNameExpressionResolver indexNameExpressionResolver,
        AllocationService allocationService,
        ShardsAllocator shardsAllocator
    ) {
        super(
            DeleteDesiredBalanceAction.NAME,
            transportService,
            clusterService,
            threadPool,
            actionFilters,
            DesiredBalanceRequest::new,
            indexNameExpressionResolver,
            in -> ActionResponse.Empty.INSTANCE,
            ThreadPool.Names.MANAGEMENT
        );
        this.resetDesiredBalanceTaskQueue = shardsAllocator instanceof DesiredBalanceShardsAllocator
            ? clusterService.createTaskQueue(
                "reset-desired-balance",
                Priority.NORMAL,
                new ResetDesiredBalanceClusterExecutor(allocationService, shardsAllocator)
            )
            : null;
    }

    public record ResetDesiredBalanceTask(ActionListener<ActionResponse.Empty> listener) implements ClusterStateTaskListener {

        @Override
        public void onFailure(Exception e) {
            listener.onFailure(e);
        }
    }

    public static final class ResetDesiredBalanceClusterExecutor implements ClusterStateTaskExecutor<ResetDesiredBalanceTask> {

        private final AllocationService allocationService;
        @Nullable
        private final DesiredBalanceShardsAllocator desiredBalanceShardsAllocator;

        public ResetDesiredBalanceClusterExecutor(AllocationService allocationService, ShardsAllocator shardsAllocator) {
            this.allocationService = allocationService;
            this.desiredBalanceShardsAllocator = shardsAllocator instanceof DesiredBalanceShardsAllocator allocator ? allocator : null;
        }

        @Override
        public ClusterState execute(BatchExecutionContext<ResetDesiredBalanceTask> batchExecutionContext) {
            assert desiredBalanceShardsAllocator != null : "Should be executed only with DesiredBalanceShardsAllocator";
            var state = batchExecutionContext.initialState();
            desiredBalanceShardsAllocator.resetDesiredBalance();
            state = allocationService.reroute(state, "reset-desired-balance", ActionListener.noop());
            for (var taskContext : batchExecutionContext.taskContexts()) {
                taskContext.success(() -> taskContext.getTask().listener.onResponse(ActionResponse.Empty.INSTANCE));
            }
            return state;
        }
    }

    @Override
    protected void masterOperation(
        Task task,
        DesiredBalanceRequest request,
        ClusterState state,
        ActionListener<ActionResponse.Empty> listener
    ) throws Exception {
        if (resetDesiredBalanceTaskQueue == null) {
            listener.onFailure(new ResourceNotFoundException("Desired balance allocator is not in use, no desired balance found"));
            return;
        }
        resetDesiredBalanceTaskQueue.submitTask("reset-desired-balance", new ResetDesiredBalanceTask(listener), null);
    }

    @Override
    protected ClusterBlockException checkBlock(DesiredBalanceRequest request, ClusterState state) {
        return state.blocks().globalBlockedException(ClusterBlockLevel.METADATA_READ);
    }
}
