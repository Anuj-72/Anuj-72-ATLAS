package com.inferstep.atlas.client

import com.inferstep.atlas.protocol.ReadyResponse
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource

class AtlasStatusTest {
    @Test
    fun `all gates passing is ready`() {
        val ready =
            ReadyResponse(
                ready = true,
                inference = true,
                lensReady = true,
                sandbox = true,
                v3 = true,
            )

        assertEquals(AtlasStatus.Ready, statusFromReady(ready))
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("gates")
    fun `a failing gate is named in status order`(
        name: String,
        response: ReadyResponse,
        want: List<String>,
    ) {
        assertEquals(AtlasStatus.Starting(want), statusFromReady(response), name)
    }

    @Test
    fun `a 401 is unauthorized and every other failure is unreachable`() {
        assertEquals(
            AtlasStatus.Unauthorized,
            statusFromFailure(AtlasApiError(401, "unauthorized", "no token")),
        )
        assertEquals(
            AtlasStatus.Unreachable,
            statusFromFailure(AtlasApiError(503, "dependency_unavailable", "lens down")),
        )
        assertEquals(
            AtlasStatus.Unreachable,
            statusFromFailure(IllegalStateException("connection refused")),
        )
    }

    companion object {
        @JvmStatic
        fun gates(): List<Arguments> =
            listOf(
                Arguments.of(
                    "inference alone",
                    ReadyResponse(ready = false, lensReady = true, sandbox = true, v3 = true),
                    listOf("inference"),
                ),
                Arguments.of(
                    "lens and v3",
                    ReadyResponse(ready = false, inference = true, sandbox = true),
                    listOf("lens", "v3 service"),
                ),
                Arguments.of(
                    "all four, in display order",
                    ReadyResponse(ready = false),
                    listOf("inference", "lens", "sandbox", "v3 service"),
                ),
                Arguments.of(
                    "a ready flag that disagrees with the gates",
                    ReadyResponse(
                        ready = false,
                        inference = true,
                        lensReady = true,
                        sandbox = true,
                        v3 = true,
                    ),
                    emptyList<String>(),
                ),
            )
    }
}
