package com.tyron.builder.cache.internal;

import static com.tyron.builder.api.internal.UncheckedException.throwAsUncheckedException;

import com.google.common.annotations.VisibleForTesting;
import com.tyron.builder.api.Action;
import com.tyron.builder.api.internal.Factory;
import com.tyron.builder.api.internal.concurrent.CompositeStoppable;
import com.tyron.builder.api.internal.concurrent.Stoppable;
import com.tyron.builder.api.internal.id.IdGenerator;
import com.tyron.builder.api.internal.id.RandomLongIdGenerator;
import com.tyron.builder.api.util.GFileUtils;
import com.tyron.builder.cache.FileIntegrityViolationException;
import com.tyron.builder.cache.FileLock;
import com.tyron.builder.cache.FileLockManager;
import com.tyron.builder.cache.FileLockReleasedSignal;
import com.tyron.builder.cache.InsufficientLockModeException;
import com.tyron.builder.cache.LockOptions;
import com.tyron.builder.cache.LockTimeoutException;
import com.tyron.builder.cache.internal.filelock.DefaultLockStateSerializer;
import com.tyron.builder.cache.internal.filelock.FileLockOutcome;
import com.tyron.builder.cache.internal.filelock.LockFileAccess;
import com.tyron.builder.cache.internal.filelock.LockInfo;
import com.tyron.builder.cache.internal.filelock.LockState;
import com.tyron.builder.cache.internal.filelock.LockStateAccess;
import com.tyron.builder.cache.internal.filelock.LockStateSerializer;
import com.tyron.builder.cache.internal.filelock.Version1LockStateSerializer;
import com.tyron.builder.cache.internal.locklistener.FileLockContentionHandler;
import com.tyron.builder.internal.io.ExponentialBackoff;
import com.tyron.builder.internal.io.IOQuery;

import java.io.File;
import java.io.IOException;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import static java.util.concurrent.TimeUnit.MILLISECONDS;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Uses file system locks on a lock file per target file.
 */
public class DefaultFileLockManager implements FileLockManager {
    private static final Logger LOGGER = LoggerFactory.getLogger(DefaultFileLockManager.class);
    public static final int DEFAULT_LOCK_TIMEOUT = 60000;

    private final Set<File> lockedFiles = new CopyOnWriteArraySet<>();
    private final ProcessMetaDataProvider metaDataProvider;
    private final int lockTimeoutMs;
    private final IdGenerator<Long> generator;
    private final FileLockContentionHandler fileLockContentionHandler;
    private final int shortTimeoutMs = 10000;

    public DefaultFileLockManager(ProcessMetaDataProvider metaDataProvider, FileLockContentionHandler fileLockContentionHandler) {
        this(metaDataProvider, DEFAULT_LOCK_TIMEOUT, fileLockContentionHandler);
    }

    public DefaultFileLockManager(ProcessMetaDataProvider metaDataProvider, int lockTimeoutMs, FileLockContentionHandler fileLockContentionHandler) {
        this(metaDataProvider, lockTimeoutMs, fileLockContentionHandler, new RandomLongIdGenerator());
    }

    DefaultFileLockManager(ProcessMetaDataProvider metaDataProvider, int lockTimeoutMs, FileLockContentionHandler fileLockContentionHandler,
                           IdGenerator<Long> generator) {
        this.metaDataProvider = metaDataProvider;
        this.lockTimeoutMs = lockTimeoutMs;
        this.fileLockContentionHandler = fileLockContentionHandler;
        this.generator = generator;
    }

    @Override
    public FileLock lock(File target, LockOptions options, String targetDisplayName) throws LockTimeoutException {
        return lock(target, options, targetDisplayName, "");
    }

    @Override
    public FileLock lock(File target, LockOptions options, String targetDisplayName, String operationDisplayName) {
        return lock(target, options, targetDisplayName, operationDisplayName, null);
    }

    @Override
    public FileLock lock(File target, LockOptions options, String targetDisplayName, String operationDisplayName, Action<FileLockReleasedSignal> whenContended) {
        if (options.getMode() == LockMode.OnDemand) {
            throw new UnsupportedOperationException(String.format("No %s mode lock implementation available.", options));
        }
        File canonicalTarget = GFileUtils.canonicalize(target);
        if (!lockedFiles.add(canonicalTarget)) {
            throw new IllegalStateException(String.format("Cannot lock %s as it has already been locked by this process.", targetDisplayName));
        }
        try {
            int port = fileLockContentionHandler.reservePort();
            return new DefaultFileLock(canonicalTarget, options, targetDisplayName, operationDisplayName, port, whenContended);
        } catch (Throwable t) {
            lockedFiles.remove(canonicalTarget);
            throw throwAsUncheckedException(t);
        }
    }

