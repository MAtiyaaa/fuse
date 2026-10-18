package io.github.matiyaaa.fuse.launch

import io.github.matiyaaa.fuse.model.LaunchPlan

/**
 * Frame generation for a game through lsfg-vk (Linux), set on the emulator's command as lsfg-vk
 * documents it (lsfg-vk.dev, Configuration, Environment Variables): "set `LSFGVK_ENV=1` and then
 * any of the following variables", among them `LSFGVK_MULTIPLIER`, "Frame generation multiplier".
 * lsfg-vk is set up once its configuration file is there, "located at
 * `~/.config/lsfg-vk/conf.toml`" (or under `XDG_CONFIG_HOME`; lsfg-vk.dev, Configuring lsfg-vk).
 * Data only: whether it is set up is the platform's to say.
 */
object FrameGeneration {
    /** The multipliers offered: off is 0. */
    val MULTIPLIERS = listOf(2, 3, 4)

    /** lsfg-vk's variables for [multiplier]; none for off or a value it isn't offered at. */
    fun env(multiplier: Int): Map<String, String> =
        if (multiplier in MULTIPLIERS) mapOf("LSFGVK_ENV" to "1", "LSFGVK_MULTIPLIER" to multiplier.toString()) else emptyMap()

    /** [plan] with frame generation at [multiplier]: only a command gets it, and only when on. */
    fun apply(plan: LaunchPlan, multiplier: Int): LaunchPlan =
        if (plan is LaunchPlan.Command && multiplier in MULTIPLIERS) plan.copy(env = plan.env + env(multiplier)) else plan

    /** Where lsfg-vk keeps its configuration, from the home folder and `XDG_CONFIG_HOME`. */
    fun configPath(home: String, xdgConfig: String?): String =
        (xdgConfig?.takeIf { it.startsWith("/") } ?: "${home.trimEnd('/')}/.config").trimEnd('/') + "/lsfg-vk/conf.toml"
}
