package org.valkyrienskies.mod.fabric.common

import com.mojang.blaze3d.vertex.PoseStack
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.LevelRenderer
import net.minecraft.client.renderer.RenderType
import net.minecraft.world.phys.AABB
import org.valkyrienskies.mod.client.ShipDebugRender
import org.valkyrienskies.mod.common.VSClientGameUtils
import org.valkyrienskies.mod.common.config.VSClientConfig
import org.valkyrienskies.mod.common.shipObjectWorld
import org.valkyrienskies.mod.common.util.ShipInfluenceOrientation

/**
 * Draws each loaded ship's "influence border" as a thin blue oriented wireframe box when
 * `/vs influence-border true` is active.
 *
 * The border is the ship-space voxel AABB ([org.valkyrienskies.core.api.ships.ClientShip.getShipAABB]) inflated
 * per-FACE by [VSClientConfig.Client.influenceExtendLeft]/Right/Bottom/Top/Back/Front -- i.e. the EXACT region
 * [org.valkyrienskies.mod.common.util.EntityDragger] tests to decide whether an airborne player (who jumped or
 * walked off a moving ship) is still carried. It is drawn in ship-space and pushed out through the ship's
 * interpolated render transform, so the box is ORIENTED: it rotates/tilts with the hull and always reflects the
 * live influenceExtend values.
 *
 * ## 1.20.1 port note
 * The upstream 1.21.11 renderer uses the post-1.21.5 render APIs (`ShapeRenderer.renderShape` + `RenderTypes.lines()`
 * + `WorldRenderContext.matrices()`), none of which exist on 1.20.1. This uses the 1.20.1 pipeline: an axis-aligned
 * [LevelRenderer.renderLineBox] into the [RenderType.lines] buffer, drawn under the ship render transform on
 * [WorldRenderContext.matrixStack] so it comes out oriented. [WorldRenderEvents.AFTER_ENTITIES] fires inside the
 * main level pass with the camera-relative pose stack already set and the consumers wired to the level renderer's
 * own buffer source (the same immediate pass vanilla entity hitboxes use). Shared verbatim with the 1.21.1 port.
 */
object ShipInfluenceBorderRenderer {

    // renderLineBox tint, matching the old VS2 renderLineBox(0f, 0.45f, 1f, 1f) slightly-cyan blue.
    private const val R = 0.0f
    private const val G = 0.45f
    private const val B = 1.0f
    private const val A = 1.0f

    /** Register the per-frame draw. Call once from the Fabric client initializer. */
    fun register() {
        WorldRenderEvents.AFTER_ENTITIES.register { render(it) }
    }

    private fun render(context: WorldRenderContext) {
        if (!ShipDebugRender.influenceBorder) return

        val mc = Minecraft.getInstance()
        val world = mc.level ?: return

        val poseStack: PoseStack = context.matrixStack() ?: return
        val consumer = context.consumers()?.getBuffer(RenderType.lines()) ?: return

        // Camera position the world renderer is rendering relative to (matrixStack() is camera-relative).
        val cam = mc.gameRenderer.mainCamera.position

        // Per-FACE outward inflation, read LIVE each frame so config edits show immediately. The four horizontal
        // faces are HELM-oriented per ship (Front/Back/Left/Right rotated onto the ship's real axes by its learned
        // forward), identical to EntityDragger's carry test; Top/Bottom stay -Y/+Y.
        val extLeft = VSClientConfig.CLIENT.influenceExtendLeft
        val extRight = VSClientConfig.CLIENT.influenceExtendRight
        val extBottom = VSClientConfig.CLIENT.influenceExtendBottom
        val extTop = VSClientConfig.CLIENT.influenceExtendTop
        val extBack = VSClientConfig.CLIENT.influenceExtendBack
        val extFront = VSClientConfig.CLIENT.influenceExtendFront

        for (ship in world.shipObjectWorld.loadedShips) {
            val shipAABB = ship.shipAABB ?: continue

            // Inflated ship-space influence box (matches EntityDragger per-face; can be ASYMMETRIC, so the box center
            // below is the midpoint of these inflated bounds, not the ship-AABB center).
            val h = ShipInfluenceOrientation.horizontalExtents(
                ShipInfluenceOrientation.forwardFor(ship.id), extFront, extBack, extLeft, extRight
            )
            val minX = shipAABB.minX() - h[0]
            val maxX = shipAABB.maxX() + h[1]
            val minY = shipAABB.minY() - extBottom
            val maxY = shipAABB.maxY() + extTop
            val minZ = shipAABB.minZ() - h[2]
            val maxZ = shipAABB.maxZ() + h[3]

            // Offset the box by -center so the wireframe is built near the origin (avoids float error far from
            // ship-space 0), then put the center back via the render transform below.
            val cx = (minX + maxX) * 0.5
            val cy = (minY + maxY) * 0.5
            val cz = (minZ + maxZ) * 0.5

            poseStack.pushPose()
            // Last = Last * translate(-cam) * shipToWorld * translate(center): orients the box with the hull and
            // restores the center we subtracted above.
            VSClientGameUtils.transformRenderWithShip(
                ship.renderTransform, poseStack, cx, cy, cz, cam.x, cam.y, cam.z
            )
            LevelRenderer.renderLineBox(
                poseStack, consumer,
                AABB(minX - cx, minY - cy, minZ - cz, maxX - cx, maxY - cy, maxZ - cz),
                R, G, B, A
            )
            poseStack.popPose()
        }
    }
}
