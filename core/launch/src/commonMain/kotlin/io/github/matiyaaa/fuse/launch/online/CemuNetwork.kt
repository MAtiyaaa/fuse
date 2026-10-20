package io.github.matiyaaa.fuse.launch.online

/**
 * Which online service Cemu's account plays through, as Cemu keeps it (src/config/CemuConfig.cpp,
 * src/config/NetworkSettings.h): per account, in `settings.xml`'s `<AccountService>` as
 * `<SelectedService PersistentId="…" Service="…"/>`, the service Offline 0, Nintendo 1, Pretendo 2
 * or Custom 3, for the account in `<Account><PersistentId>` (2147483649, 0x80000001, Cemu's first
 * account, until another is chosen; Account.h `kMinPersistendId`). Pretendo also needs the Wii U's
 * own online files and an account linked to a Pretendo Network ID, which Cemu's Online Play
 * settings take. Pure: text in, text out.
 */
object CemuNetwork {
    const val OFFLINE = 0
    const val NINTENDO = 1
    const val PRETENDO = 2
    const val CUSTOM = 3

    /** Cemu's first account (Account.h `kMinPersistendId`). */
    const val FIRST_ACCOUNT = 2147483649L

    private val ACCOUNT = Regex("<Account>(.*?)</Account>", RegexOption.DOT_MATCHES_ALL)
    private val PERSISTENT = Regex("<PersistentId>\\s*(\\d+)\\s*</PersistentId>")
    private val SERVICE_BLOCK = Regex("<AccountService>(.*?)</AccountService>|<AccountService\\s*/>", RegexOption.DOT_MATCHES_ALL)
    private val SELECTED = Regex("<SelectedService\\s+([^>]*?)/>")
    private val ATTR = Regex("(\\w+)\\s*=\\s*\"([^\"]*)\"")

    /** The account Cemu plays as in [xml]. */
    fun account(xml: String): Long =
        ACCOUNT.find(xml)?.groupValues?.get(1)?.let { PERSISTENT.find(it)?.groupValues?.get(1)?.toLongOrNull() }?.takeIf { it >= FIRST_ACCOUNT } ?: FIRST_ACCOUNT

    /** The service [xml] picks for its account; null when it picks none (Cemu then reads its older setting). */
    fun service(xml: String): Int? {
        val id = account(xml)
        val block = SERVICE_BLOCK.find(xml)?.groupValues?.get(1) ?: return null
        return SELECTED.findAll(block).map { attrs(it.groupValues[1]) }
            .firstOrNull { it["PersistentId"]?.toLongOrNull() == id }?.get("Service")?.toIntOrNull()
    }

    /** [xml] with its account playing through [service]; every other account's choice as it was. */
    fun withService(xml: String, service: Int): String {
        val id = account(xml)
        val entry = "<SelectedService PersistentId=\"$id\" Service=\"$service\"/>"
        val found = SERVICE_BLOCK.find(xml)
        if (found == null) {
            val close = xml.lastIndexOf("</content>")
            val block = "    <AccountService>\n        $entry\n    </AccountService>\n"
            return if (close >= 0) xml.substring(0, close) + block + xml.substring(close) else xml + "\n" + block
        }
        val inside = found.groupValues[1]
        val mine = SELECTED.findAll(inside).firstOrNull { attrs(it.groupValues[1])["PersistentId"]?.toLongOrNull() == id }
        val updated = if (mine != null) inside.replaceRange(mine.range, entry) else inside.trimEnd() + "\n        $entry\n    "
        return xml.replaceRange(found.range, "<AccountService>$updated</AccountService>")
    }

    private fun attrs(text: String): Map<String, String> = ATTR.findAll(text).associate { it.groupValues[1] to it.groupValues[2] }
}
