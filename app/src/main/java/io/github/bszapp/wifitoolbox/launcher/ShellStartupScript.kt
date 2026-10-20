package io.github.bszapp.wifitoolbox.launcher

import android.content.Context
import android.util.AtomicFile
import io.github.bszapp.wifitoolbox.contract.startup.StartupMode
import io.github.bszapp.wifitoolbox.service.MainServiceStarter
import java.io.File

internal class ShellStartupScript(private val context: Context) {
    private val launcher = RootProcessLauncher(context)
    private val scriptFile = File(
        requireNotNull(context.getExternalFilesDir(null)) { "无法访问启动脚本保存目录" },
        "start.sh",
    )

    val command: String = "sh ${launcher.shellQuote(scriptFile.absolutePath)}"

    fun recreate() {
        val startupLog = File(scriptFile.parentFile, "start.log")
        val launchCommand = launcher.buildDetachedCommand(
            className = MainServiceStarter::class.java.name,
            mode = StartupMode.OTHER,
            startupLogFile = startupLog,
        )
        val script = """
            #!/system/bin/sh
            trap 'printf "starter exit with %s\n" "${'$'}?"' 0
            printf 'starter begin\n'
            printf 'current uid is %s\n' "${'$'}(id -u)"
            startup_log=${launcher.shellQuote(startupLog.absolutePath)}
            printf 'startup log is %s\n' "${'$'}startup_log"
            : > "${'$'}startup_log" || { printf 'cannot create startup log\n' >&2; exit 1; }
            exec 3< "${'$'}startup_log" || exit 1
            export WIFITOOLBOX_STARTUP_PROGRESS=1
            $launchCommand
            server_pid=${'$'}!
            printf 'server pid is %s\n' "${'$'}server_pid"
            initialized=0
            read_startup_log() {
                while IFS= read -r line <&3; do
                    printf '%s\n' "${'$'}line"
                    if [ "${'$'}line" = 'server initialized' ]; then initialized=1; fi
                done
            }
            attempts=0
            while [ "${'$'}attempts" -lt 15 ]; do
                read_startup_log
                if ! kill -0 "${'$'}server_pid" 2>/dev/null; then
                    wait "${'$'}server_pid"
                    result=${'$'}?
                    read_startup_log
                    if [ "${'$'}result" -eq 0 ]; then result=1; fi
                    printf 'server exited before startup completed\n' >&2
                    exit "${'$'}result"
                fi
                if [ "${'$'}initialized" -eq 1 ]; then
                    exec 3<&-
                    exit 0
                fi
                sleep 1
                attempts=${'$'}((attempts + 1))
            done
            read_startup_log
            if [ "${'$'}initialized" -eq 1 ] && kill -0 "${'$'}server_pid" 2>/dev/null; then
                exit 0
            fi
            printf 'startup confirmation timed out, server was not stopped\n' >&2
            printf 'check startup log: %s\n' "${'$'}startup_log" >&2
            exit 124
        """.trimIndent()
        val atomicFile = AtomicFile(scriptFile)
        val output = atomicFile.startWrite()
        try {
            output.write((script + "\n").toByteArray(Charsets.UTF_8))
            atomicFile.finishWrite(output)
        } catch (error: Throwable) {
            atomicFile.failWrite(output)
            throw error
        }
    }
}
