package org.valkyrienskies.mod.compat.voxy;

import com.mojang.logging.LogUtils;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.FloatBuffer;
import net.minecraft.client.renderer.LevelRenderer;
import org.joml.Matrix4f;
import org.joml.Vector4f;
import org.joml.primitives.AABBdc;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL45C;
import org.lwjgl.system.MemoryUtil;
import org.slf4j.Logger;
import org.valkyrienskies.core.api.ships.ClientShip;

/**
 * Read-only Voxy LOD-depth occlusion for VS2 assembled ships (1.21.1 port of the 1.21.11 whole-ship
 * cull).
 *
 * <p>WHY: under a shaderpack, Voxy keeps its LOD terrain OUT of the gbuffer depth, so a ship far
 * behind a distant LOD monument still paints in front of it (X-ray). We READ Voxy's own LOD depth
 * buffer to decide whether a ship is fully hidden and skip drawing it -- never writing anything the
 * shaderpack reads, so there are zero shadow/SSR side-effects on any pack.
 *
 * <p>1.21.1 DIFFERENCES vs the 1.21.11 implementation:
 * <ul>
 * <li>WHOLE-SHIP CULL ONLY -- no {@code VoxyPerPixel} merge. On 1.21.1 ship terrain draws inside the
 *     Sodium terrain passes interleaved with world terrain (per-ship render lists in
 *     {@code RenderSectionManager.renderLayer}), so there is no discrete hull-draw point to bracket
 *     with a scoped depth merge. The cull alone was the shipped, proven solution on 1.21.11 for
 *     months before per-pixel landed.</li>
 * <li>The per-ship verdict cache lives HERE (time-based TTL, staggered per ship) instead of in a
 *     LevelRenderer mixin -- the 1.21.1 call sites (terrain layer draw + ship block entities) have no
 *     shared per-frame hook to stamp frames from. Each verdict costs a synchronous GPU depth
 *     readback, so it is refreshed every ~130 ms, not every frame.</li>
 * <li>IRIS-GATED FROM DAY ONE (the 1.21.11 "vanilla stand-down" fix is built in): without a
 *     shaderpack Voxy blits LOD colour AND depth into the main framebuffer itself, so ships
 *     depth-test against LOD natively and need no cull -- and Voxy's vanilla pipeline copies REAL
 *     terrain depth into the very buffer sampled here, which would false-positive whenever the
 *     camera is near the ground.</li>
 * </ul>
 *
 * <p>NO COMPILE DEPENDENCY ON VOXY: everything is reflection, resolved by name at runtime and
 * cached. Voxy is auto-detected via the {@code voxy$getRenderSystem} method its mixin adds to
 * LevelRenderer. If Voxy is absent / a different version / anything fails, this disables itself and
 * VS2 renders ships exactly as before -- safe to sit static in a build shipped to users without
 * Voxy.
 *
 * <p>CALIBRATION (from the 1.21.11 diagnostic, Voxy 0.2.15-beta): Voxy depth is standard
 * zero-to-one (0 = near, 1 = far). A point is BEHIND LOD terrain when
 * {@code sampledLodDepth < pointNdcZ}. Sky reads ~1.0 (far), so points over sky never count as
 * behind. Points are projected with Voxy's own viewport MVP (camera-relative), so their NDC depth
 * and the sampled LOD depth live in the same clip space -- no near/far linearisation.
 *
 * <p>COVERAGE: we sample a {@code GRID^3} lattice over the render AABB (the ship's on-screen bbox is
 * read in one block; a very large bbox from a close/zoomed ship falls back to per-texel reads) and
 * classify each on-screen point as SKY (no LOD terrain there), BEHIND (LOD in front of it) or FRONT
 * (the point sticks out in front of LOD). The ship is hidden when BEHIND is the majority of the
 * terrain-overlapping points ({@code >= BEHIND_FRAC}) and at least {@code MIN_COVER} of its
 * on-screen points overlap terrain. Granularity is whole-ship: a mostly-hidden ship pops out
 * entirely rather than clipping per-pixel.
 */
public final class VoxyOcclusion {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** Depth margin to avoid culling on coincident / z-fighting depths. */
    private static final float EPS = 0.0005f;

    /**
     * Minimum fraction of a ship's on-screen sample points that must overlap LOD terrain (the rest
     * being open sky / gaps, which are ignored) before it is even eligible to be hidden.
     */
    private static final float MIN_COVER = 0.5f;

