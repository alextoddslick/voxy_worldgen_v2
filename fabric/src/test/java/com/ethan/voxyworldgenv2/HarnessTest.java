package com.ethan.voxyworldgenv2;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Proves the JUnit 5 harness is wired up. Gradle 9.5.1 fails the build outright when test
 * sources exist but no engine discovers them, so a green run here is what distinguishes
 * "harness works" from "harness silently skipped everything".
 */
class HarnessTest {

    @Test
    void harnessRunsJUnit5Tests() {
        assertEquals(4, 2 + 2);
    }
}