    static File determineLockTargetFile(File target) {
        if (target.isDirectory()) {
            return new File(target, target.getName() + ".lock");
        } else {
            return new File(target.getParentFile(), target.getName() + ".lock");
        }
    }

    private class DefaultFileLock extends AbstractFileAccess implements FileLock {
        private final File lockFile;
        private final File target;
        private final LockMode mode;
        private final String displayName;
        private final String operationDisplayName;
        private java.nio.channels.FileLock lock;
        private LockFileAccess lockFileAccess;
        private LockState lockState;
        private final int port;
        private final long lockId;

        public DefaultFileLock(File target, LockOptions options, String displayName, String operationDisplayName, int port, Action<FileLockReleasedSignal> whenContended) throws Throwable {
            this.port = port;
            this.lockId = generator.generateId();
            if (options.getMode() == LockMode.OnDemand) {
                throw new UnsupportedOperationException("Locking mode OnDemand is not supported.");
            }

            this.target = target;

            this.displayName = displayName;
            this.operationDisplayName = operationDisplayName;
            this.lockFile = determineLockTargetFile(target);

            GFileUtils.mkdirs(lockFile.getParentFile());
            try {
                lockFile.createNewFile();
            } catch (IOException e) {
                LOGGER.debug("Couldn't create lock file for " + lockFile);
                throw e;
            }

            LockStateSerializer stateProtocol = options.isUseCrossVersionImplementation() ? new Version1LockStateSerializer() : new DefaultLockStateSerializer();
            lockFileAccess = new LockFileAccess(lockFile, new LockStateAccess(stateProtocol));
            try {
                if (whenContended != null) {
                    fileLockContentionHandler.start(lockId, whenContended);
                }
                lockState = lock(options.getMode());
            } catch (Throwable t) {
                // Also releases any locks
                lockFileAccess.close();
                throw t;
            }

            this.mode = lock.isShared() ? LockMode.Shared : LockMode.Exclusive;
        }

        @Override
        public boolean isLockFile(File file) {
            return file.equals(lockFile);
        }

        @Override
        public boolean getUnlockedCleanly() {
            assertOpen();
            return !lockState.isDirty();
        }

        @Override
        public State getState() {
            assertOpen();
            return lockState;
        }

        @Override
        public <T> T readFile(Factory<? extends T> action) throws LockTimeoutException, FileIntegrityViolationException {
            assertOpenAndIntegral();
            return action.create();
        }

        @Override
        public void updateFile(Runnable action) throws LockTimeoutException, FileIntegrityViolationException {
            assertOpenAndIntegral();
            doWriteAction(action);
        }

        @Override
        public void writeFile(Runnable action) throws LockTimeoutException {
            assertOpen();
            doWriteAction(action);
        }

        private void doWriteAction(Runnable action) {
            if (mode != LockMode.Exclusive) {
                throw new InsufficientLockModeException("An exclusive lock is required for this operation");
            }

            try {
                lockState = lockFileAccess.markDirty(lockState);
                action.run();
                lockState = lockFileAccess.markClean(lockState);
            } catch (Throwable t) {
                throw throwAsUncheckedException(t);
            }
        }

        private void assertOpen() {
            if (lock == null) {
                throw new IllegalStateException("This lock has been closed.");
            }
        }

        private void assertOpenAndIntegral() {
            assertOpen();
            if (lockState.isDirty()) {
                throw new FileIntegrityViolationException(String.format("The file '%s' was not unlocked cleanly", target));
            }
        }

