package org.valkyrienskies.mod.mixin.client.renderer;

import static org.valkyrienskies.mod.common.VSClientGameUtils.transformRenderWithShip;

import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyConstant;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.valkyrienskies.core.api.ships.ClientShip;
import org.valkyrienskies.mod.common.VSGameUtilsKt;

@Mixin(LevelRenderer.class)
public abstract class MixinLevelRenderer {

    @Shadow
    private @Nullable ClientLevel level;

    @Shadow
    @Final
    private Minecraft minecraft;

    @Unique private PoseStack matrixStack;
    @Unique private Vec3 camera;

    // When the ship-mount camera pulls the camera far back from the player, vanilla's
    // EntityRenderDispatcher.shouldRender returns false for the mounted LocalPlayer because
    // Entity.shouldRender(d,e,f) does a distance cull: dist < bb.getSize() * 64 * viewScale.
    // With entityDistanceScaling < 1.0 that threshold drops below the ship pullback and the
    // standing-mounted player gets culled. Bypass the distance cull while still consulting the
    // frustum directly, so off-screen is still off-screen.
    @WrapOperation(
        method = "renderLevel",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/entity/EntityRenderDispatcher;shouldRender(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/client/renderer/culling/Frustum;DDD)Z"
        ),
        require = 1
    )
    private boolean valkyrienskies$forceShouldRenderForMountedPlayer(
        final EntityRenderDispatcher dispatcher,
        final Entity entity, final Frustum frustum,
        final double d, final double e, final double f,
        final Operation<Boolean> original) {
        if (!(entity instanceof LocalPlayer)) {
            return original.call(dispatcher, entity, frustum, d, e, f);
        }
        final Entity vehicle = entity.getVehicle();
        // Every ShipMountingEntity rider renders standing.
        if (!(vehicle instanceof org.valkyrienskies.mod.common.entity.ShipMountingEntity)) {
            return original.call(dispatcher, entity, frustum, d, e, f);
        }
        return frustum.isVisible(entity.getBoundingBox().inflate(0.5));
    }

    // Force camera.isDetached() to true for the standing-mounted LocalPlayer so they survive the
    // 1st-person skip in renderLevel. Backstop in case another mod (Iris shadow pass, etc.) resets
    // the live camera between updateCamera and the cull loop. Bail out when the user is actually in
    // 1st person at a helm: lying here then makes vanilla skip the "don't render own entity in 1st
    // person" branch and the body renders at the eye position (looking inside our own head).
    @WrapOperation(
        method = "renderLevel",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/Camera;isDetached()Z"
        ),
        require = 1
    )
    private boolean valkyrienskies$forceDetachedForShipMount(final Camera camera,
        final Operation<Boolean> original, @Local final Entity entity) {

        if (original.call(camera)) {
            return true;
        }
        if (this.minecraft.options.getCameraType().isFirstPerson()) {
            return false;
        }
        if (entity != camera.getEntity()) {
            return false;
        }
        final Entity vehicle = entity.getVehicle();
        // Every ShipMountingEntity rider renders standing.
        if (!(vehicle instanceof org.valkyrienskies.mod.common.entity.ShipMountingEntity)) {
            return false;
        }
        return true;
    }

    @Shadow
    private static void renderShape(final PoseStack matrixStack, final VertexConsumer vertexConsumer,
        final VoxelShape voxelShape, final double d, final double e, final double f, final float red, final float green,
        final float blue, final float alpha) {
        throw new AssertionError();
    }

    /**
     * @reason This mixin forces the game to always render block damage.
     */
    @ModifyConstant(
        method = "renderLevel",
        constant = @Constant(
            doubleValue = 1024,
            ordinal = 0
        ))
    private double disableBlockDamageDistanceCheck(final double originalBlockDamageDistanceConstant) {
        return Double.MAX_VALUE;
    }

    /**
     * @reason mojank developers who wrote this don't quite understand what a matrixstack is apparently
     * @author Rubydesic
     */
    @Inject(method = "renderHitOutline", at = @At("HEAD"), cancellable = true)
    private void preRenderHitOutline(final PoseStack matrixStack, final VertexConsumer vertexConsumer,
        final Entity entity, final double camX, final double camY, final double camZ, final BlockPos blockPos,
        final BlockState blockState, final CallbackInfo ci) {
        ci.cancel();
        final ClientShip ship = VSGameUtilsKt.getLoadedShipManagingPos(level, blockPos);
        if (ship != null) {
            matrixStack.pushPose();
            transformRenderWithShip(ship.getRenderTransform(), matrixStack, blockPos, camX, camY, camZ);
            renderShape(matrixStack, vertexConsumer,
                blockState.getShape(this.level, blockPos, CollisionContext.of(entity)),
                0d, 0d, 0d, 0.0F, 0.0F, 0.0F, 0.4F);
            matrixStack.popPose();
        } else {
            // vanilla
            renderShape(matrixStack, vertexConsumer,
                blockState.getShape(this.level, blockPos, CollisionContext.of(entity)),
                (double) blockPos.getX() - camX,
                (double) blockPos.getY() - camY,
                (double) blockPos.getZ() - camZ,
                0.0F, 0.0F, 0.0F, 0.4F);
        }
    }


    /**
     * This mixin makes block damage render on ships.
     */
    /*
    @WrapOperation(method = "renderLevel", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/client/renderer/block/BlockRenderDispatcher;renderBreakingTexture(Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/BlockAndTintGetter;Lcom/mojang/blaze3d/vertex/PoseStack;Lcom/mojang/blaze3d/vertex/VertexConsumer;)V"))
    private void renderBlockDamage(final BlockRenderDispatcher blockRenderManager, final BlockState state,
        final BlockPos blockPos, final BlockAndTintGetter blockRenderWorld, final PoseStack matrix,
        final VertexConsumer vertexConsumer, final Operation<Void> renderBreakingTexture) {


        final ClientShip ship = VSGameUtilsKt.getLoadedShipManagingPos(level, blockPos);
        if (ship != null) {
            // Remove the vanilla render transform
            matrixStack.popPose();

            // Add the VS render transform
            matrixStack.pushPose();

            final ShipTransform renderTransform = ship.getRenderTransform();
            final Vec3 cameraPos = methodCamera.getPosition();

            transformRenderWithShip(renderTransform, matrixStack, blockPos, cameraPos.x, cameraPos.y, cameraPos.z);

            final Matrix3f newNormalMatrix = matrixStack.last().normal().copy();
            final Matrix4f newModelMatrix = matrixStack.last().pose().copy();

            // Then update the matrices in vertexConsumer (I'm guessing vertexConsumer is responsible for mapping
            // textures, so we need to update its matrices otherwise the block damage texture looks wrong)
            final SheetedDecalTextureGenerator newVertexConsumer =
                new SheetedDecalTextureGenerator(((OverlayVertexConsumerAccessor) vertexConsumer).getDelegate(),
                    newModelMatrix, newNormalMatrix);

            // Finally, invoke the render damage function.
            renderBreakingTexture.call(blockRenderManager, state, blockPos, blockRenderWorld, matrix,
                newVertexConsumer);
        } else {
            // Vanilla behavior
            renderBreakingTexture.call(blockRenderManager, state, blockPos, blockRenderWorld, matrix, vertexConsumer);
        }
    }

     */

    /**
     * If an entity, for example an arrow stuck on a ship, is attached outside the border of the ship's chunk claim,
     * it won't be rendered because the chunk isn't compiled.
     * This injector bypasses that if the entity is in shipyard next to the compiled chunk.
     */
    @WrapOperation(
        method = "renderLevel",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/LevelRenderer;isChunkCompiled(Lnet/minecraft/core/BlockPos;)Z")
    )
    private boolean isInShipyardBorder(LevelRenderer instance, BlockPos blockPos, Operation<Boolean> original){
        if(original.call(instance, blockPos)) return true;
        if(VSGameUtilsKt.isBlockInShipyard(level, blockPos)) {
            BlockPos blockPos1 = blockPos.offset(-1, -1, -1);
            BlockPos blockPos2 = blockPos.offset(1, 1, 1);
            for(BlockPos neighbor : BlockPos.betweenClosed(blockPos1, blockPos2)) {
                if (original.call(instance, neighbor)) return true;
            }
        }
        return false;
    }
}