    /**
     * Of the sample points that overlap LOD terrain, the fraction that must be BEHIND it to hide the
     * whole ship. 0.5 = a simple majority.
     */
    private static final float BEHIND_FRAC = 0.5f;

    /** LOD depth at/above which a pixel is treated as open sky / no LOD terrain (standard 0..1, far = 1). */
    private static final float SKY_DEPTH = 0.999f;

    /** Samples per axis over the render AABB ({@code GRID^3} points total). */
    private static final int GRID = 5;

    /**
     * Occluder-dilation radius in texels: each sample takes the NEAREST LOD depth in a
     * {@code (2*DILATE+1)^2} screen neighbourhood, filling thin holes a coarse LOD leaves in
     * carved/recessed facades and covering silhouette edges.
     */
    private static final int DILATE = 2;

    /** Max width/height (texels) of the one-shot depth-region readback; larger bboxes use a per-sample read. */
    private static final int MAXDIM = 96;

    /**
     * Per-ship verdict TTL in milliseconds (staggered by ship id below so a fleet doesn't re-read on
     * the same frame). ~8 frames at 60 fps -- whole-ship pop-in latency of a few frames is
     * imperceptible; a synchronous GPU depth readback every frame is not.
     */
    private static final long REOCCLUDE_MS = 130L;

    /** Reusable scratch buffer for the per-ship depth-region readback (render thread only; never freed). */
    private static FloatBuffer regionBuf;

    // Per-ship verdict cache: shipId -> (timestampMillis << 1 | occluded). Render thread only.
    private static final it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap VERDICTS = vs$newVerdictCache();

    private static it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap vs$newVerdictCache() {
        final it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap map =
            new it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap();
        map.defaultReturnValue(Long.MIN_VALUE);
        return map;
    }

    // Voxy presence is detected by reflection (its mixin adds voxy$getRenderSystem to LevelRenderer),
    // so there is no compile/runtime dependency on Voxy or any loader API. Flipped true once we learn
    // Voxy is absent or its internals can't be reached, after which this is a no-op.
    private static boolean failed = false;
    private static boolean resolved = false;
    private static boolean loggedVanillaStandDown = false; // one-shot log for the no-shaderpack gate

    private static Method mGetRenderSystem; // LevelRenderer.voxy$getRenderSystem()
    private static Method mGetViewport;     // VoxyRenderSystem.getViewport()
    private static Field fPipeline;         // VoxyRenderSystem.pipeline (private)
    private static Field fFb;               // AbstractRenderPipeline.fb (public)
    private static Method mGetDepthTex;     // DepthFramebuffer.getDepthTex()
    private static Field fTexId;            // GlTexture.id
    private static Field fVpWidth;
    private static Field fVpHeight;
    private static Field fVpMvp;            // Viewport.MVP (joml Matrix4f, camera-relative clip)
    private static Field fVpCamX;
    private static Field fVpCamY;
    private static Field fVpCamZ;

    // Cached reflective handle to IrisApi.getInstance().isShaderPackInUse() -- no hard dep on Iris.
    private static boolean irisResolved;
    private static Object irisApiInstance;
    private static Method irisIsShaderPackInUse;

    private VoxyOcclusion() {
    }

    /**
     * True when a shaderpack is active (Iris). Resolved reflectively against the stable Iris v0 API;
     * if Iris is absent or anything fails we report "no shaders" and the cull stays down.
     */
    private static boolean shadersActive() {
        if (!irisResolved) {
            irisResolved = true;
            try {
                final Class<?> api = Class.forName("net.irisshaders.iris.api.v0.IrisApi");
                irisApiInstance = api.getMethod("getInstance").invoke(null);
                irisIsShaderPackInUse = api.getMethod("isShaderPackInUse");
            } catch (final Throwable ignored) {
                irisApiInstance = null;
                irisIsShaderPackInUse = null;
            }
        }
        if (irisApiInstance == null || irisIsShaderPackInUse == null) {
            return false;
        }
        try {
            return (Boolean) irisIsShaderPackInUse.invoke(irisApiInstance);
        } catch (final Throwable ignored) {
            return false;
        }
    }

