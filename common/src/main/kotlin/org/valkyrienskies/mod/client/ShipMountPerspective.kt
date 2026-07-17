package org.valkyrienskies.mod.client

import net.minecraft.client.CameraType
import net.minecraft.client.Minecraft

/**
 * The F5 perspective cycle while mounted on a ShipMountingEntity (helm, reconnect auto-seat,
 * sit-down hotkey seat).
 *
 * Vanilla's CameraType enum only has three values, so the immersive ship-mounted view can't be a
 * real camera type. Instead it is a VIRTUAL slot layered on top of THIRD_PERSON_BACK: [shipView]
 * marks it active and MixinGameRenderer engages the pulled-back ship camera; the underlying camera
 * type stays THIRD_PERSON_BACK so the player's BACK faces the camera -- a natural behind-the-player
 * view. (It used to ride on THIRD_PERSON_FRONT, which put the player FACING the camera; that
 * mirrored slot is what made the ship view's pitch feel inverted, so it was dropped.) The mounted
 * cycle now has a single, back-facing 3rd-person slot:
 *
 *   FIRST_PERSON -> ship view (behind the player, scroll-zoomable)
 *     -> Shoulder Surfing (only when that mod is installed) -> FIRST_PERSON
 *
 * The cycle is driven from the platform START-of-client-tick hook, which drains the F5 key while
 * mounted BEFORE vanilla handleKeybinds (mid-tick) and Shoulder Surfing's end-of-tick handler ever
 * see the clicks. On foot the key is left alone, so vanilla and every camera mod behave as stock.
 *
 * Shoulder Surfing Reloaded is reached through its public API (api.client.ShoulderSurfing) via
 * reflection -- no compile-time dependency, and its own MixinOptions interception of
 * Options.setCameraType keeps its internal state consistent when we set the vanilla slots.
 */
object ShipMountPerspective {

    /** True while the virtual ship-view slot is selected. Only meaningful while mounted. */
    @Volatile
    private var shipView = false

    /**
     * Render-path gate (MixinGameRenderer): the ship camera engages only in the virtual slot, and
     * only while the underlying camera type is still THIRD_PERSON_BACK (something else -- e.g. a
     * Shoulder Surfing hotkey -- changing the camera type out from under us invalidates the slot;
     * [tickMounted] then clears the flag). The plain THIRD_PERSON_BACK view has [shipView] == false,
     * so it is NOT treated as the ship view.
     */
    @JvmStatic
    fun isShipViewEngaged(): Boolean =
        shipView && Minecraft.getInstance().options.cameraType == CameraType.THIRD_PERSON_BACK

    @JvmStatic
    fun reset() {
        shipView = false
    }

    /**
     * Per-tick invariant while mounted: the virtual slot is only valid on top of a plain
     * THIRD_PERSON_BACK. If another mod's hotkey switched the camera type or engaged Shoulder
     * Surfing while we were in the ship view, drop the flag so the cycle resumes from whatever
     * they picked.
     */
    @JvmStatic
    fun tickMounted() {
        if (shipView &&
            (Minecraft.getInstance().options.cameraType != CameraType.THIRD_PERSON_BACK ||
                ShoulderSurfingCompat.isShoulderSurfing())
        ) {
            shipView = false
        }
    }

