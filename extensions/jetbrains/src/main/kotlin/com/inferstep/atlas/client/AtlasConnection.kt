package com.inferstep.atlas.client

import java.nio.file.Files
import java.nio.file.Path

// Where the proxy is and how to authenticate to it, read from the same
// environment variables the TUI and `atlas doctor` use.
//
// Stage 3 needs a connection to be useful at all; the settings UI and secure
// token storage are Stage 5, so this is the interim source of both, and it is
// the only place in the plugin that reads the environment.

/**
 * proxy URL plus service token.
 *
 * The token is never logged and never written anywhere: it is read from the
 * per-installation file `atlas init` generates (0600), which is the same
 * transport every other ATLAS client uses.
 */
data class AtlasConnection(
    val url: String,
    /** Empty when no token is configured, which is an install predating it. */
    val token: String = "",
) {
    companion object {
        /** The documented default (docs/CONFIGURATION.md). */
        const val DEFAULT_URL = "http://127.0.0.1:8090"

        /** atlas-proxy endpoint, honoured by the TUI as its `--proxy` default. */
        const val URL_ENV = "ATLAS_PROXY_URL"

        /** Path to the generated service token; its value never goes in `.env`. */
        const val TOKEN_FILE_ENV = "ATLAS_SERVICE_TOKEN_FILE"

        /** The token file `atlas init` writes beside the install. */
        const val FALLBACK_TOKEN_FILE = "secrets/service-token"

        /**
         * Read the connection from the environment.
         *
         * A missing token file is not an error: an install without
         * `ATLAS_SERVICE_TOKEN_FILE` has internal auth disabled, and the proxy
         * answers. A blank `ATLAS_PROXY_URL` falls back to the default.
         */
        fun fromEnvironment(
            env: (String) -> String? = System::getenv,
            readFile: (String) -> String? = ::readTokenFile,
        ): AtlasConnection {
            val configuredUrl =
                env(URL_ENV)
                    ?.trim()
                    ?.takeIf { it.isNotEmpty() }
            val configuredPath =
                env(TOKEN_FILE_ENV)
                    ?.trim()
                    ?.takeIf { it.isNotEmpty() }
            val token =
                readFile(configuredPath ?: FALLBACK_TOKEN_FILE)
                    ?.trim()
                    .orEmpty()
            return AtlasConnection(url = configuredUrl ?: DEFAULT_URL, token = token)
        }

        private fun readTokenFile(path: String): String? =
            try {
                val file = Path.of(path)
                if (Files.isReadable(file)) {
                    Files.readString(file)
                } else {
                    null
                }
            } catch (_: Exception) {
                // An unreadable, missing or non-file path means "no token
                // configured", not a broken connection.
                null
            }
    }
}
