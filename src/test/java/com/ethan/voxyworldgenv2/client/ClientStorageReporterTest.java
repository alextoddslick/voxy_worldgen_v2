package com.ethan.voxyworldgenv2.client;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The disk measurement half of the storage report. The number sent to the server must mean
 * "bytes Voxy's store for this world occupies right now", and a store that does not exist must
 * produce NO report rather than a plausible-looking zero.
 */
class ClientStorageReporterTest {

    @TempDir
    Path dir;

    @Test
    void sumsNestedRegularFiles(@TempDir Path dir) throws Exception {
        Files.write(dir.resolve("a.bin"), new byte[100]);
        Path sub = Files.createDirectories(dir.resolve("sub").resolve("deeper"));
        Files.write(sub.resolve("b.bin"), new byte[28]);

        assertEquals(128, ClientStorageReporter.directorySize(dir));
    }

    @Test
    void missingDirectoryIsNotAValue() {
        // -1, not 0: "no store" must be distinguishable from "empty store", because the caller
        // skips the report entirely for -1 while 0 would be sent and rendered as truth.
        assertEquals(-1, ClientStorageReporter.directorySize(dir.resolve("does-not-exist")));
    }

    @Test
    void emptyDirectoryIsZero() {
        assertEquals(0, ClientStorageReporter.directorySize(dir));
    }
}
