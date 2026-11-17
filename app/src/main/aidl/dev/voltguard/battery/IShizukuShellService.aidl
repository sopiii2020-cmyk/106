package dev.voltguard.battery;

interface IShizukuShellService {
    void destroy() = 16777114;
    String runCommand(String cmd) = 1;
}
