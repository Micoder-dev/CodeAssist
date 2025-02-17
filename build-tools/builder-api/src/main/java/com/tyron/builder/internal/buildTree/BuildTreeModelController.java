package com.tyron.builder.internal.buildTree;

import com.tyron.builder.api.internal.GradleInternal;
import com.tyron.builder.api.internal.operations.RunnableBuildOperation;

import java.util.Collection;

public interface BuildTreeModelController {
    /**
     * Returns the mutable model, configuring if necessary.
     */
    GradleInternal getConfiguredModel();

//    ToolingModelScope locateBuilderForDefaultTarget(String modelName, boolean param);
//
//    ToolingModelScope locateBuilderForTarget(BuildState target, String modelName, boolean param);
//
//    ToolingModelScope locateBuilderForTarget(ProjectState target, String modelName, boolean param);

    boolean queryModelActionsRunInParallel();

    /**
     * Runs the given actions, possibly in parallel.
     */
    void runQueryModelActions(Collection<? extends RunnableBuildOperation> actions);
}