        @Override
        public void close() {
            CompositeStoppable stoppable = new CompositeStoppable();
            stoppable.add(new Stoppable() {
                @Override
                public void stop() {
                    if (lockFileAccess == null) {
                        return;
                    }
                    try {
                        LOGGER.debug("Releasing lock on " + displayName);
                        try {
                            if (lock != null && !lock.isShared()) {
                                // Discard information region
                                FileLockOutcome lockOutcome;
                                try {
                                    lockOutcome = lockInformationRegion(LockMode.Exclusive, newExponentialBackoff(shortTimeoutMs));
                                } catch (InterruptedException e) {
                                    throw throwAsUncheckedException(e);
                                }
                                if (lockOutcome.isLockWasAcquired()) {
                                    try {
                                        lockFileAccess.clearLockInfo();
                                    } finally {
                                        lockOutcome.getFileLock().release();
                                    }
                                }
                            }
                        } finally {
                            lockFileAccess.close();
                        }
                    } catch (Exception e) {
                        throw new RuntimeException("Failed to release lock on " + displayName, e);
                    }
                }
            });
            stoppable.add(new Stoppable() {
                @Override
                public void stop() {
                    try {
                        fileLockContentionHandler.stop(lockId);
                    } catch (Exception e) {
                        throw new RuntimeException("Unable to stop listening for file lock requests for " + displayName, e);
                    }
                }
            });
            stoppable.add(new Stoppable() {
                @Override
                public void stop() {
                    lock = null;
                    lockFileAccess = null;
                    lockedFiles.remove(target);
                }
            });
            stoppable.stop();
        }

        @Override
        public LockMode getMode() {
            return mode;
        }

        private LockState lock(LockMode lockMode) throws Throwable {
            LOGGER.debug("Waiting to acquire " + lockMode.toString().toLowerCase() + " lock on " + displayName);

            // Lock the state region, with the requested mode
            FileLockOutcome lockOutcome = lockStateRegion(lockMode);
            if (!lockOutcome.isLockWasAcquired()) {
                com.tyron.builder.cache.internal.filelock.LockInfo lockInfo = readInformationRegion(newExponentialBackoff(shortTimeoutMs));
                throw timeoutException(displayName, operationDisplayName, lockFile, metaDataProvider.getProcessIdentifier(), lockOutcome, lockInfo);
            }

            java.nio.channels.FileLock stateRegionLock = lockOutcome.getFileLock();
            try {
                LockState lockState;
                if (!stateRegionLock.isShared()) {
                    // We have an exclusive lock (whether we asked for it or not).

                    // Update the state region
                    lockState = lockFileAccess.ensureLockState();

                    // Acquire an exclusive lock on the information region and write our details there
                    FileLockOutcome informationRegionLockOutcome = lockInformationRegion(LockMode.Exclusive, newExponentialBackoff(shortTimeoutMs));
                    if (!informationRegionLockOutcome.isLockWasAcquired()) {
                        throw new IllegalStateException(String.format("Unable to lock the information region for %s", displayName));
                    }
                    // check that the length of the reserved region is enough for storing our content
                    try {
                        lockFileAccess.writeLockInfo(port, lockId, metaDataProvider.getProcessIdentifier(), operationDisplayName);
                    } finally {
                        informationRegionLockOutcome.getFileLock().release();
                    }
                } else {
                    // Just read the state region
                    lockState = lockFileAccess.readLockState();
                }
                LOGGER.debug("Lock acquired on " + displayName);
                lock = stateRegionLock;
                return lockState;
            } catch (Throwable t) {
                stateRegionLock.release();
                throw t;
            }
        }

        private LockTimeoutException timeoutException(String lockDisplayName, String thisOperation, File lockFile, String thisProcessPid, FileLockOutcome fileLockOutcome, com.tyron.builder.cache.internal.filelock.LockInfo lockInfo) {
            if (fileLockOutcome == FileLockOutcome.LOCKED_BY_ANOTHER_PROCESS) {
                String message = String.format("Timeout waiting to lock %s. It is currently in use by another Gradle instance.%nOwner PID: %s%nOur PID: %s%nOwner Operation: %s%nOur operation: %s%nLock file: %s", lockDisplayName, lockInfo.pid, thisProcessPid, lockInfo.operation, thisOperation, lockFile);
                return new LockTimeoutException(message, lockFile);
            } else if (fileLockOutcome == FileLockOutcome.LOCKED_BY_THIS_PROCESS){
                String message = String.format("Timeout waiting to lock %s. It is currently in use by this Gradle process.Owner Operation: %s%nOur operation: %s%nLock file: %s", lockDisplayName, lockInfo.operation, thisOperation, lockFile);
                return new LockTimeoutException(message, lockFile);
            } else {
                throw new IllegalArgumentException("Unexpected lock outcome: " + fileLockOutcome);
            }
        }