    /**
     * Whether [ship] should be skipped this frame because it is fully hidden behind Voxy LOD terrain.
     * Cheap cached front-end over {@link #isOccludedByLod}: the real verdict (a synchronous GPU depth
     * readback) is refreshed at most every ~{@link #REOCCLUDE_MS} ms per ship, staggered by ship id.
     * Always false without a shaderpack, without Voxy, or on the Iris shadow pass (Voxy's viewport is
     * null there, so a fresh verdict can't be computed -- a cached main-pass verdict may apply, which
     * matches the 1.21.11 behaviour).
     */
    public static boolean isShipOccluded(final LevelRenderer levelRenderer, final ClientShip ship) {
        if (failed || levelRenderer == null || ship == null) {
            return false;
        }
        // VANILLA STAND-DOWN (ported 1.21.11 fix): without a shaderpack this cull must not run. Voxy's
        // vanilla pipeline blits LOD depth into the main framebuffer itself (ships depth-test against
        // LOD natively), AND it copies real MC terrain depth into the buffer sampled here -- the
        // samples would read the real floor between camera and ship as "LOD in front" -> whole-ship
        // false positives whenever the camera is near the ground.
        if (!shadersActive()) {
            if (!loggedVanillaStandDown) {
                loggedVanillaStandDown = true;
                LOGGER.info("[vs voxy-occlusion] no shaderpack active -> cull standing down (vanilla depth-tests LOD natively)");
            }
            return false;
        }
        final long shipId = ship.getId();
        final long now = System.currentTimeMillis();
        final long packed = VERDICTS.get(shipId);
        if (packed != Long.MIN_VALUE && now - (packed >>> 1) < REOCCLUDE_MS + (shipId & 3L) * 16L) {
            return (packed & 1L) != 0L;
        }
        final AABBdc box = ship.getRenderAABB();
        final boolean occluded = isOccludedByLod(levelRenderer,
            box.minX(), box.minY(), box.minZ(), box.maxX(), box.maxY(), box.maxZ());
        VERDICTS.put(shipId, (now << 1) | (occluded ? 1L : 0L));
        return occluded;
    }

