package com.tyron.builder.api.internal.file.impl;

import com.tyron.builder.api.internal.file.FileMetadata;
import com.tyron.builder.api.internal.file.FileType;

public class DefaultFileMetadata implements FileMetadata {
    private static final FileMetadata DIR = new DefaultFileMetadata(FileType.Directory, 0, 0, AccessType.DIRECT);
    private static final FileMetadata DIR_ACCESSED_VIA_SYMLINK = new DefaultFileMetadata(FileType.Directory, 0, 0, AccessType.VIA_SYMLINK);
    private static final FileMetadata MISSING = new DefaultFileMetadata(FileType.Missing, 0, 0, AccessType.DIRECT);
    private static final FileMetadata BROKEN_SYMLINK = new DefaultFileMetadata(FileType.Missing, 0, 0, AccessType.VIA_SYMLINK);
    private final FileType type;
    private final long lastModified;
    private final long length;
    private final AccessType accessType;

    private DefaultFileMetadata(FileType type, long lastModified, long length, AccessType accessType) {
        this.type = type;
        this.lastModified = lastModified;
        this.length = length;
        this.accessType = accessType;
    }

    public static FileMetadata file(long lastModified, long length, AccessType accessType) {
        return new DefaultFileMetadata(FileType.RegularFile, lastModified, length, accessType);
    }

    public static FileMetadata directory(AccessType accessType) {
        switch (accessType) {
            case DIRECT:
                return DIR;
            case VIA_SYMLINK:
                return DIR_ACCESSED_VIA_SYMLINK;
            default:
                throw new AssertionError();
        }
    }

    public static FileMetadata missing(AccessType accessType) {
        switch (accessType) {
            case DIRECT:
                return MISSING;
            case VIA_SYMLINK:
                return BROKEN_SYMLINK;
            default:
                throw new AssertionError();
        }
    }

    @Override
    public FileType getType() {
        return type;
    }

    @Override
    public long getLastModified() {
        return lastModified;
    }

    @Override
    public long getLength() {
        return length;
    }

    @Override
    public AccessType getAccessType() {
        return accessType;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        DefaultFileMetadata that = (DefaultFileMetadata) o;
        return type == that.type &&
               length == that.length &&
               lastModified == that.lastModified &&
               accessType == that.accessType;
    }

    @Override
    public int hashCode() {
        int result = type.hashCode();
        result = 31 * result + (int) (lastModified ^ (lastModified >>> 32));
        result = 31 * result + (int) (length ^ (length >>> 32));
        result = 31 * result + accessType.hashCode();
        return result;
    }
}