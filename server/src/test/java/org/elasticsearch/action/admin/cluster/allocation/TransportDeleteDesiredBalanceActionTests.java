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
import org.elasticsearch.action.admin.cluster.allocation.TransportDeleteDesiredBalanceAction.ResetDesiredBalanceClusterExecutor;
import org.elasticsearch.action.support.ActionFilters;
import org.elasticsearch.cluster.ClusterState;
import org.elasticsearch.cluster.ESAllocationTestCase;
import org.elasticsearch.cluster.metadata.IndexNameExpressionResolver;
import org.elasticsearch.cluster.routing.allocation.AllocationService;
import org.elasticsearch.cluster.routing.allocation.allocator.DesiredBalanceShardsAllocator;
import org.elasticsearch.cluster.routing.allocation.allocator.ShardsAllocator;
import org.elasticsearch.cluster.service.ClusterService;
import org.elasticsearch.cluster.service.TestMasterServiceTaskQueue;
import org.elasticsearch.common.Priority;
import org.elasticsearch.rest.RestStatus;
import org.elasticsearch.tasks.Task;
import org.elasticsearch.threadpool.ThreadPool;
import org.elasticsearch.transport.TransportService;
import org.mockito.ArgumentCaptor;

import static org.hamcrest.Matchers.equalTo;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class TransportDeleteDesiredBalanceActionTests extends ESAllocationTestCase {

    public void testReturnsErrorIfAllocatorIsNotDesiredBalanced() throws Exception {

        ActionListener<ActionResponse.Empty> listener = mock(ActionListener.class);

        new TransportDeleteDesiredBalanceAction(
            mock(TransportService.class),
            mock(ClusterService.class),
            mock(ThreadPool.class),
            mock(ActionFilters.class),
            mock(IndexNameExpressionResolver.class),
            mock(AllocationService.class),
            mock(ShardsAllocator.class)
        ).masterOperation(mock(Task.class), mock(DesiredBalanceRequest.class), ClusterState.EMPTY_STATE, listener);

        ArgumentCaptor<ResourceNotFoundException> exceptionArgumentCaptor = ArgumentCaptor.forClass(ResourceNotFoundException.class);
        verify(listener).onFailure(exceptionArgumentCaptor.capture());

        final var exception = exceptionArgumentCaptor.getValue();
        assertEquals("Desired balance allocator is not in use, no desired balance found", exception.getMessage());
        assertThat(exception.status(), equalTo(RestStatus.NOT_FOUND));
    }

    public void testDeleteDesiredBalance() throws Exception {

        ActionListener<ActionResponse.Empty> listener = mock(ActionListener.class);
        DesiredBalanceShardsAllocator allocator = mock(DesiredBalanceShardsAllocator.class);
        AllocationService allocationService = mock(AllocationService.class);

        var executor = new ResetDesiredBalanceClusterExecutor(allocationService, allocator);
        var queue = new TestMasterServiceTaskQueue<>(executor);
        ClusterService clusterService = mock(ClusterService.class);
        when(
            clusterService.createTaskQueue(eq("reset-desired-balance"), eq(Priority.NORMAL), any(ResetDesiredBalanceClusterExecutor.class))
        ).thenReturn(queue);

        var action = new TransportDeleteDesiredBalanceAction(
            mock(TransportService.class),
            clusterService,
            mock(ThreadPool.class),
            mock(ActionFilters.class),
            mock(IndexNameExpressionResolver.class),
            allocationService,
            allocator
        );

        action.masterOperation(mock(Task.class), mock(DesiredBalanceRequest.class), ClusterState.EMPTY_STATE, listener);
        queue.processAllTasks(ClusterState.EMPTY_STATE);

        verify(listener).onResponse(ActionResponse.Empty.INSTANCE);
        verify(allocator).resetDesiredBalance();
        verify(allocationService).reroute(any(), eq("reset-desired-balance"), any());
    }
}
