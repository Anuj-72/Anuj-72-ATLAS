package com.inferstep.atlas.protocol

/**
 * Proxy HTTP paths, as listed in docs/API.md ("Building a non-TUI client").
 * The health, ready and version routes need no service token.
 */
object AtlasEndpoints {
    const val AGENT = "/v1/agent"
    const val PERMISSION = "/v1/permission"
    const val CANCEL = "/cancel"
    const val WORKSPACE = "/workspace"
    const val HEALTH = "/health"
    const val READY = "/ready"
    const val VERSION = "/version"
}
