/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0 and the Server Side Public License, v 1; you may not use this file except
 * in compliance with, at your election, the Elastic License 2.0 or the Server
 * Side Public License, v 1.
 */

package org.elasticsearch.cluster.routing.allocation.allocator;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.elasticsearch.common.util.concurrent.AbstractRunnable;

import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import java.util.function.Predicate;

/**
 * Asynchronously runs some computation using at most one thread but expects the input value changes over time as it's running. Newer input
 * values are assumed to be fresher and trigger a recomputation. If a computation never starts before a fresher value arrives then it is
 * skipped.
 */
public class ContinuousComputation<T> {

    private static final Logger logger = LogManager.getLogger(ContinuousComputation.class);

    private final AtomicReference<T> enqueuedInput = new AtomicReference<>();
    private final Processor processor = new Processor();
    private final ExecutorService executorService;
    private final BiConsumer<T, Predicate<T>> action;

    /**
     * @param executorService the background executor service to use to run the computations. No more than one task is executed at once.
     */
    public ContinuousComputation(ExecutorService executorService, BiConsumer<T, Predicate<T>> action) {
        this.executorService = executorService;
        this.action = action;
    }

    /**
     * Called when the input value has changed. If no newer value is received then eventually either the computation will run on this value.
     */
    public void onNewInput(T input) {
        assert input != null;
        if (enqueuedInput.getAndSet(Objects.requireNonNull(input)) == null) {
            executorService.execute(processor);
        }
    }

    /**
     * @return {@code false} iff there are no active/enqueued computations
     */
    // exposed for tests
    boolean isActive() {
        return enqueuedInput.get() != null;
    }

    /**
     * @return {@code true} iff the given {@code input} is the latest known input.
     */
    protected boolean isFresh(T input) {
        return enqueuedInput.get() == input;
    }

    private class Processor extends AbstractRunnable {

        @Override
        public void onFailure(Exception e) {
            assert false : e;
        }

        @Override
        public void onRejection(Exception e) {
            // shutting down, just give up
            logger.debug("rejected", e);
        }

        @Override
        protected void doRun() throws Exception {
            final T input = enqueuedInput.get();
            assert input != null;

            action.accept(input, ContinuousComputation.this::isFresh);

            if (enqueuedInput.compareAndSet(input, null) == false) {
                executorService.execute(this);
            }
        }

        @Override
        public String toString() {
            return "ContinuousComputation$Processor[" + ContinuousComputation.this.action.toString() + "]";
        }
    }
}
