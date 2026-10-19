package com.inferstep.atlas.ide

import com.inferstep.atlas.protocol.PermissionMode
import com.inferstep.atlas.settings.AtlasSettings
import com.intellij.testFramework.fixtures.BasePlatformTestCase

/**
 * The project service is the seam the panel, the status bar and the actions
 * share, so the wiring that makes them one conversation is worth a test: a
 * second instance, or a session that ignores the stored permission mode,
 * would show up here before a runPyCharm session.
 */
class AtlasProjectServiceTest : BasePlatformTestCase() {
    fun testOneServiceAndOneSessionPerProject() {
        val service = AtlasProjectService.getInstance(project)

        assertSame(service, AtlasProjectService.getInstance(project))
        assertFalse(service.session.state.value.running)
        assertTrue(
            service.connection.value.url
                .isNotEmpty(),
        )
        assertEquals(
            AtlasSettings.getInstance().permissionMode,
            service.session.state.value.permissionMode,
        )
    }

    fun testSettingThePermissionModeWritesTheSettingAndTheSession() {
        val settings = AtlasSettings.getInstance()
        val previous = settings.permissionMode
        try {
            AtlasProjectService
                .getInstance(project)
                .setPermissionMode(PermissionMode.YOLO)

            assertEquals(PermissionMode.YOLO, settings.permissionMode)
            assertEquals(
                PermissionMode.YOLO,
                AtlasProjectService
                    .getInstance(project)
                    .session.state.value.permissionMode,
            )
        } finally {
            // The settings service is application-wide, so a test must not
            // leave a mode behind for the next one.
            settings.permissionMode = previous
            settings.notifyChanged()
        }
    }
}
