package com.tyron.builder.internal.buildTree;

import com.tyron.builder.api.execution.MultipleBuildFailures;
import com.tyron.builder.api.internal.invocation.BuildAction;

import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Responsible for executing a {@link BuildAction} and generating the result.
 */
public interface BuildActionRunner {
    /**
     * Runs the given action, returning a result that describes the build outcome and the result that should be returned to the client.
     *
     * <p>Build failures should be packaged in the returned result, rather than thrown.
     */
    Result run(BuildAction action, BuildTreeLifecycleController buildController);

    /**
     * Packages up the result of a {@link BuildAction}, either success plus an optional result object, or failure.
     *
     * <p>Failures are represented using 2 exceptions: the build failure, which is the failure that the build completed with and that should be reported to the user (via logging), plus the client failure, which is the exception that should be forwarded to the client. Often these are the same, but may be different for specific combinations of action + failure.
     */
    class Result {
        private final boolean hasResult;
        private final Object result;
        private final Throwable buildFailure;
        private final Throwable clientFailure;
        private static final Result NOTHING = new Result(false, null, null, null);
        private static final Result NULL = new Result(true, null, null, null);

        private Result(boolean hasResult, Object result, Throwable buildFailure, Throwable clientFailure) {
            this.hasResult = hasResult;
            this.result = result;
            this.buildFailure = buildFailure;
            this.clientFailure = clientFailure;
        }

        public static Result nothing() {
            return NOTHING;
        }

        public static Result of(@Nullable Object result) {
            if (result == null) {
                return NULL;
            }
            return new Result(true, result, null, null);
        }

        public static Result failed(Throwable buildFailure) {
            return new Result(true, null, buildFailure, buildFailure);
        }

        public static Result failed(Throwable buildFailure, RuntimeException clientFailure) {
            return new Result(true, null, buildFailure, clientFailure);
        }

        /**
         * Is there a result, possibly a null object or a build failure?
         */
        public boolean hasResult() {
            return hasResult;
        }

        /**
         * Returns the result to send back to the client, possibly null.
         */
        @Nullable
        public Object getClientResult() {
            return result;
        }

        /**
         * Returns the failure to report in the build outcome.
         */
        @Nullable
        public Throwable getBuildFailure() {
            return buildFailure;
        }

        /**
         * Returns the failure to send back to the client.
         */
        @Nullable
        public Throwable getClientFailure() {
            return clientFailure;
        }

        /**
         * Returns a copy of this result adding the given failures.
         */
        public Result addFailures(List<Throwable> failures) {
            if (failures.isEmpty()) {
                return this;
            }
            List<Throwable> newFailures = new ArrayList<>(1 + failures.size());
            if (buildFailure != null) {
                if (buildFailure instanceof MultipleBuildFailures) {
                    MultipleBuildFailures multipleBuildFailures = (MultipleBuildFailures) buildFailure;
                    newFailures.addAll(multipleBuildFailures.getCauses());
                } else {
                    newFailures.add(buildFailure);
                }
            }
            newFailures.addAll(failures);
            if (newFailures.size() == 1) {
                return failed(newFailures.get(0));
            } else {
                return failed(new MultipleBuildFailures(newFailures));
            }
        }

        /**
         * Returns a copy of this result adding the given failure.
         */
        public Result addFailure(Throwable t) {
            return addFailures(Collections.singletonList(t));
        }
    }
}