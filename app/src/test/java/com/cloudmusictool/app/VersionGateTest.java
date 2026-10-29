package com.cloudmusictool.app;

import com.cloudmusictool.app.profile.ProfileRegistry;
import com.cloudmusictool.app.profile.TargetProfile;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class VersionGateTest {

    @Test
    public void supportedVersions_areExplicitlyListed() {
        assertTrue(VersionGate.isSupportedVersion("9.6.08"));
        assertTrue(VersionGate.isSupportedVersion("9.6.13"));
        assertTrue("9.6.15 复用 9.6.13 挂载点", VersionGate.isSupportedVersion("9.6.15"));
        assertFalse(VersionGate.isSupportedVersion("9.6.09"));
        assertFalse(VersionGate.isSupportedVersion(null));
    }

    @Test
    public void versionCode9006015MapsTo9615Profile() {
        TargetProfile profile = ProfileRegistry.forVersionCode(9006015L);
        assertNotNull(profile);
        assertEquals("9.6.15", profile.versionName);
        assertEquals(9006015L, profile.versionCode);
        assertEquals("9.6.15", ProfileRegistry.forVersionName("9.6.15").versionName);
    }
}
