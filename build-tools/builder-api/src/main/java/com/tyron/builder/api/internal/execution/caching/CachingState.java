package com.tyron.builder.api.internal.execution.caching;

import com.google.common.collect.ImmutableList;
import com.tyron.builder.api.internal.Either;
import com.tyron.builder.api.internal.execution.history.BeforeExecutionState;
import com.tyron.builder.caching.BuildCacheKey;

import org.jetbrains.annotations.Nullable;

import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Function;

public class CachingState {
    public static final CachingState NOT_DETERMINED = disabledWithoutInputs(new CachingDisabledReason(CachingDisabledReasonCategory.UNKNOWN, "Cacheability was not determined"));

    private final Either<Enabled, Disabled> delegate;

    private CachingState(Either<Enabled, Disabled> delegate) {
        this.delegate = delegate;
    }

    public static CachingState enabled(BuildCacheKey key, BeforeExecutionState beforeExecutionState) {
        return new CachingState(Either.left(new Enabled(key, beforeExecutionState)));
    }

    public static CachingState disabled(ImmutableList<CachingDisabledReason> disabledReasons, @Nullable BuildCacheKey key, @Nullable BeforeExecutionState beforeExecutionState) {
        return new CachingState(Either.right(new Disabled(disabledReasons, key, beforeExecutionState)));
    }

    public static CachingState disabledWithoutInputs(CachingDisabledReason reason) {
        return disabled(ImmutableList.of(reason), null, null);
    }

    public Optional<Enabled> whenEnabled() {
        return delegate.getLeft();
    }

    public Optional<Disabled> whenDisabled() {
        return delegate.getRight();
    }

    public <T> T fold(Function<Enabled, T> enabled, Function<Disabled, T> disabled) {
        return delegate.fold(enabled, disabled);
    }

    public void apply(Consumer<Enabled> enabled, Consumer<Disabled> disabled) {
        delegate.apply(enabled, disabled);
    }

    /**
     * Caching state when caching is enabled for the work.
     */
    public static class Enabled {
        private final BuildCacheKey key;
        private final BeforeExecutionState beforeExecutionState;

        private Enabled(BuildCacheKey key, BeforeExecutionState beforeExecutionState) {
            this.key = key;
            this.beforeExecutionState = beforeExecutionState;
        }

        /**
         * The cache key.
         */
        public BuildCacheKey getKey() {
            return key;
        }

        /**
         * The captured state of the work's inputs.
         */
        public BeforeExecutionState getBeforeExecutionState() {
            return beforeExecutionState;
        }
    }

    /**
     * Caching state when caching is disabled for the work.
     */
    public static class Disabled {
        private final ImmutableList<CachingDisabledReason> disabledReasons;
        private final BuildCacheKey key;
        private final BeforeExecutionState beforeExecutionState;

        private Disabled(ImmutableList<CachingDisabledReason> disabledReasons, @Nullable BuildCacheKey key, @Nullable BeforeExecutionState beforeExecutionState) {
            this.disabledReasons = disabledReasons;
            this.key = key;
            this.beforeExecutionState = beforeExecutionState;
        }

        /**
         * Reasons for the caching to be disabled for the work, empty when enabled.
         */
        public ImmutableList<CachingDisabledReason> getDisabledReasons() {
            return disabledReasons;
        }

        /**
         * The cache key if a valid cache key could be built.
         *
         * Might be present even when caching is disabled.
         */
        public Optional<BuildCacheKey> getKey() {
            return Optional.ofNullable(key);
        }

        /**
         * Individual fingerprints for each of the work's inputs, if they were tracked.
         */
        public Optional<BeforeExecutionState> getBeforeExecutionState() {
            return Optional.ofNullable(beforeExecutionState);
        }
    }
}