package io.github.matiyaaa.fuse.romm

/**
 * RomM on this computer, through the Docker or Podman the person already has: the Compose file Fuse
 * writes in its own folder (after RomM's own `examples/docker-compose.example.yml`, RomM 5.4.0) and
 * the commands that start and stop it. Pure text and argument lists; running them is the app's.
 *
 * The person's library is mounted read-only. The database and sign-in secrets are never in the file:
 * Compose reads them from the environment of the command Fuse runs ([SECRET_VARS]), and Fuse keeps
 * them in its secret store. Nothing here runs anything.
 */
object RommHome {
    /** RomM's release the file names: the one Fuse is built and tested against. */
    const val ROMM_IMAGE = "rommapp/romm:5.4.0"

    /** The database RomM's example uses. */
    const val DB_IMAGE = "mariadb:latest"

    /** The Compose project name, so Fuse's containers are its own and found again. */
    const val PROJECT = "fuse-romm"

    /** RomM's own port inside its container. */
    const val INNER_PORT = 8080

    const val DB_PASSWORD = "FUSE_ROMM_DB_PASSWORD"
    const val DB_ROOT_PASSWORD = "FUSE_ROMM_DB_ROOT_PASSWORD"
    const val AUTH_SECRET_KEY = "FUSE_ROMM_AUTH_SECRET_KEY"

    /** The secrets Compose reads from the command's environment. */
    val SECRET_VARS: List<String> = listOf(DB_PASSWORD, DB_ROOT_PASSWORD, AUTH_SECRET_KEY)

    /**
     * What to set up: the person's [library] (mounted read-only), the folder for RomM's own files
     * ([dataDir]: its assets, uploaded saves among them, and its config), the [port] RomM answers
     * on, and whether only this computer may reach it ([thisComputerOnly]) or the home network too.
     */
    @kotlinx.serialization.Serializable
    data class Spec(val library: String, val dataDir: String, val port: Int = 8080, val thisComputerOnly: Boolean = false)

    /** A way to run Compose: Docker's plugin, Podman's, or podman-compose; [version] asks it whether it works. */
    enum class Engine(val label: String, val command: List<String>, val version: List<String>) {
        DOCKER("Docker", listOf("docker", "compose"), listOf("version")),
        PODMAN("Podman", listOf("podman", "compose"), listOf("version")),
        PODMAN_COMPOSE("Podman", listOf("podman-compose"), listOf("--version")),
    }

    /** The Compose file for [spec]. */
    fun compose(spec: Spec): String {
        require(spec.port in 1..65535) { "A port is a number from 1 to 65535." }
        val bind = if (spec.thisComputerOnly) "127.0.0.1:${spec.port}:$INNER_PORT" else "${spec.port}:$INNER_PORT"
        val data = spec.dataDir.replace('\\', '/').trimEnd('/')
        return buildString {
            appendLine("# Written by Fuse for RomM on this computer. Fuse starts and stops it; your library is read-only to it.")
            appendLine("# The passwords and the sign-in key come from Fuse's secret store when Fuse runs Compose.")
            appendLine("name: $PROJECT")
            appendLine()
            appendLine("volumes:")
            appendLine("  mysql_data:")
            appendLine("  romm_resources:")
            appendLine("  romm_redis_data:")
            appendLine()
            appendLine("services:")
            appendLine("  romm:")
            appendLine("    image: $ROMM_IMAGE")
            appendLine("    container_name: $PROJECT")
            appendLine("    restart: unless-stopped")
            appendLine("    environment:")
            appendLine("      - DB_HOST=$PROJECT-db")
            appendLine("      - DB_NAME=romm")
            appendLine("      - DB_USER=romm-user")
            appendLine("      - DB_PASSWD=\${$DB_PASSWORD}")
            appendLine("      - ROMM_AUTH_SECRET_KEY=\${$AUTH_SECRET_KEY}")
            appendLine("    volumes:")
            appendLine("      - romm_resources:/romm/resources")
            appendLine("      - romm_redis_data:/redis-data")
            appendLine("      - type: bind")
            appendLine("        source: ${quote(spec.library.replace('\\', '/').trimEnd('/'))}")
            appendLine("        target: /romm/library")
            appendLine("        read_only: true")
            appendLine("      - type: bind")
            appendLine("        source: ${quote("$data/assets")}")
            appendLine("        target: /romm/assets")
            appendLine("      - type: bind")
            appendLine("        source: ${quote("$data/config")}")
            appendLine("        target: /romm/config")
            appendLine("    ports:")
            appendLine("      - ${quote(bind)}")
            appendLine("    depends_on:")
            appendLine("      $PROJECT-db:")
            appendLine("        condition: service_healthy")
            appendLine("        restart: true")
            appendLine()
            appendLine("  $PROJECT-db:")
            appendLine("    image: $DB_IMAGE")
            appendLine("    container_name: $PROJECT-db")
            appendLine("    restart: unless-stopped")
            appendLine("    environment:")
            appendLine("      - MARIADB_ROOT_PASSWORD=\${$DB_ROOT_PASSWORD}")
            appendLine("      - MARIADB_DATABASE=romm")
            appendLine("      - MARIADB_USER=romm-user")
            appendLine("      - MARIADB_PASSWORD=\${$DB_PASSWORD}")
            appendLine("    volumes:")
            appendLine("      - mysql_data:/var/lib/mysql")
            appendLine("    healthcheck:")
            appendLine("      test: [\"CMD\", \"healthcheck.sh\", \"--connect\", \"--innodb_initialized\"]")
            appendLine("      start_period: 30s")
            appendLine("      start_interval: 10s")
            appendLine("      interval: 10s")
            appendLine("      timeout: 5s")
            appendLine("      retries: 5")
        }
    }

    /** New secrets: each [bytes] random bytes as hex (RomM's own advice for its key is `openssl rand -hex 32`). */
    fun newSecrets(random: (Int) -> ByteArray, bytes: Int = 32): Map<String, String> =
        SECRET_VARS.associateWith { random(bytes).joinToString("") { b -> (b.toInt() and 0xFF).toString(16).padStart(2, '0') } }

    /** [engine]'s command to start RomM from [file], in the background. */
    fun up(engine: Engine, file: String): List<String> = engine.command + listOf("-p", PROJECT, "-f", file, "up", "-d")

    /** [engine]'s command to stop it, keeping everything. */
    fun stop(engine: Engine, file: String): List<String> = engine.command + listOf("-p", PROJECT, "-f", file, "stop")

    /** [engine]'s command listing its containers that run (one id a line). */
    fun running(engine: Engine, file: String): List<String> = engine.command + listOf("-p", PROJECT, "-f", file, "ps", "-q")

    /** Where RomM answers on this computer for [spec]. */
    fun address(spec: Spec): String = "http://localhost:${spec.port}"

    /** [text] as a YAML double-quoted string. */
    internal fun quote(text: String): String = "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
}
