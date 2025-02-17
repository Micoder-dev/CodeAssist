package com.tyron.builder.api.internal.fingerprint.impl;

import com.google.common.hash.HashCode;
import com.google.common.hash.Hasher;
import com.tyron.builder.api.internal.file.FileType;
import com.tyron.builder.api.internal.fingerprint.FileSystemLocationFingerprint;

import java.nio.charset.StandardCharsets;
import java.util.Comparator;
import java.util.function.ToIntFunction;

public class DefaultFileSystemLocationFingerprint implements FileSystemLocationFingerprint {
    private final HashCode normalizedContentHash;
    private final String normalizedPath;

    public DefaultFileSystemLocationFingerprint(String normalizedPath, FileType type, HashCode contentHash) {
        this.normalizedContentHash = hashForType(type, contentHash);
        this.normalizedPath = normalizedPath;
    }

    private static HashCode hashForType(FileType fileType, HashCode hash) {
        switch (fileType) {
            case Directory:
                return DIR_SIGNATURE;
            case Missing:
                return MISSING_FILE_SIGNATURE;
            case RegularFile:
                return hash;
            default:
                throw new IllegalStateException("Unknown file type: " + fileType);
        }
    }

    @Override
    public final void appendToHasher(Hasher hasher) {
        hasher.putString(getNormalizedPath(), StandardCharsets.UTF_8);
        hasher.putBytes(getNormalizedContentHash().asBytes());
    }

    @Override
    public FileType getType() {
        if (normalizedContentHash == DIR_SIGNATURE) {
            return FileType.Directory;
        } else if (normalizedContentHash == MISSING_FILE_SIGNATURE) {
            return FileType.Missing;
        } else {
            return FileType.RegularFile;
        }
    }

    @Override
    public String getNormalizedPath() {
        return normalizedPath;
    }

    @Override
    public HashCode getNormalizedContentHash() {
        return normalizedContentHash;
    }

    @Override
    public final int compareTo(FileSystemLocationFingerprint o) {
        int result = getNormalizedPath().compareTo(o.getNormalizedPath());
        if (result == 0) {
            result = Comparator.comparingInt(HashCode::hashCode)
                    .compare(getNormalizedContentHash(), o.getNormalizedContentHash());
        }
        return result;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }

        DefaultFileSystemLocationFingerprint that = (DefaultFileSystemLocationFingerprint) o;

        if (!normalizedContentHash.equals(that.normalizedContentHash)) {
            return false;
        }
        return normalizedPath.equals(that.normalizedPath);
    }

    @Override
    public int hashCode() {
        int result = normalizedContentHash.hashCode();
        result = 31 * result + normalizedPath.hashCode();
        return result;
    }

    @Override
    public String toString() {
        return String.format("'%s' / %s",
                getNormalizedPath(),
                getHashOrTypeToDisplay()
        );
    }

    private Object getHashOrTypeToDisplay() {
        switch (getType()) {
            case Directory:
                return "DIR";
            case Missing:
                return "MISSING";
            default:
                return normalizedContentHash;
        }
    }
}