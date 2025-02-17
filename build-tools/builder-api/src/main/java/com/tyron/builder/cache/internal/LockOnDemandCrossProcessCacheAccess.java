package com.tyron.builder.cache.internal;

import com.tyron.builder.api.Action;
import com.tyron.builder.api.internal.Factory;
import com.tyron.builder.api.internal.UncheckedException;
import com.tyron.builder.cache.FileLock;
import com.tyron.builder.cache.FileLockManager;
import com.tyron.builder.cache.FileLockReleasedSignal;
import com.tyron.builder.cache.LockOptions;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.util.concurrent.locks.Lock;

class LockOnDemandCrossProcessCacheAccess extends AbstractCrossProcessCacheAccess {
    private static final Logger LOGGER = LoggerFactory.getLogger(LockOnDemandCrossProcessCacheAccess.class);
    private final String cacheDisplayName;
    private final File lockTarget;
    private final LockOptions lockOptions;
    private final FileLockManager lockManager;
    private final Lock stateLock;
    private final Action<FileLock> onOpen;
    private final Action<FileLock> onClose;
    private final Runnable unlocker;
    private final Action<FileLockReleasedSignal> whenContended;
    private int lockCount;
    private FileLock fileLock;
    private CacheInitializationAction initAction;
    private FileLockReleasedSignal lockReleaseSignal;

    /**
     * Actions are notified when lock is opened or closed. Actions are called while holding state lock, so that no other threads are working with cache while these are running.
     *
     * @param stateLock Lock to hold while mutating state.
     * @param onOpen Action to run when the lock is opened. Action is called while holding state lock
     * @param onClose Action to run when the lock is closed. Action is called while holding state lock
     */
    public LockOnDemandCrossProcessCacheAccess(String cacheDisplayName, File lockTarget, LockOptions lockOptions, FileLockManager lockManager, Lock stateLock, CacheInitializationAction initAction, Action<FileLock> onOpen, Action<FileLock> onClose) {
        this.cacheDisplayName = cacheDisplayName;
        this.lockTarget = lockTarget;
        this.lockOptions = lockOptions;
        this.lockManager = lockManager;
        this.stateLock = stateLock;
        this.initAction = initAction;
        this.onOpen = onOpen;
        this.onClose = onClose;
        unlocker = new UnlockAction();
        whenContended = new ContendedAction();
    }

    @Override
    public void open() {
        // Don't need to do anything
    }

    @Override
    public void close() {
        stateLock.lock();
        try {
            if (lockCount != 0) {
                throw new IllegalStateException(String.format("Cannot close cache access for %s as it is currently in use for %s operations.", cacheDisplayName, lockCount));
            }
            releaseLockIfHeld();
        } finally {
            stateLock.unlock();
        }
    }

    @Override
    public <T> T withFileLock(Factory<T> factory) {
        incrementLockCount();
        try {
            return factory.create();
        } finally {
            decrementLockCount();
        }
    }

    private void incrementLockCount() {
        stateLock.lock();
        try {
            if (fileLock == null) {
                if (lockCount != 0) {
                    throw new IllegalStateException("Mismatched lock count.");
                }
                if (LOGGER.isDebugEnabled()) {
                    LOGGER.debug("Acquiring file lock for " + cacheDisplayName);
                }
                fileLock = lockManager.lock(lockTarget, lockOptions, cacheDisplayName, "", whenContended);
                try {
                    if (initAction.requiresInitialization(fileLock)) {
                        fileLock.writeFile(new Runnable() {
                            @Override
                            public void run() {
                                initAction.initialize(fileLock);
                            }
                        });
                    }
                    onOpen.execute(fileLock);
                } catch (Exception e) {
                    fileLock.close();
                    fileLock = null;
                    throw UncheckedException.throwAsUncheckedException(e);
                }
            }
            lockCount++;
        } finally {
            stateLock.unlock();
        }
    }

    private void decrementLockCount() {
        stateLock.lock();
        try {
            if (lockCount <= 0 || fileLock == null) {
                throw new IllegalStateException("Mismatched lock count.");
            }
            lockCount--;
            if (lockCount == 0 && lockReleaseSignal != null) {
                releaseLockIfHeld();
            } // otherwise, keep lock open
        } finally {
            stateLock.unlock();
        }
    }

    private void releaseLockIfHeld() {
        if (fileLock == null) {
            return;
        }
        if (LOGGER.isDebugEnabled()) {
            LOGGER.debug("Releasing file lock for " + cacheDisplayName);
        }
        try {
            onClose.execute(fileLock);
        } finally {
            try {
                fileLock.close();
                fileLock = null;
            } finally {
                if (lockReleaseSignal != null) {
                    lockReleaseSignal.trigger();
                    lockReleaseSignal = null;
                }
            }
        }
    }

    @Override
    public Runnable acquireFileLock() {
        incrementLockCount();
        return unlocker;
    }

    private class ContendedAction implements Action<FileLockReleasedSignal> {
        @Override
        public void execute(FileLockReleasedSignal signal) {
            stateLock.lock();
            try {
                if (lockCount == 0) {
                    LOGGER.debug("Lock on " + cacheDisplayName + " requested by another process - releasing lock.");
                    releaseLockIfHeld();
                    signal.trigger();
                } else {
                    // Lock is in use - mark as contended
                    LOGGER.debug("Lock on " + cacheDisplayName + " requested by another process - lock is in use and will be released when operation completed.");
                    lockReleaseSignal = signal;
                }
            } finally {
                stateLock.unlock();
            }
        }
    }

    private class UnlockAction implements Runnable {
        @Override
        public void run() {
            decrementLockCount();
        }
    }
}