    /**
     * @return true if BEHIND is the majority ({@code >= BEHIND_FRAC}) of the ship's terrain-overlapping
     *     sample points and at least {@code MIN_COVER} of its on-screen points overlap terrain, so the
     *     whole ship should be skipped this frame. Returns false (render the ship) when it is near the
     *     camera (a corner behind the near plane), fully off-screen, mostly in front of terrain, or
     *     mostly over open sky / gaps.
     */
    private static boolean isOccludedByLod(final LevelRenderer levelRenderer,
        final double minX, final double minY, final double minZ,
        final double maxX, final double maxY, final double maxZ) {
        try {
            if (mGetRenderSystem == null) {
                try {
                    mGetRenderSystem = levelRenderer.getClass().getMethod("voxy$getRenderSystem");
                } catch (final NoSuchMethodException notVoxy) {
                    failed = true;
                    LOGGER.info("[vs voxy-occlusion] Voxy not present; ship LOD-occlusion disabled");
                    return false;
                }
            }
            final Object vrs = mGetRenderSystem.invoke(levelRenderer);
            if (vrs == null) {
                return false; // renderer not created (Voxy disabled / world loading)
            }
            if (!resolved) {
                resolveHandles(vrs);
                if (!resolved) {
                    return false; // viewport not ready yet
                }
            }
            final Object viewport = mGetViewport.invoke(vrs);
            if (viewport == null) {
                return false; // iris shadow pass etc.
            }
            final Object pipeline = fPipeline.get(vrs);
            if (pipeline == null) {
                return false;
            }
            final Object fb = fFb.get(pipeline);
            if (fb == null) {
                return false;
            }
            final Object depthTex = mGetDepthTex.invoke(fb);
            if (depthTex == null) {
                return false;
            }
            final int texId = fTexId.getInt(depthTex);
            final int w = fVpWidth.getInt(viewport);
            final int h = fVpHeight.getInt(viewport);
            if (texId <= 0 || w <= 0 || h <= 0) {
                return false;
            }
            final Matrix4f mvp = (Matrix4f) fVpMvp.get(viewport);
            final double camX = fVpCamX.getDouble(viewport);
            final double camY = fVpCamY.getDouble(viewport);
            final double camZ = fVpCamZ.getDouble(viewport);

            final double[] xs = {minX, maxX};
            final double[] ys = {minY, maxY};
            final double[] zs = {minZ, maxZ};
            final Vector4f clip = new Vector4f();

            // Project the 8 AABB corners to find the ship's on-screen bounding rectangle. Bail (render
            // the ship) if any corner is behind the camera: that means the ship is very close, where it
            // is drawn from real chunks and occludes naturally -- LOD-culling it there would be wrong.
            float bbMinX = Float.MAX_VALUE;
            float bbMinY = Float.MAX_VALUE;
            float bbMaxX = -Float.MAX_VALUE;
            float bbMaxY = -Float.MAX_VALUE;
            for (int i = 0; i < 8; i++) {
                clip.set((float) (xs[i & 1] - camX), (float) (ys[(i >> 1) & 1] - camY),
                    (float) (zs[(i >> 2) & 1] - camZ), 1.0f);
                mvp.transform(clip);
                if (clip.w <= 0.0f) {
                    return false; // a corner is behind the camera -> near ship, don't LOD-cull
                }
                final float invW = 1.0f / clip.w;
                final float sx = (clip.x * invW * 0.5f + 0.5f) * w;
                final float sy = (clip.y * invW * 0.5f + 0.5f) * h;
                bbMinX = Math.min(bbMinX, sx);
                bbMaxX = Math.max(bbMaxX, sx);
                bbMinY = Math.min(bbMinY, sy);
                bbMaxY = Math.max(bbMaxY, sy);
            }
            // Integer screen rectangle, clamped to the viewport.
            int x0 = (int) Math.floor(bbMinX);
            int y0 = (int) Math.floor(bbMinY);
            int x1 = (int) Math.ceil(bbMaxX);
            int y1 = (int) Math.ceil(bbMaxY);
            x0 = Math.max(0, x0);
            y0 = Math.max(0, y0);
            x1 = Math.min(w - 1, x1);
            y1 = Math.min(h - 1, y1);
            if (x1 < x0 || y1 < y0) {
                return false; // fully off-screen
            }
            final int bw = x1 - x0 + 1;
            final int bh = y1 - y0 + 1;

            // Common case (far / normal-zoom ship, small bbox): read the whole bbox in ONE call -- one
            // GPU sync. For a very large bbox (a close or heavily zoomed ship) fall back to a
            // per-sample texel read so every grid sample stays correct.
            final boolean useRegion = bw <= MAXDIM && bh <= MAXDIM;
            final FloatBuffer region = useRegion ? readDepthRegion(texId, x0, y0, bw, bh) : null;

            // Sample a GRID^3 lattice over the AABB and classify each on-screen point by the LOD depth
            // at its pixel: SKY (ignore), BEHIND (LOD in front), FRONT (ship sticks out past terrain).
            int counted = 0;
            int behind = 0;
            int front = 0;
            for (int gz = 0; gz < GRID; gz++) {
                final double wz = minZ + (maxZ - minZ) * gz / (GRID - 1);
                for (int gy = 0; gy < GRID; gy++) {
                    final double wy = minY + (maxY - minY) * gy / (GRID - 1);
                    for (int gx = 0; gx < GRID; gx++) {
                        final double wx = minX + (maxX - minX) * gx / (GRID - 1);
                        clip.set((float) (wx - camX), (float) (wy - camY), (float) (wz - camZ), 1.0f);
                        mvp.transform(clip);
                        if (clip.w <= 0.0f) {
                            continue;
                        }
                        final float invW = 1.0f / clip.w;
                        final int px = Math.round((clip.x * invW * 0.5f + 0.5f) * w);
                        final int py = Math.round((clip.y * invW * 0.5f + 0.5f) * h);
                        if (px < x0 || px > x1 || py < y0 || py > y1) {
                            continue; // outside the read region
                        }
                        counted++;
                        // Nearest LOD depth in a small neighbourhood (occluder dilation).
                        final float lod = useRegion
                            ? minLodInRegion(region, x0, y0, x1, y1, bw, px, py)
                            : minLodBlock(texId, px, py, w, h);
                        if (lod >= SKY_DEPTH) {
                            continue; // open sky / gap / non-LOD object -> ignore
                        }
                        final float ndcZ = clip.z * invW;
                        if (lod < ndcZ - EPS) {
                            behind++;
                        } else {
                            front++; // ship is in front of LOD terrain here -> genuinely visible
                        }
                    }
                }
            }
            if (counted == 0) {
                return false;
            }
            final int nonSky = behind + front;
            if (nonSky < counted * MIN_COVER) {
                return false; // ship is mostly over open sky / gaps -> visible
            }
            return behind >= nonSky * BEHIND_FRAC;
        } catch (final Throwable t) {
            failed = true;
            LOGGER.warn("[vs voxy-occlusion] reflective access failed; disabling Voxy occlusion", t);
            return false;
        }
    }

