/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0 and the Server Side Public License, v 1; you may not use this file except
 * in compliance with, at your election, the Elastic License 2.0 or the Server
 * Side Public License, v 1.
 */

package org.elasticsearch.cluster.service;

import org.elasticsearch.cluster.ClusterState;
import org.elasticsearch.cluster.ClusterStateAckListener;
import org.elasticsearch.cluster.ClusterStateTaskExecutor;
import org.elasticsearch.cluster.ClusterStateTaskListener;
import org.elasticsearch.core.Releasable;
import org.elasticsearch.core.TimeValue;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

public class TestMasterServiceTaskQueue<T extends ClusterStateTaskListener> implements MasterServiceTaskQueue<T> {

    private final ClusterStateTaskExecutor<T> executor;
    private final List<T> tasks = new ArrayList<>();

    public TestMasterServiceTaskQueue(ClusterStateTaskExecutor<T> executor) {
        this.executor = executor;
    }

    @Override
    public void submitTask(String source, T task, TimeValue timeout) {
        tasks.add(task);
    }

    public ClusterState processAllTasks(ClusterState clusterState) throws Exception {
        var tasksContexts = createTaskContexts(tasks);
        tasks.clear();
        return executor.execute(
            new ClusterStateTaskExecutor.BatchExecutionContext<>(
                clusterState,
                tasksContexts,
                () -> TestMasterServiceTaskQueue::dummyReleasable
            )
        );
    }

    private static void dummyReleasable() {}

    private List<? extends ClusterStateTaskExecutor.TaskContext<T>> createTaskContexts(List<T> tasks) {
        return tasks.stream().map(task -> new ClusterStateTaskExecutor.TaskContext<T>() {
            @Override
            public T getTask() {
                return task;
            }

            @Override
            public void success(Runnable onPublicationSuccess) {
                onPublicationSuccess.run();
            }

            @Override
            public void success(Consumer<ClusterState> publishedStateConsumer) {
                throw new UnsupportedOperationException("Operation is deprecated and should not be used");
            }

            @Override
            public void success(Runnable onPublicationSuccess, ClusterStateAckListener clusterStateAckListener) {
                onPublicationSuccess.run();
                clusterStateAckListener.onAllNodesAcked();
            }

            @Override
            public void success(Consumer<ClusterState> publishedStateConsumer, ClusterStateAckListener clusterStateAckListener) {
                throw new UnsupportedOperationException("Operation is deprecated and should not be used");
            }

            @Override
            public void onFailure(Exception failure) {

            }

            @Override
            public Releasable captureResponseHeaders() {
                return TestMasterServiceTaskQueue::dummyReleasable;
            }
        }).toList();
    }
}
