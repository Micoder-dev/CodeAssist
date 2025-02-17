package com.tyron.builder.internal.vfs.impl;

import static com.tyron.builder.api.internal.file.FileMetadata.*;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.Interner;
import com.google.common.hash.HashCode;
import com.google.common.util.concurrent.Striped;
import com.tyron.builder.api.internal.file.FileMetadata;
import com.tyron.builder.api.internal.file.FileType;
import com.tyron.builder.api.internal.file.Stat;
import com.tyron.builder.api.internal.hash.FileHasher;
import com.tyron.builder.api.internal.snapshot.FileSystemLocationSnapshot;
import com.tyron.builder.api.internal.snapshot.FileSystemSnapshot;
import com.tyron.builder.api.internal.snapshot.MissingFileSnapshot;
import com.tyron.builder.api.internal.snapshot.RegularFileSnapshot;
import com.tyron.builder.api.internal.snapshot.SnapshottingFilter;
import com.tyron.builder.api.internal.snapshot.impl.DirectorySnapshotter;
import com.tyron.builder.api.internal.snapshot.impl.DirectorySnapshotterStatistics;
import com.tyron.builder.api.internal.snapshot.impl.FileSystemSnapshotFilter;
import com.tyron.builder.internal.vfs.FileSystemAccess;
import com.tyron.builder.internal.vfs.VirtualFileSystem;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.Lock;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

public class DefaultFileSystemAccess implements FileSystemAccess {
    private static final Logger LOGGER = LoggerFactory.getLogger(DefaultFileSystemAccess.class);

    private final VirtualFileSystem virtualFileSystem;
    private final Stat stat;
    private final Interner<String> stringInterner;
    private final WriteListener writeListener;
    private final DirectorySnapshotterStatistics.Collector statisticsCollector;
    private ImmutableList<String> defaultExcludes;
    private DirectorySnapshotter directorySnapshotter;
    private final FileHasher hasher;
    private final StripedProducerGuard<String> producingSnapshots = new StripedProducerGuard<>();

    public DefaultFileSystemAccess(
            FileHasher hasher,
            Interner<String> stringInterner,
            Stat stat,
            VirtualFileSystem virtualFileSystem,
            WriteListener writeListener,
            DirectorySnapshotterStatistics.Collector statisticsCollector,
            String... defaultExcludes
    ) {
        this.stringInterner = stringInterner;
        this.stat = stat;
        this.writeListener = writeListener;
        this.statisticsCollector = statisticsCollector;
        this.defaultExcludes = ImmutableList.copyOf(defaultExcludes);
        this.directorySnapshotter = new DirectorySnapshotter(hasher, stringInterner, this.defaultExcludes, statisticsCollector);
        this.hasher = hasher;
        this.virtualFileSystem = virtualFileSystem;
    }

    @Override
    public <T> T read(String location, Function<FileSystemLocationSnapshot, T> visitor) {
        return visitor.apply(readLocation(location));
    }

    @Override
    public <T> Optional<T> readRegularFileContentHash(String location, Function<HashCode, T> visitor) {
        return virtualFileSystem.findMetadata(location)
                .<Optional<HashCode>>flatMap(snapshot -> {
                    if (snapshot.getType() != FileType.RegularFile) {
                        return Optional.of(Optional.empty());
                    }
                    if (snapshot instanceof FileSystemLocationSnapshot) {
                        return Optional.of(Optional.of(((FileSystemLocationSnapshot) snapshot).getHash()));
                    }
                    return Optional.empty();
                })
                .orElseGet(() -> {
                    File file = new File(location);
                    FileMetadata fileMetadata = this.stat.stat(file);
                    if (fileMetadata.getType() == FileType.Missing) {
                        storeMetadataForMissingFile(location, fileMetadata.getAccessType());
                    }
                    if (fileMetadata.getType() != FileType.RegularFile) {
                        return Optional.empty();
                    }
                    HashCode hash = producingSnapshots.guardByKey(location,
                            () -> virtualFileSystem.findSnapshot(location)
                                    .orElseGet(() -> {
                                        HashCode hashCode = hasher.hash(file, fileMetadata.getLength(), fileMetadata.getLastModified());
                                        RegularFileSnapshot
                                                snapshot = new RegularFileSnapshot(location, file.getName(), hashCode, fileMetadata);
                                        virtualFileSystem.store(snapshot.getAbsolutePath(), snapshot);
                                        return snapshot;
                                    }).getHash());
                    return Optional.of(hash);
                })
                .map(visitor);
    }

    private void storeMetadataForMissingFile(String location, AccessType accessType) {
        virtualFileSystem.store(location, new MissingFileSnapshot(location, accessType));
    }

