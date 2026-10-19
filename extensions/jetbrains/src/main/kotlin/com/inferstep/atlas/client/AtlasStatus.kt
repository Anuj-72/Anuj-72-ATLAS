// The proxy's readiness, as a value the status bar and its tests share.
//
// Kept in the IDE-free client package: it is a rule about the API's response,
// not about Swing. `AtlasStatusBarWidget` only decides how to draw it.
package com.inferstep.atlas.client

import com.inferstep.atlas.protocol.ReadyResponse

/** What the status bar says about the proxy, in display order. */
sealed interface AtlasStatus {
    /** Every gate passes and `/v1/agent` will accept a turn. */
    data object Ready : AtlasStatus

    /** Reachable, but one or more gates are still failing. */
    data class Starting(
        /** The gate labels that are not true yet, in status-bar order. */
        val waiting: List<String>,
    ) : AtlasStatus

    /** The proxy could not be reached at all. */
    data object Unreachable : AtlasStatus

    /** The proxy answered 401: it wants a service token this client lacks. */
    data object Unauthorized : AtlasStatus
}

/** Map `GET /ready` to the state the status bar shows. */
fun statusFromReady(response: ReadyResponse): AtlasStatus {
    if (response.ready) {
        return AtlasStatus.Ready
    }
    val waiting =
        buildList {
            if (!response.inference) {
                add("inference")
            }
            if (!response.lensReady) {
                add("lens")
            }
            if (!response.sandbox) {
                add("sandbox")
            }
            if (!response.v3) {
                add("v3 service")
            }
        }
    return AtlasStatus.Starting(waiting)
}

/** Map a failed `/ready` request to the state the status bar shows. */
fun statusFromFailure(error: Throwable): AtlasStatus =
    if (error is AtlasApiError && error.isUnauthorized) {
        AtlasStatus.Unauthorized
    } else {
        AtlasStatus.Unreachable
    }
