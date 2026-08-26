package org.valkyrienskies.mod.mixin.client.renderer;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.feature.FeatureRenderDispatcher;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.valkyrienskies.mod.common.render.ShipTerrainMeshCache;
import org.valkyrienskies.mod.compat.voxy.VoxyPerPixel;

/**
 * Flush the ship-terrain GPU-buffer draw queue at the correct point in the frame.
 *
 * <p>The GPU render path bakes each ship section once into a persistent vertex buffer and redraws it
 * every frame with a per-section model-view, instead of re-emitting every block's vertices. The queue
 * is BUILT at submitBlockEntities TAIL (see MixinLevelRenderer), but it must not be DRAWN there: at
 * submit time Iris has not yet bound its gbuffer target, so the draw lands on the wrong framebuffer
 * and is composited away (the ship renders correctly from submit time only when no shaderpack is
 * active). renderAllFeatures is called immediately after submitBlockEntities and is where vanilla then
 * flushes this frame's immediate ship terrain (solidMovingBlock) -- Iris's target override is live
 * here. Flushing at TAIL puts the GPU draws at the same point, so they render under shaders too.
 *
 * <p>With no shaderpack the queue is instead flushed at HEAD (flushDeferredGpuDrawsEarly): the moving-block
 * types are not fixed buffers, so the shared-buffer type switch draws ship translucent mid-features, and the
 * hull must already be in the framebuffer or it loses the depth test behind every translucent surface (the
 * old shaders-off xray). Solid-vs-solid needs no ordering, so drawing before the features is harmless.
 *
 * <p>renderAllFeatures is invoked more than once per frame; per-pass flags draw the queue at most once per
 * pass. No-op entirely when the GPU path is off.
 */
@Mixin(FeatureRenderDispatcher.class)
public abstract class MixinFeatureRenderDispatcher {

    // Per-pixel LOD occlusion: merge Voxy's LOD depth into the gbuffer BEFORE the hull (which draws
    // within renderAllFeatures), so the hull depth-tests against LOD. No-op on the shadow pass or when
    // per-pixel can't run (then the dilation cull in VoxyOcclusion handles it instead).
    @Inject(method = "renderAllFeatures", at = @At("HEAD"), require = 1)
    private void valkyrienskies$mergeLodDepthBeforeHull(final CallbackInfo ci) {
        VoxyPerPixel.beforeHull(Minecraft.getInstance().levelRenderer);
        // NO-shaderpack GPU path: draw the hull BEFORE the feature draws + immediate moving-block flush,
        // so ship translucent (water, stained glass) blends against the hull instead of xraying through to
        // the world. Iris frames no-op here and keep the TAIL flush (gbuffer target binds after this point).
        ShipTerrainMeshCache.INSTANCE.flushDeferredGpuDrawsEarly();
    }

    @Inject(method = "renderAllFeatures", at = @At("TAIL"), require = 1)
    private void valkyrienskies$flushShipGpuDraws(final CallbackInfo ci) {
        ShipTerrainMeshCache.INSTANCE.flushDeferredGpuDraws();
        // Per-pixel LOD occlusion: selectively restore the gbuffer depth (remove the LOD primer, keep
        // hull + entities) so the shaderpack's shadow/SSR passes never see LOD -> no shadow glitch.
        VoxyPerPixel.afterHull(Minecraft.getInstance().levelRenderer);
    }
}