    private static void resolveHandles(final Object vrs) throws Exception {
        mGetViewport = vrs.getClass().getMethod("getViewport");
        fPipeline = vrs.getClass().getDeclaredField("pipeline");
        fPipeline.setAccessible(true);
        final Object pipeline = fPipeline.get(vrs);
        if (pipeline == null) {
            return; // pipeline not built yet
        }
        fFb = pipeline.getClass().getField("fb"); // public, declared in AbstractRenderPipeline
        final Object fb = fFb.get(pipeline);
        if (fb == null) {
            return; // pipeline framebuffer not built yet (e.g. the Iris shadow pass) -- retry later
        }
        mGetDepthTex = fb.getClass().getMethod("getDepthTex");
        final Object depthTex = mGetDepthTex.invoke(fb);
        fTexId = depthTex.getClass().getDeclaredField("id");
        fTexId.setAccessible(true);
        final Object viewport = mGetViewport.invoke(vrs);
        if (viewport == null) {
            return; // resolve viewport fields once a viewport exists
        }
        fVpWidth = viewport.getClass().getField("width");
        fVpHeight = viewport.getClass().getField("height");
        fVpMvp = viewport.getClass().getField("MVP");
        fVpCamX = viewport.getClass().getField("cameraX");
        fVpCamY = viewport.getClass().getField("cameraY");
        fVpCamZ = viewport.getClass().getField("cameraZ");
        resolved = true;
        LOGGER.info("[vs voxy-occlusion] resolved Voxy reflective handles (depthTexClass={}, viewportClass={})",
            depthTex.getClass().getName(), viewport.getClass().getName());
    }

    /**
     * Read a {@code bw x bh} block of Voxy's LOD depth texture starting at (x, y) in ONE call into a
     * reusable scratch buffer. GL texture origin is bottom-left, so the value at screen (px, py) is at
     * index {@code (py - y) * bw + (px - x)}.
     */
    private static FloatBuffer readDepthRegion(final int texId, final int x, final int y,
        final int bw, final int bh) {
        if (regionBuf == null) {
            regionBuf = MemoryUtil.memAllocFloat(MAXDIM * MAXDIM);
        }
        final FloatBuffer buf = regionBuf;
        buf.clear();
        buf.limit(bw * bh);
        GL45C.glGetTextureSubImage(texId, 0, x, y, 0, bw, bh, 1,
            GL11C.GL_DEPTH_COMPONENT, GL11C.GL_FLOAT, buf);
        return buf;
    }

    /**
     * Nearest (smallest) LOD depth in a {@code (2*DILATE+1)^2} screen neighbourhood of (px, py), read
     * from the already-fetched region buffer and clamped to the region bounds.
     */
    private static float minLodInRegion(final FloatBuffer region, final int x0, final int y0,
        final int x1, final int y1, final int bw, final int px, final int py) {
        float m = Float.MAX_VALUE;
        for (int dy = -DILATE; dy <= DILATE; dy++) {
            final int sy = py + dy;
            if (sy < y0 || sy > y1) {
                continue;
            }
            for (int dx = -DILATE; dx <= DILATE; dx++) {
                final int sx = px + dx;
                if (sx < x0 || sx > x1) {
                    continue;
                }
                final float v = region.get((sy - y0) * bw + (sx - x0));
                if (v < m) {
                    m = v;
                }
            }
        }
        return m;
    }

    /**
     * Same neighbourhood-min as {@link #minLodInRegion}, but for the per-sample fallback path used
     * when a ship's bbox is larger than {@link #MAXDIM} (a close / zoomed ship).
     */
    private static float minLodBlock(final int texId, final int x, final int y, final int w, final int h) {
        final int x0 = Math.max(0, x - DILATE);
        final int y0 = Math.max(0, y - DILATE);
        final int x1 = Math.min(w - 1, x + DILATE);
        final int y1 = Math.min(h - 1, y + DILATE);
        final int bw = x1 - x0 + 1;
        final int bh = y1 - y0 + 1;
        if (regionBuf == null) {
            regionBuf = MemoryUtil.memAllocFloat(MAXDIM * MAXDIM);
        }
        final FloatBuffer buf = regionBuf;
        buf.clear();
        buf.limit(bw * bh);
        GL45C.glGetTextureSubImage(texId, 0, x0, y0, 0, bw, bh, 1,
            GL11C.GL_DEPTH_COMPONENT, GL11C.GL_FLOAT, buf);
        float m = Float.MAX_VALUE;
        for (int i = 0; i < bw * bh; i++) {
            final float v = buf.get(i);
            if (v < m) {
                m = v;
            }
        }
        return m;
    }
}
