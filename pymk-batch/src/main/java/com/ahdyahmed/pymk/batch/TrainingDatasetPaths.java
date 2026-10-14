package com.ahdyahmed.pymk.batch;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;

/** Resolves final and execution-private staging paths for a dataset export. */
final class TrainingDatasetPaths {

    private TrainingDatasetPaths() {
    }

    static Path output(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("outputPath is required");
        }
        try {
            return Path.of(value).toAbsolutePath().normalize();
        } catch (InvalidPathException exception) {
            throw new IllegalArgumentException("outputPath is invalid", exception);
        }
    }

    static Path staging(String outputPath, long executionId) {
        Path output = output(outputPath);
        return output.resolveSibling(output.getFileName() + ".part." + executionId);
    }
}
