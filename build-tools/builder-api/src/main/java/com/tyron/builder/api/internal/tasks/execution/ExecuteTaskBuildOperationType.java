package com.tyron.builder.api.internal.tasks.execution;

import com.tyron.builder.api.internal.operations.BuildOperationType;
import com.tyron.builder.api.internal.project.taskfactory.TaskIdentity;
import com.tyron.builder.api.tasks.TaskState;
import com.tyron.builder.api.work.InputChanges;

import javax.annotation.Nullable;
import java.util.List;

/**
 * The overall execution of a task, including:
 *
 * - User lifecycle callbacks (TaskExecutionListener.beforeExecute and TaskExecutionListener.afterExecute)
 * - Gradle execution mechanics (e.g. up-to-date and build cache “checks”, property validation, build cache output store, etc.)
 *
 * That is, this operation does not represent just the execution of task actions.
 *
 * This operation can fail _and_ yield a result.
 * If the operation gets as far as invoking the task executer
 * (i.e. beforeTask callbacks did not fail), then a result is expected.
 * If the task execution fails, or if afterTask callbacks fail, an operation failure is expected _in addition_.
 */
public final class ExecuteTaskBuildOperationType implements BuildOperationType<ExecuteTaskBuildOperationType.Details, ExecuteTaskBuildOperationType.Result> {

    public interface Details {

        String getBuildPath();

        String getTaskPath();

        /**
         * @see TaskIdentity#uniqueId
         */
        long getTaskId();

        Class<?> getTaskClass();

    }

    public interface Result {

        /**
         * The message describing why the task was skipped.
         *
         * Expected to be {@link TaskState#getSkipMessage()}.
         */
        @Nullable
        String getSkipMessage();

        /**
         * Whether the task had any actions.
         * See {@link TaskStateInternal#isActionable()}.
         */
        boolean isActionable();

        /**
         * If task was UP_TO_DATE or FROM_CACHE, this will convey the ID of the build that produced the outputs being reused.
         * Value will be null for any other outcome.
         *
         * This value may also be null for an UP_TO_DATE outcome where the task executed, but then decided it was UP_TO_DATE.
         * That is, it was not UP_TO_DATE due to Gradle's core input/output incremental build mechanism.
         * This is not necessarily ideal behaviour, but it is the current.
         */
        @Nullable
        String getOriginBuildInvocationId();

        /**
         * If task was UP_TO_DATE or FROM_CACHE, this will convey the execution time of the task in the build that produced the outputs being reused.
         * Value will be null for any other outcome.
         *
         * This value may also be null for an UP_TO_DATE outcome where the task executed, but then decided it was UP_TO_DATE.
         * That is, it was not UP_TO_DATE due to Gradle's core input/output incremental build mechanism.
         * This is not necessarily ideal behaviour, but it is the current.
         *
         * @since 4.5
         */
        @Nullable
        Long getOriginExecutionTime();

        /**
         * The human friendly description of why this task was not cacheable.
         * Null if the task was cacheable.
         * Not null if {@link #getCachingDisabledReasonCategory()} is not null.
         */
        @Nullable
        String getCachingDisabledReasonMessage();

        /**
         * The categorisation of the why the task was not cacheable.
         * Null if the task was cacheable.
         * Not null if {@link #getCachingDisabledReasonMessage()}l is not null.
         * Values are expected to correlate to {@link TaskOutputCachingDisabledReasonCategory}.
         */
        @Nullable
        String getCachingDisabledReasonCategory();

        /**
         * Opaque messages describing why the task was not up to date.
         * In the order emitted by Gradle.
         * Null if execution did not get so far as to test “up-to-date-ness”.
         * Empty if tested, but task was considered up to date.
         */
        @Nullable
        List<String> getUpToDateMessages();

        /**
         * Returns if this task was executed incrementally.
         *
         * @see InputChanges#isIncremental()
         */
//        @NotUsedByScanPlugin("used to report incrementality to TAPI progress listeners")
        boolean isIncremental();

    }

    private ExecuteTaskBuildOperationType() {

    }

}