    @Override
    public void read(String location, SnapshottingFilter filter, Consumer<FileSystemLocationSnapshot> visitor) {
        if (filter.isEmpty()) {
            visitor.accept(readLocation(location));
        } else {
            FileSystemSnapshot filteredSnapshot = readSnapshotFromLocation(location,
                    snapshot -> FileSystemSnapshotFilter
                            .filterSnapshot(filter.getAsSnapshotPredicate(), snapshot),
                    () -> {
                        FileSystemLocationSnapshot snapshot = snapshot(location, filter);
                        return snapshot.getType() == FileType.Directory
                                // Directory snapshots have been filtered while walking the file system
                                ? snapshot
                                : FileSystemSnapshotFilter.filterSnapshot(filter.getAsSnapshotPredicate(), snapshot);
                    });

            if (filteredSnapshot instanceof FileSystemLocationSnapshot) {
                visitor.accept((FileSystemLocationSnapshot) filteredSnapshot);
            }
        }
    }

    private FileSystemLocationSnapshot snapshot(String location, SnapshottingFilter filter) {
        File file = new File(location);
        FileMetadata fileMetadata = this.stat.stat(file);
        switch (fileMetadata.getType()) {
            case RegularFile:
                HashCode hash = hasher.hash(file, fileMetadata.getLength(), fileMetadata.getLastModified());
                RegularFileSnapshot regularFileSnapshot = new RegularFileSnapshot(location, file.getName(), hash, fileMetadata);
                virtualFileSystem.store(regularFileSnapshot.getAbsolutePath(), regularFileSnapshot);
                return regularFileSnapshot;
            case Missing:
                MissingFileSnapshot missingFileSnapshot = new MissingFileSnapshot(location, fileMetadata.getAccessType());
                virtualFileSystem.store(missingFileSnapshot.getAbsolutePath(), missingFileSnapshot);
                return missingFileSnapshot;
            case Directory:
                AtomicBoolean hasBeenFiltered = new AtomicBoolean(false);
                FileSystemLocationSnapshot directorySnapshot = directorySnapshotter.snapshot(
                        location,
                        filter.isEmpty() ? null : filter.getAsDirectoryWalkerPredicate(),
                        hasBeenFiltered,
                        snapshot -> virtualFileSystem.store(snapshot.getAbsolutePath(), snapshot));
                if (!hasBeenFiltered.get()) {
                    virtualFileSystem.store(directorySnapshot.getAbsolutePath(), directorySnapshot);
                }
                return directorySnapshot;
            default:
                throw new UnsupportedOperationException();
        }
    }

    private FileSystemLocationSnapshot readLocation(String location) {
        return readSnapshotFromLocation(location, () -> snapshot(location, SnapshottingFilter.EMPTY));
    }

    private FileSystemLocationSnapshot readSnapshotFromLocation(
            String location,
            Supplier<FileSystemLocationSnapshot> readFromDisk
    ) {
        return readSnapshotFromLocation(
                location,
                Function.identity(),
                readFromDisk
        );
    }

    private <T> T readSnapshotFromLocation(
            String location,
            Function<FileSystemLocationSnapshot, T> snapshotProcessor,
            Supplier<T> readFromDisk
    ) {
        return virtualFileSystem.findSnapshot(location)
                .map(snapshotProcessor)
                // Avoid snapshotting the same location at the same time
                .orElseGet(() -> producingSnapshots.guardByKey(location,
                        () -> virtualFileSystem.findSnapshot(location)
                                .map(snapshotProcessor)
                                .orElseGet(readFromDisk)
                ));
    }

    @Override
    public void write(Iterable<String> locations, Runnable action) {
        writeListener.locationsWritten(locations);
        virtualFileSystem.invalidate(locations);
        action.run();
    }

    @Override
    public void record(FileSystemLocationSnapshot snapshot) {
        virtualFileSystem.store(snapshot.getAbsolutePath(), snapshot);
    }

    private static class StripedProducerGuard<T> {
        private final Striped<Lock> locks = Striped.lock(Runtime.getRuntime().availableProcessors() * 4);

        public <V> V guardByKey(T key, Supplier<V> supplier) {
            Lock lock = locks.get(key);
            try {
                lock.lock();
                return supplier.get();
            } finally {
                lock.unlock();
            }
        }
    }

    public void updateDefaultExcludes(String... newDefaultExcludesArgs) {
        ImmutableList<String> newDefaultExcludes = ImmutableList.copyOf(newDefaultExcludesArgs);
        if (!defaultExcludes.equals(newDefaultExcludes)) {
            LOGGER.debug("Default excludes changes from " + defaultExcludes + " to " + newDefaultExcludes);
            defaultExcludes = newDefaultExcludes;
            directorySnapshotter = new DirectorySnapshotter(hasher, stringInterner, newDefaultExcludes, statisticsCollector);
            virtualFileSystem.invalidateAll();
        }
    }
}