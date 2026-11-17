package dev.voltguard.battery.engine

object CommandBlocklist {
    val FORBIDDEN_TERMS = listOf(
        "tether",
        "softap",
        "hotspot",
        "accelerometer_rotation",
        "user_rotation"
    )

    fun isCommandAllowed(command: String): Boolean {
        val lower = command.lowercase()
        return FORBIDDEN_TERMS.none { term -> lower.contains(term) }
    }

    fun requireAllowed(command: String) {
        if (!isCommandAllowed(command)) {
            throw SecurityException("Blocked forbidden command touching hotspot/tethering or rotation lock: $command")
        }
    }
}
