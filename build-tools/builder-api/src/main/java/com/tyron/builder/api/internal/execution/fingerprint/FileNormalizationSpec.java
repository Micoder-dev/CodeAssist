package com.tyron.builder.api.internal.execution.fingerprint;

import com.tyron.builder.api.internal.fingerprint.DirectorySensitivity;
import com.tyron.builder.api.internal.fingerprint.LineEndingSensitivity;
import com.tyron.builder.api.tasks.FileNormalizer;

/**
 * Specifies criteria for selecting a {@link FileCollectionFingerprinter}.
 */
public interface FileNormalizationSpec {
    Class<? extends FileNormalizer> getNormalizer();

    DirectorySensitivity getDirectorySensitivity();

    LineEndingSensitivity getLineEndingNormalization();
}