    /** Advance one slot in the mounted cycle. Called once per queued F5 click. */
    @JvmStatic
    fun cycleMounted(mc: Minecraft) {
        when {
            // Ship view -> Shoulder Surfing when installed as an EXTRA perspective, else wrap
            // to first person. With replace_default_perspective=true SSR replaces the back slot,
            // so it doesn't get a second slot here.
            shipView -> {
                shipView = false
                withVanillaToggleParity(mc) {
                    if (ShoulderSurfingCompat.replacesDefaultPerspective() ||
                        !ShoulderSurfingCompat.enterShoulderSurfing()
                    ) {
                        mc.options.cameraType = CameraType.FIRST_PERSON
                    }
                }
            }
            // Shoulder Surfing active (via our cycle or SSR's own hotkeys). SSR intercepts
            // Options.setCameraType, so a plain set both changes the slot and clears its shoulder
            // state. There is no longer a front stop, so exiting SSR always wraps to first person.
            ShoulderSurfingCompat.isShoulderSurfing() -> {
                withVanillaToggleParity(mc) { mc.options.cameraType = CameraType.FIRST_PERSON }
            }
            // Anything else (first person, or a stray third-person slot) -> the single virtual
            // ship-view slot: a back-facing, scroll-zoomable behind-the-player camera. Engaging it
            // needs the underlying vanilla slot to be THIRD_PERSON_BACK (player's back to camera);
            // set that, then flag the virtual slot so MixinGameRenderer swaps in the pulled-back
            // ship camera. There is no separate plain back/front stop -- this is the only mounted
            // 3rd-person view.
            else -> {
                if (mc.options.cameraType != CameraType.THIRD_PERSON_BACK) {
                    withVanillaToggleParity(mc) { mc.options.cameraType = CameraType.THIRD_PERSON_BACK }
                }
                shipView = true
                mc.levelRenderer.needsUpdate()
            }
        }
    }

    /**
     * Mirror what vanilla handleKeybinds does around a perspective change: fix up the spectated
     * entity's post-effect shader on first<->third transitions and mark the level renderer dirty.
     * Needed because our changes bypass that code, and Shoulder Surfing's changePerspective only
     * does the needsUpdate half.
     */
    private inline fun withVanillaToggleParity(mc: Minecraft, change: () -> Unit) {
        val wasFirstPerson = mc.options.cameraType.isFirstPerson
        change()
        val isFirstPerson = mc.options.cameraType.isFirstPerson
        if (wasFirstPerson != isFirstPerson) {
            mc.gameRenderer.checkEntityPostEffect(if (isFirstPerson) mc.cameraEntity else null)
        }
        mc.levelRenderer.needsUpdate()
    }
}

/**
 * Reflection bridge to Shoulder Surfing Reloaded's public API. Resolves once on first use; every
 * accessor degrades to "not installed" on any failure, so VS2 carries no dependency on the mod.
 */
private object ShoulderSurfingCompat {

    private class Api(
        val getInstance: java.lang.reflect.Method,
        val isShoulderSurfing: java.lang.reflect.Method,
        val changePerspective: java.lang.reflect.Method,
        val getClientConfig: java.lang.reflect.Method,
        val replaceDefaultPerspective: java.lang.reflect.Method,
        val shoulderSurfing: Any,
    )

    private val api: Api? = try {
        val entry = Class.forName("com.github.exopandora.shouldersurfing.api.client.ShoulderSurfing")
        val iface = Class.forName("com.github.exopandora.shouldersurfing.api.client.IShoulderSurfing")
        val config = Class.forName("com.github.exopandora.shouldersurfing.api.client.IClientConfig")
        val perspective = Class.forName("com.github.exopandora.shouldersurfing.api.model.Perspective")
        val shoulderSurfing = perspective.enumConstants.first { (it as Enum<*>).name == "SHOULDER_SURFING" }
        Api(
            entry.getMethod("getInstance"),
            iface.getMethod("isShoulderSurfing"),
            iface.getMethod("changePerspective", perspective),
            iface.getMethod("getClientConfig"),
            config.getMethod("replaceDefaultPerspective"),
            shoulderSurfing,
        )
    } catch (t: Throwable) {
        null
    }

    fun isShoulderSurfing(): Boolean {
        val a = api ?: return false
        return try {
            a.isShoulderSurfing.invoke(a.getInstance.invoke(null)) as Boolean
        } catch (t: Throwable) {
            false
        }
    }

    fun replacesDefaultPerspective(): Boolean {
        val a = api ?: return false
        return try {
            a.replaceDefaultPerspective.invoke(a.getClientConfig.invoke(a.getInstance.invoke(null))) as Boolean
        } catch (t: Throwable) {
            false
        }
    }

    /** Returns false when the mod is absent (or the call failed), so the caller can fall back. */
    fun enterShoulderSurfing(): Boolean {
        val a = api ?: return false
        return try {
            a.changePerspective.invoke(a.getInstance.invoke(null), a.shoulderSurfing)
            true
        } catch (t: Throwable) {
            false
        }
    }
}
