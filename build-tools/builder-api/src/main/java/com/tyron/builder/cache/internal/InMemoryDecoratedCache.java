package com.tyron.builder.cache.internal;

import com.google.common.cache.Cache;
import com.google.common.util.concurrent.Runnables;
import com.google.common.util.concurrent.UncheckedExecutionException;
import com.tyron.builder.api.internal.Cast;
import com.tyron.builder.api.internal.UncheckedException;
import com.tyron.builder.cache.FileLock;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicReference;

import java.util.concurrent.ExecutionException;
import java.util.function.Function;

class InMemoryDecoratedCache<K, V> implements MultiProcessSafeAsyncPersistentIndexedCache<K, V>, InMemoryCacheController {
    private final static Logger LOG = LoggerFactory.getLogger(InMemoryDecoratedCache.class);
    private final static Object NULL = new Object();
    private final MultiProcessSafeAsyncPersistentIndexedCache<K, V> delegate;
    private final Cache<Object, Object> inMemoryCache;
    private final String cacheId;
    private final AtomicReference<FileLock.State> fileLockStateReference;

    public InMemoryDecoratedCache(MultiProcessSafeAsyncPersistentIndexedCache<K, V> delegate, Cache<Object, Object> inMemoryCache, String cacheId, AtomicReference<FileLock.State> fileLockStateReference) {
        this.delegate = delegate;
        this.inMemoryCache = inMemoryCache;
        this.cacheId = cacheId;
        this.fileLockStateReference = fileLockStateReference;
    }

    @Override
    public String toString() {
        return "{in-memory-cache cache: " + delegate + "}";
    }

    @Override
    public V get(final K key) {
        Object value;
        try {
            value = inMemoryCache.get(key, () -> {
                Object out = delegate.get(key);
                return out == null ? NULL : out;
            });
        } catch (UncheckedExecutionException | ExecutionException e) {
            throw UncheckedException.throwAsUncheckedException(e.getCause());
        }
        if (value == NULL) {
            return null;
        } else {
            return Cast.uncheckedCast(value);
        }
    }

    @Override
    public V get(final K key, final Function<? super K, ? extends V> producer, final Runnable completion) {
        final AtomicReference<Runnable> completionRef = new AtomicReference<>(completion);
        Object value;
        try {
            value = inMemoryCache.getIfPresent(key);
            final boolean wasNull = value == NULL;
            if (wasNull) {
                inMemoryCache.invalidate(key);
            } else if (value != null) {
                return Cast.uncheckedCast(value);
            }
            value = inMemoryCache.get(key, () -> {
                if (!wasNull) {
                    Object out = delegate.get(key);
                    if (out != null) {
                        return out;
                    }
                }
                V generatedValue = producer.apply(key);
                delegate.putLater(key, generatedValue, completion);
                completionRef.set(Runnables.doNothing());
                return generatedValue;
            });
        } catch (UncheckedExecutionException | ExecutionException e) {
            throw UncheckedException.throwAsUncheckedException(e.getCause());
        } finally {
            completionRef.get().run();
        }
        if (value == NULL) {
            return null;
        } else {
            return Cast.uncheckedCast(value);
        }
    }

    @Override
    public void putLater(K key, V value, Runnable completion) {
        inMemoryCache.put(key, value);
        delegate.putLater(key, value, completion);
    }

    @Override
    public void removeLater(K key, Runnable completion) {
        inMemoryCache.put(key, NULL);
        delegate.removeLater(key, completion);
    }

    @Override
    public void afterLockAcquire(FileLock.State currentCacheState) {
        boolean outOfDate = false;
        FileLock.State previousState = fileLockStateReference.get();
        if (previousState == null) {
            outOfDate = true;
        } else if (currentCacheState.hasBeenUpdatedSince(previousState)) {
            LOG.debug("Invalidating in-memory cache of " + cacheId);
            outOfDate = true;
        }
        if (outOfDate) {
            inMemoryCache.invalidateAll();
        }
        delegate.afterLockAcquire(currentCacheState);
    }

    @Override
    public void finishWork() {
        delegate.finishWork();
    }

    @Override
    public void beforeLockRelease(FileLock.State currentCacheState) {
        fileLockStateReference.set(currentCacheState);
        delegate.beforeLockRelease(currentCacheState);
    }

    @Override
    public String getCacheId() {
        return cacheId;
    }

    @Override
    public void clearInMemoryCache() {
        inMemoryCache.invalidateAll();
    }
}