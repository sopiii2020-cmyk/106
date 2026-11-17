package dev.voltguard.battery.service

import dev.voltguard.battery.IShizukuShellService
import dev.voltguard.battery.engine.CommandBlocklist
import kotlin.system.exitProcess

class ShizukuShellUserService : IShizukuShellService.Stub() {

    override fun destroy() {
        exitProcess(0)
    }

    override fun runCommand(cmd: String): String {
        CommandBlocklist.requireAllowed(cmd)
        val process = ProcessBuilder("sh", "-c", cmd)
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().readText()
        val exitCode = process.waitFor()
        return "EXIT=$exitCode\n$output"
    }
}
