package com.cloudmusictool.app;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class VersionGateTest {

    @Test
    public void supportedVersions_areExplicitlyListed() {
        assertTrue(VersionGate.isSupportedVersion("9.6.08"));
        assertTrue(VersionGate.isSupportedVersion("9.6.13"));
        assertFalse(VersionGate.isSupportedVersion("9.6.09"));
        assertFalse(VersionGate.isSupportedVersion(null));
    }
}
