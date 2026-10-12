package app.yatori.android

import mobilecore.Mobilecore
import org.json.JSONObject

object EngineRegistry {
    @Volatile
    var initialized: Boolean = false
        private set

    private val initLock = Any()

    /**
     * Initializes the Go core at most once per Android process.
     * Returns true only for the call that performed the initialization.
     */
    fun ensureInitialized(dataDir: String): Boolean = synchronized(initLock) {
        if (initialized) return@synchronized false

        val result = JSONObject(Mobilecore.init(dataDir))
        if (!result.optBoolean("ok")) {
            val message = result.optString("error", "init failed")
            throw IllegalStateException(message)
        }

        initialized = true
        true
    }
}
