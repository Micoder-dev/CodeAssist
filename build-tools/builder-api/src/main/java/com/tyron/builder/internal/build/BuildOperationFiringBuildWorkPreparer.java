package com.tyron.builder.internal.build;


import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;

import static java.util.Collections.newSetFromMap;
import static java.util.Objects.requireNonNull;

import com.google.common.collect.Collections2;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.tyron.builder.api.Task;
import com.tyron.builder.api.execution.plan.ExecutionPlan;
import com.tyron.builder.api.execution.plan.LocalTaskNode;
import com.tyron.builder.api.execution.plan.Node;
import com.tyron.builder.api.execution.plan.TaskNode;
import com.tyron.builder.api.internal.GradleInternal;
import com.tyron.builder.api.internal.operations.BuildOperationContext;
import com.tyron.builder.api.internal.operations.BuildOperationDescriptor;
import com.tyron.builder.api.internal.operations.BuildOperationExecutor;
import com.tyron.builder.api.internal.operations.RunnableBuildOperation;
import com.tyron.builder.api.internal.project.taskfactory.TaskIdentity;
import com.tyron.builder.initialization.DefaultPlannedTask;
import com.tyron.builder.internal.taskgraph.CalculateTaskGraphBuildOperationType;

@SuppressWarnings({"Guava"})
public class BuildOperationFiringBuildWorkPreparer implements BuildWorkPreparer {
    private final BuildOperationExecutor buildOperationExecutor;
    private final BuildWorkPreparer delegate;

    public BuildOperationFiringBuildWorkPreparer(BuildOperationExecutor buildOperationExecutor, BuildWorkPreparer delegate) {
        this.buildOperationExecutor = buildOperationExecutor;
        this.delegate = delegate;
    }

    @Override
    public ExecutionPlan newExecutionPlan() {
        return delegate.newExecutionPlan();
    }

    @Override
    public void populateWorkGraph(GradleInternal gradle, ExecutionPlan plan, Consumer<? super ExecutionPlan> action) {
        buildOperationExecutor.run(new PopulateWorkGraph(gradle, plan, delegate, action));
    }

    @Override
    public void finalizeWorkGraph(GradleInternal gradle, ExecutionPlan plan) {
        delegate.finalizeWorkGraph(gradle, plan);
    }

    private static class PopulateWorkGraph implements RunnableBuildOperation {
        private final GradleInternal gradle;
        private final ExecutionPlan plan;
        private final BuildWorkPreparer delegate;
        private final Consumer<? super ExecutionPlan> action;

        public PopulateWorkGraph(GradleInternal gradle, ExecutionPlan plan, BuildWorkPreparer delegate, Consumer<? super ExecutionPlan> action) {
            this.gradle = gradle;
            this.plan = plan;
            this.delegate = delegate;
            this.action = action;
        }

        @Override
        public void run(BuildOperationContext buildOperationContext) {
            populateTaskGraph();

            // create copy now - https://github.com/gradle/gradle/issues/12527
            Set<Task> requestedTasks = plan.getRequestedTasks();
            Set<Task> filteredTasks = plan.getFilteredTasks();
            List<Node> scheduledWork = plan.getScheduledNodes();

            buildOperationContext.setResult(new CalculateTaskGraphBuildOperationType.Result() {
                @Override
                public List<String> getRequestedTaskPaths() {
                    return toTaskPaths(requestedTasks);
                }

                @Override
                public List<String> getExcludedTaskPaths() {
                    return toTaskPaths(filteredTasks);
                }

                @Override
                public List<CalculateTaskGraphBuildOperationType.PlannedTask> getTaskPlan() {
                    return toPlannedTasks(scheduledWork);
                }

                private List<CalculateTaskGraphBuildOperationType.PlannedTask> toPlannedTasks(List<Node> scheduledWork) {
                    return FluentIterable.from(scheduledWork)
                            .filter(LocalTaskNode.class)
                            .transform(this::toPlannedTask)
                            .toList();
                }

                private CalculateTaskGraphBuildOperationType.PlannedTask toPlannedTask(LocalTaskNode taskNode) {
                    TaskIdentity<?> taskIdentity = taskNode.getTask().getTaskIdentity();
                    return new DefaultPlannedTask(
                            new PlannedTaskIdentity(taskIdentity),
                            taskIdentifiesOf(taskNode.getDependencySuccessors(), Node::getDependencySuccessors),
                            taskIdentifiesOf(taskNode.getMustSuccessors()),
                            taskIdentifiesOf(taskNode.getShouldSuccessors()),
                            taskIdentifiesOf(taskNode.getFinalizers())
                    );
                }
            });
        }

        void populateTaskGraph() {
            delegate.populateWorkGraph(gradle, plan, action);
        }

        @Override
        public BuildOperationDescriptor.Builder description() {
            //noinspection Convert2Lambda
            return BuildOperationDescriptor.displayName(gradle.contextualize("Calculate task graph"))
                    .details(new CalculateTaskGraphBuildOperationType.Details() {
                        @Override
                        public String getBuildPath() {
                            return gradle.getIdentityPath().getPath();
                        }
                    });
        }
    }

    private static List<CalculateTaskGraphBuildOperationType.TaskIdentity> taskIdentifiesOf(Collection<Node> nodes, Function<? super Node, ? extends Collection<Node>> traverser) {
        if (nodes.isEmpty()) {
            return Collections.emptyList();
        }
        List<CalculateTaskGraphBuildOperationType.TaskIdentity> list = new ArrayList<>();
        traverseNonTasks(nodes, traverser, newSetFromMap(new IdentityHashMap<>()))
                .forEach(taskNode -> list.add(toIdentity(taskNode)));
        return list;
    }

    private static Iterable<TaskNode> traverseNonTasks(Collection<Node> nodes, Function<? super Node, ? extends Collection<Node>> traverser, Set<Node> seen) {
        if (nodes.isEmpty()) {
            return Collections.emptyList();
        }
        return FluentIterable.from(nodes)
                .filter(seen::add)
                .transformAndConcat(
                        node -> node instanceof TaskNode
                                ? ImmutableSet.of((TaskNode) node)
                                : traverseNonTasks(requireNonNull(traverser.apply(node)), traverser, seen)
                );
    }

    private static List<CalculateTaskGraphBuildOperationType.TaskIdentity> taskIdentifiesOf(Collection<Node> nodes) {
        if (nodes.isEmpty()) {
            return Collections.emptyList();
        }
        return FluentIterable.from(nodes)
                .filter(TaskNode.class)
                .transform(BuildOperationFiringBuildWorkPreparer::toIdentity)
                .toList();
    }

    private static CalculateTaskGraphBuildOperationType.TaskIdentity toIdentity(TaskNode n) {
        return new PlannedTaskIdentity(n.getTask().getTaskIdentity());
    }

    private static List<String> toTaskPaths(Set<Task> tasks) {
        return ImmutableSortedSet.copyOf(Collections2.transform(tasks, Task::getPath)).asList();
    }

    private static class PlannedTaskIdentity implements CalculateTaskGraphBuildOperationType.TaskIdentity {

        private final TaskIdentity<?> delegate;

        public PlannedTaskIdentity(TaskIdentity<?> delegate) {
            this.delegate = delegate;
        }

        @Override
        public String getBuildPath() {
            return delegate.getBuildPath();
        }

        @Override
        public String getTaskPath() {
            return delegate.getTaskPath();
        }

        @Override
        public long getTaskId() {
            return delegate.getId();
        }
    }
}