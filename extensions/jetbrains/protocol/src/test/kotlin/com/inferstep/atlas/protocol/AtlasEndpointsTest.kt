package com.inferstep.atlas.protocol

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * The endpoint constants are the proxy's public paths, copied from
 * docs/API.md. A typo here would break every client at runtime, so the
 * exact strings are asserted rather than just their shape.
 */
class AtlasEndpointsTest {
    @Test
    fun agentEndpointIsTheStreamingTurnRoute() {
        assertEquals("/v1/agent", AtlasEndpoints.AGENT)
    }

    @Test
    fun everyEndpointMatchesTheDocumentedPath() {
        assertEquals("/v1/permission", AtlasEndpoints.PERMISSION)
        assertEquals("/cancel", AtlasEndpoints.CANCEL)
        assertEquals("/workspace", AtlasEndpoints.WORKSPACE)
        assertEquals("/health", AtlasEndpoints.HEALTH)
        assertEquals("/ready", AtlasEndpoints.READY)
        assertEquals("/version", AtlasEndpoints.VERSION)
    }
}