        private LockInfo readInformationRegion(ExponentialBackoff<AwaitableFileLockReleasedSignal> backoff) throws IOException, InterruptedException {
            // Can't acquire lock, get details of owner to include in the error message
            LockInfo out = new LockInfo();
            FileLockOutcome lockOutcome = lockInformationRegion(LockMode.Shared, backoff);
            if (!lockOutcome.isLockWasAcquired()) {
                LOGGER.debug("Could not lock information region for " + displayName + ". Ignoring.");
            } else {
                try {
                    out = lockFileAccess.readLockInfo();
                } finally {
                    lockOutcome.getFileLock().release();
                }
            }
            return out;
        }

        private FileLockOutcome lockStateRegion(final LockMode lockMode) throws IOException, InterruptedException {
            final ExponentialBackoff<AwaitableFileLockReleasedSignal> backoff = newExponentialBackoff(lockTimeoutMs);
            return backoff.retryUntil(new IOQuery<FileLockOutcome>() {
                private long lastPingTime;
                private int lastLockHolderPort;

                @Override
                public IOQuery.Result<FileLockOutcome> run() throws IOException, InterruptedException {
                    FileLockOutcome lockOutcome = lockFileAccess.tryLockState(lockMode == LockMode.Shared);
                    if (lockOutcome.isLockWasAcquired()) {
                        return IOQuery.Result.successful(lockOutcome);
                    }
                    if (port != -1) { //we don't like the assumption about the port very much
                        com.tyron.builder.cache.internal.filelock.LockInfo lockInfo = readInformationRegion(backoff);
                        if (lockInfo.port != -1) {
                            if (lockInfo.port != lastLockHolderPort) {
                                backoff.restartTimer();
                                lastLockHolderPort = lockInfo.port;
                                lastPingTime = 0;
                            }
                            if (fileLockContentionHandler.maybePingOwner(lockInfo.port, lockInfo.lockId, displayName, backoff.getTimer().getElapsedMillis() - lastPingTime, backoff.getSignal())) {
                                lastPingTime = backoff.getTimer().getElapsedMillis();
                                LOGGER.debug("The file lock for " + displayName + " is held by a different Gradle process (pid: " + lockInfo.pid + ", lockId: " + lockInfo.lockId + "). Pinged owner at port " + lockInfo.port);
                            }
                        } else {
                            LOGGER.debug("The file lock for " + displayName + " is held by a different Gradle process. I was unable to read on which port the owner listens for lock access requests.");
                        }
                    }
                    return IOQuery.Result.notSuccessful(lockOutcome);
                }
            });
        }

        private FileLockOutcome lockInformationRegion(final LockMode lockMode, ExponentialBackoff<AwaitableFileLockReleasedSignal> backoff) throws IOException, InterruptedException {
            return backoff.retryUntil(() -> {
                FileLockOutcome lockOutcome = lockFileAccess.tryLockInfo(lockMode == LockMode.Shared);
                if (lockOutcome.isLockWasAcquired()) {
                    return IOQuery.Result.successful(lockOutcome);
                } else {
                    return IOQuery.Result.notSuccessful(lockOutcome);
                }
            });
        }
    }

    private ExponentialBackoff<AwaitableFileLockReleasedSignal> newExponentialBackoff(int shortTimeoutMs) {
        return ExponentialBackoff.of(shortTimeoutMs, MILLISECONDS, new AwaitableFileLockReleasedSignal());
    }

    @VisibleForTesting
    static class AwaitableFileLockReleasedSignal implements FileLockReleasedSignal, ExponentialBackoff.Signal {

        private final Lock lock = new ReentrantLock();
        private final Condition condition = lock.newCondition();
        private int waiting;

        public boolean await(long millis) throws InterruptedException {
            lock.lock();
            try {
                waiting++;
                return condition.await(millis, MILLISECONDS);
            } finally {
                waiting--;
                lock.unlock();
            }
        }

        @Override
        public void trigger() {
            lock.lock();
            try {
                if (waiting > 0) {
                    condition.signalAll();
                }
            } finally {
                lock.unlock();
            }
        }

        @VisibleForTesting
        boolean isWaiting() {
            lock.lock();
            try {
                return waiting > 0;
            } finally {
                lock.unlock();
            }
        }
    }
}