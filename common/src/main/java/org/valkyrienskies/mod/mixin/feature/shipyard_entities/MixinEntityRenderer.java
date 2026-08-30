package org.valkyrienskies.mod.mixin.feature.shipyard_entities;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import org.joml.Quaternionf;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.valkyrienskies.core.api.ships.ClientShip;
import org.valkyrienskies.mod.common.VSGameUtilsKt;
import org.valkyrienskies.mod.common.entity.ShipMountedToData;

@Mixin(EntityRenderer.class)
public class MixinEntityRenderer {
    /**
     * Entities are rendered along with their name tags, which is good as the ship transforms are already applied.
     * For readability, however, we want the tags to always be vertically oriented so we negate rotation of the ship.
     * We inject specifically after translation as offsetting the tag vertically in shipspace makes more sense.
     */
    @Inject(
        method = "renderNameTag(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/network/chat/Component;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;IF)V",
        at = @At(
            value = "INVOKE",
            target = "Lcom/mojang/blaze3d/vertex/PoseStack;translate(DDD)V",
            shift = At.Shift.AFTER
        )
    )
    private void revertShipRotation(Entity entity, Component component, PoseStack matrices, MultiBufferSource bufferSource, int packedLight,
        float partialTicks, CallbackInfo ci) {
        ClientShip ship = (ClientShip)VSGameUtilsKt.getLoadedShipManagingPos(entity.level(), entity.blockPosition());
        if (ship == null) {
            // A MOUNTED rider never had a ship here, and it is the case that needed the correction most.
            //
            // MixinEntityRenderDispatcher puts an entity into its ship's frame exactly when
            // getShipMountedToData answers -- that is, whenever it rides a ShipMountingEntity -- and that
            // rotation reaches the name tag along with the body. But a rider LIVES at a world position,
            // while the lookup above resolves SHIPYARD positions, so for the one class of entity the ship
            // transform is actually applied to, it returned null and the tag was left turning with the
            // hull. Standing on a deck looked fine; sitting in a seat on the same deck did not.
            //
            // So ask the question the dispatcher asked. The partial tick is irrelevant here: only the ship
            // is wanted, and its render transform is the same object either way.
            final ShipMountedToData mounted = VSGameUtilsKt.getShipMountedToData(entity, null);
            if (mounted != null) {
                ship = (ClientShip) mounted.getShipMountedTo();
            }
        }
        if (ship != null) {
            matrices.mulPose(new Quaternionf(ship.getRenderTransform().getShipToWorldRotation()).invert());
        }
    }
}
