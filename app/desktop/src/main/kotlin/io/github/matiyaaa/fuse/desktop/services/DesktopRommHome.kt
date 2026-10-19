package io.github.matiyaaa.fuse.desktop.services

import io.github.matiyaaa.fuse.data.settings.SecretStore
import io.github.matiyaaa.fuse.desktop.system.Processes
import io.github.matiyaaa.fuse.romm.RommHome
import io.github.matiyaaa.fuse.ui.shell.store.RommHomeHost
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.SecureRandom
import java.util.concurrent.TimeUnit

/**
 * RomM on this computer, run with the Docker or Podman installed here ([RommHome]). The Compose
 * file and what it was made from live in [dir]; the database and sign-in secrets are made once and
 * kept only in [secrets], handed to Compose in its environment for each command. [log] gets what
 * happened, never a secret.
 */
internal class DesktopRommHome(
    private val dir: File,
    private val secrets: SecretStore,
    private val log: (String) -> Unit = {},
) : RommHomeHost {
    private val file: File get() = File(dir, "compose.yml")
    private val specFile: File get() = File(dir, "spec.json")
    private val json = Json { ignoreUnknownKeys = true }

    @Volatile private var engine: RommHome.Engine? = null
    @Volatile private var looked = false

    override suspend fun engine(): RommHome.Engine? = withContext(Dispatchers.IO) {
        if (!looked) {
            engine = RommHome.Engine.entries.firstOrNull { e ->
                Processes.which(e.command[0]) != null && Processes.run(e.command + e.version, timeoutMs = VERSION_MS)?.exitCode == 0
            }
            looked = true
        }
        engine
    }

    override suspend fun current(): RommHome.Spec? = withContext(Dispatchers.IO) {
        runCatching { json.decodeFromString(RommHome.Spec.serializer(), specFile.readText()) }.getOrNull()?.takeIf { file.isFile }
    }

    override suspend fun start(spec: RommHome.Spec): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val e = engine() ?: error("Neither Docker nor Podman is installed on this computer.")
            check(File(spec.library).isDirectory) { "${spec.library} isn't a folder on this computer." }
            File(spec.dataDir, "assets").mkdirs()
            File(spec.dataDir, "config").mkdirs()
            dir.mkdirs()
            write(file, RommHome.compose(spec))
            write(specFile, json.encodeToString(RommHome.Spec.serializer(), spec))
            // The first start downloads RomM and its database before they run: it can take a while.
            val (code, out) = compose(RommHome.up(e, file.path), UP_MS)
            if (code != 0) error("${e.label} couldn't start RomM. ${lastLine(out)}".trim())
            log("RomM on this computer: started with ${e.label}, at ${RommHome.address(spec)}")
        }
    }

    override suspend fun stop(): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val e = engine() ?: error("Neither Docker nor Podman is installed on this computer.")
            check(file.isFile) { "RomM isn't set up on this computer." }
            val (code, out) = compose(RommHome.stop(e, file.path), STOP_MS)
            if (code != 0) error("${e.label} couldn't stop RomM. ${lastLine(out)}".trim())
            log("RomM on this computer: stopped")
        }
    }

    override suspend fun running(): Boolean = withContext(Dispatchers.IO) {
        val e = engine() ?: return@withContext false
        if (!file.isFile) return@withContext false
        val (code, out) = runCatching { compose(RommHome.running(e, file.path), PS_MS) }.getOrDefault(-1 to "")
        code == 0 && out.lines().any { it.isNotBlank() }
    }

    /** The secrets, made the first time and kept in the secret store from then on. */
    private suspend fun keys(): Map<String, String> {
        val serializer = MapSerializer(String.serializer(), String.serializer())
        secrets.get(SECRET)?.let { kept -> runCatching { json.decodeFromString(serializer, kept) }.getOrNull()?.takeIf { it.keys.containsAll(RommHome.SECRET_VARS) }?.let { return it } }
        val random = SecureRandom()
        val made = RommHome.newSecrets({ n -> ByteArray(n).also(random::nextBytes) })
        secrets.put(SECRET, json.encodeToString(serializer, made))
        return made
    }

    /** Runs [argv] in [dir] with the secrets in its environment; its exit code and what it said (or -1 when it took too long). */
    private suspend fun compose(argv: List<String>, timeoutMs: Long): Pair<Int, String> {
        val env = keys()
        val process = Processes.builder(argv).directory(dir).redirectErrorStream(true).also { it.environment().putAll(env) }.start()
        process.outputStream.close()
        val out = StringBuilder()
        val reader = Thread {
            runCatching { process.inputStream.bufferedReader().useLines { lines -> lines.forEach { if (out.length < MAX_OUTPUT) out.appendLine(it) } } }
        }.apply { isDaemon = true; start() }
        if (!process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) {
            process.destroyForcibly()
            return -1 to "It took too long."
        }
        reader.join(2_000)
        return process.exitValue() to out.toString()
    }

    private fun lastLine(out: String): String = out.lines().lastOrNull { it.isNotBlank() }?.trim()?.take(300).orEmpty()

    private fun write(to: File, text: String) {
        val tmp = File(to.parentFile, ".${to.name}.tmp")
        tmp.writeText(text)
        try {
            Files.move(tmp.toPath(), to.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (e: AtomicMoveNotSupportedException) {
            Files.move(tmp.toPath(), to.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    companion object {
        /** Where the database and sign-in secrets are kept. */
        const val SECRET = "romm.home"
        private const val VERSION_MS = 20_000L
        private const val UP_MS = 45 * 60_000L
        private const val STOP_MS = 5 * 60_000L
        private const val PS_MS = 30_000L
        private const val MAX_OUTPUT = 64 * 1024
    }
}
