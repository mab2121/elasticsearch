/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */
package org.elasticsearch.xpack.ilm;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.elasticsearch.ExceptionsHelper;
import org.elasticsearch.cluster.ClusterState;
import org.elasticsearch.cluster.NotMasterException;
import org.elasticsearch.cluster.ProjectState;
import org.elasticsearch.cluster.coordination.FailedToCommitClusterStateException;
import org.elasticsearch.cluster.metadata.IndexMetadata;
import org.elasticsearch.cluster.metadata.LifecycleExecutionState;
import org.elasticsearch.cluster.metadata.ProjectId;
import org.elasticsearch.common.Strings;
import org.elasticsearch.index.Index;
import org.elasticsearch.xpack.core.ilm.Step;

import java.util.Objects;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

public class MoveToErrorStepUpdateTask extends IndexLifecycleClusterStateUpdateTask {

    private static final Logger logger = LogManager.getLogger(MoveToErrorStepUpdateTask.class);

    private final Index index;
    private final String policy;
    private final Step.StepKey currentStepKey;
    private final BiFunction<IndexMetadata, Step.StepKey, Step> stepLookupFunction;
    private final Consumer<ProjectState> stateChangeConsumer;
    private final LongSupplier nowSupplier;
    private final Exception cause;

    public MoveToErrorStepUpdateTask(
        ProjectId projectId,
        Index index,
        String policy,
        Step.StepKey currentStepKey,
        Exception cause,
        LongSupplier nowSupplier,
        BiFunction<IndexMetadata, Step.StepKey, Step> stepLookupFunction,
        Consumer<ProjectState> stateChangeConsumer
    ) {
        super(projectId, index, currentStepKey);
        this.index = index;
        this.policy = policy;
        this.currentStepKey = currentStepKey;
        this.cause = cause;
        this.nowSupplier = nowSupplier;
        this.stepLookupFunction = stepLookupFunction;
        this.stateChangeConsumer = stateChangeConsumer;
    }

    @Override
    protected ClusterState doExecute(ProjectState currentState) throws Exception {
        IndexMetadata idxMeta = currentState.metadata().index(index);
        if (idxMeta == null) {
            // Index must have been since deleted, ignore it
            return currentState.cluster();
        }
        LifecycleExecutionState lifecycleState = idxMeta.getLifecycleExecutionState();
        if (policy.equals(idxMeta.getLifecyclePolicyName()) && currentStepKey.equals(Step.getCurrentStepKey(lifecycleState))) {
            return currentState.updatedState(
                IndexLifecycleTransition.moveIndexToErrorStep(index, currentState.metadata(), cause, nowSupplier, stepLookupFunction)
            );
        } else {
            // either the policy has changed or the step is now
            // not the same as when we submitted the update task. In
            // either case we don't want to do anything now
            return currentState.cluster();
        }
    }

    @Override
    public void onClusterStateProcessed(ProjectState newState) {
        stateChangeConsumer.accept(newState);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        MoveToErrorStepUpdateTask that = (MoveToErrorStepUpdateTask) o;
        // We don't have a stable equals on the cause and shouldn't have simultaneous moves to error step to begin with when deduplicating
        // tasks so we only compare the current state here and in the hashcode.
        return index.equals(that.index) && policy.equals(that.policy) && currentStepKey.equals(that.currentStepKey);
    }

    @Override
    public int hashCode() {
        return Objects.hash(index, policy, currentStepKey);
    }

    @Override
    protected void handleFailure(Exception e) {
        Level level;
        if (ExceptionsHelper.unwrap(e, NotMasterException.class, FailedToCommitClusterStateException.class) != null) {
            level = Level.DEBUG;
        } else {
            level = Level.ERROR;
            assert false : new AssertionError("unexpected exception", e);
        }
        logger.log(
            level,
            () -> Strings.format(
                "policy [%s] for index [%s] failed trying to move from step [%s] to the ERROR step.",
                policy,
                index.getName(),
                currentStepKey
            )
        );
    }
}
