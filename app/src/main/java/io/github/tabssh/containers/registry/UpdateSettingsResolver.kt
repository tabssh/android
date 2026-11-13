package io.github.tabssh.containers.registry

/** Which tier of the update settings decided a value. */
enum class UpdateTier { GLOBAL, HOST, STACK, IMAGE }

/** A resolved setting value plus the tier it came from, so the UI can show "inherited from host". */
data class Resolved<T>(val value: T, val tier: UpdateTier)

/** Effective update settings for one container after all three tiers are applied. */
data class UpdateSettings(
    val enabled: Resolved<Boolean>,
    val autoApply: Resolved<Boolean>,
    val intervalHours: Resolved<Long>
)

/**
 * Pure resolution of the three update-setting tiers. Most specific wins:
 * per-image (container policy) over per-stack over per-host over global.
 * A null override means "inherit from the next tier up". The check interval
 * only exists at the host and global tiers — a single image has no cadence of
 * its own, the host's check pass covers it.
 */
object UpdateSettingsResolver {

    fun resolve(
        globalEnabled: Boolean,
        globalAutoApply: Boolean,
        globalIntervalHours: Long,
        hostEnabled: Boolean?,
        hostAutoApply: Boolean?,
        hostIntervalHours: Int?,
        stackEnabled: Boolean? = null,
        stackAutoApply: Boolean? = null,
        imageEnabled: Boolean? = null,
        imageAutoApply: Boolean? = null
    ): UpdateSettings = UpdateSettings(
        enabled = pick(globalEnabled, hostEnabled, stackEnabled, imageEnabled),
        autoApply = pick(globalAutoApply, hostAutoApply, stackAutoApply, imageAutoApply),
        intervalHours = hostIntervalHours?.takeIf { it > 0 }
            ?.let { Resolved(it.toLong(), UpdateTier.HOST) }
            ?: Resolved(globalIntervalHours, UpdateTier.GLOBAL)
    )

    private fun pick(global: Boolean, host: Boolean?, stack: Boolean?, image: Boolean?): Resolved<Boolean> = when {
        image != null -> Resolved(image, UpdateTier.IMAGE)
        stack != null -> Resolved(stack, UpdateTier.STACK)
        host != null -> Resolved(host, UpdateTier.HOST)
        else -> Resolved(global, UpdateTier.GLOBAL)
    }
}
