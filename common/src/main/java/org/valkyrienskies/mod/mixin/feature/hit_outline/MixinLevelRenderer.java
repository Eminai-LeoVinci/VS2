package org.valkyrienskies.mod.mixin.feature.hit_outline;

import static org.valkyrienskies.mod.common.VSClientGameUtils.transformRenderWithShip;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import javax.annotation.Nullable;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.ShapeRenderer;
import net.minecraft.client.renderer.state.BlockOutlineRenderState;
import net.minecraft.util.ARGB;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.valkyrienskies.core.api.ships.ClientShip;
import org.valkyrienskies.mod.common.VSGameUtilsKt;

@Mixin(LevelRenderer.class)
public class MixinLevelRenderer {

    @Shadow
    @Nullable
    private ClientLevel level;

    /**
     * @reason 1.21.11 rewrote the block-outline pass. renderHitOutline no longer takes
     *     (Entity, BlockPos, BlockState) and no longer calls the old private renderShape -- it now reads a
     *     {@link BlockOutlineRenderState} record and draws through {@code ShapeRenderer.renderShape}. That
     *     new signature is why the old hit_outline mixin couldn't apply and was dropped from the 1.21.11
     *     port, leaving ship blocks un-highlighted (their outline is built at the shipyard BlockPos, which
     *     vanilla draws far from the camera). Redraw the outline through the ship's render transform so a
     *     hovered block on an assembled ship gets the same grey border as anywhere else -- restoring the
     *     behavior 1.21.1 / 1.20.1 still have.
     */
    @Inject(method = "renderHitOutline", at = @At("HEAD"), cancellable = true)
    private void valkyrienskies$shipHitOutline(PoseStack matrixStack, VertexConsumer vertexConsumer,
        double camX, double camY, double camZ,
        BlockOutlineRenderState state, int color, float lineWidth,
        CallbackInfo ci
    ) {
        final ClientLevel clientLevel = this.level;
        if (clientLevel == null) {
            return;
        }
        final ClientShip ship = VSGameUtilsKt.getLoadedShipManagingPos(clientLevel, state.pos());
        if (ship != null) {
            matrixStack.pushPose();
            transformRenderWithShip(ship.getRenderTransform(), matrixStack, state.pos(), camX, camY, camZ);
            // Black at 0.4 alpha -- matches the outline the old (1.21.1 / 1.20.1) mixin draws. The vanilla
            // per-frame line width flows straight through so the border thickness stays correct.
            ShapeRenderer.renderShape(matrixStack, vertexConsumer, state.shape(),
                0.0, 0.0, 0.0, ARGB.colorFromFloat(0.4F, 0.0F, 0.0F, 0.0F), lineWidth);
            matrixStack.popPose();
            ci.cancel();
        }
    }
}
