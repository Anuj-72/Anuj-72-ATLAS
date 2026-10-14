// AtlasConnection: the documented default, the token file, and the cases an
// install without a service token produces.
package com.inferstep.atlas.client

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class AtlasConnectionTest {
    @Test
    fun `reads the documented variables`() {
        val connection =
            AtlasConnection.fromEnvironment(
                env = { name ->
                    when (name) {
                        AtlasConnection.URL_ENV -> "http://127.0.0.1:18090"
                        AtlasConnection.TOKEN_FILE_ENV -> "/run/atlas-secrets/service-token"
                        else -> null
                    }
                },
                readFile = { path ->
                    if (path ==
                        "/run/atlas-secrets/service-token"
                    ) {
                        "s3cret\n"
                    } else {
                        null
                    }
                },
            )

        assertEquals("http://127.0.0.1:18090", connection.url)
        // Trimmed: the file ends with a newline.
        assertEquals("s3cret", connection.token)
    }

    @Test
    fun `falls back to the documented default url`() {
        val connection = AtlasConnection.fromEnvironment(env = { null }, readFile = { null })

        assertEquals(AtlasConnection.DEFAULT_URL, connection.url)
        assertEquals("http://127.0.0.1:8090", connection.url)
    }

    @Test
    fun `a blank url falls back rather than producing an empty base`() {
        val connection =
            AtlasConnection.fromEnvironment(env = { name ->
                if (name ==
                    AtlasConnection.URL_ENV
                ) {
                    "  "
                } else {
                    null
                }
            }, readFile = { null })

        assertEquals(AtlasConnection.DEFAULT_URL, connection.url)
    }

    @Test
    fun `an install with no token file is a connection with no token`() {
        val connection = AtlasConnection.fromEnvironment(env = { null }, readFile = { null })

        assertEquals("", connection.token)
    }

    @Test
    fun `reads the fallback token file when no path is configured`() {
        val asked = mutableListOf<String>()
        val connection =
            AtlasConnection.fromEnvironment(
                env = { null },
                readFile = { path ->
                    asked += path
                    null
                },
            )

        assertEquals("", connection.token)
        assertEquals(listOf(AtlasConnection.FALLBACK_TOKEN_FILE), asked)
    }
}
