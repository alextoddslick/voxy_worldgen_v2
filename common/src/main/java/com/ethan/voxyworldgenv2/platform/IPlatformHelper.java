package com.ethan.voxyworldgenv2.platform;

import java.nio.file.Path;

// loader-specific bits the shared code needs
public interface IPlatformHelper {

    // folder voxyworldgenv2.json lives in
    Path getConfigDir();

    // the mod version the loader actually loaded, e.g. "2.5.2+mc26.2". Read from loader metadata
    // rather than a literal so it cannot drift from the jar it came out of.
    String getModVersion();
}
