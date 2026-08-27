package org.valkyrienskies.mod.mixin.mod_compat.voxy;

import com.mojang.logging.LogUtils;
import java.util.concurrent.atomic.AtomicLong;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;
import org.slf4j.Logger;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.valkyrienskies.mod.common.VS2ChunkAllocator;

/**
 * Keep the shipyard out of Voxy's LOD store.
 *
 * <p>Voxy has no idea Valkyrien Skies exists -- nothing in it mentions ships or the shipyard -- and
 * its client ingest is driven off Sodium's chunk tracker. VS2 feeds that tracker for ship chunks
 * too ({@code MixinClientChunkCache} calls {@code SodiumCompat.onChunkAdded} inside its shipyard
 * branch, so the ship renderer learns the geometry arrived), and Voxy's hook on
 * {@code RenderSectionManager.onChunkAdded} cannot tell the two apart. It then asks the chunk source
 * for the chunk -- which VS2 obligingly answers out of its own ship-chunk map -- and voxelises a
 * ship.
 *
 * <p>Nothing good comes of that. The shipyard lives out past 28 million blocks, no LOD will ever be
 * drawn there, and every hull assembled spends voxelisation, mip generation and disk on data that
 * exists only to be ignored. Assembled ships are drawn by VS2's own renderer at any distance; they
 * are not terrain and have no business in a terrain LOD.
 *
 * <p>String-targeted with no compile or runtime dependency on Voxy, and non-required, so the whole
 * thing is a silent no-op when Voxy is absent or has moved this method. The first block is logged at
 * INFO precisely once per session -- it costs one line and it is the only proof that this path is
 * live, which is otherwise very hard to see from outside.
 *
 * <p>NOT guarded: {@code rawIngest}, Voxy's per-section path off {@code ClientLevel.setBlocksDirty}.
 * It fires only when a block is set to AIR on a section boundary, its first parameter is a
 * Voxy-internal type that would have to be {@code @Coerce}d, and the chunk-level guard here covers
 * every bulk path. Left alone deliberately rather than overlooked.
 */
@Pseudo
@Mixin(targets = "me.cortex.voxy.common.world.service.VoxelIngestService", remap = false)
public abstract class MixinVoxyIngestShipyardGuard {

    private static final Logger valkyrienskies$LOGGER = LogUtils.getLogger();
    private static final AtomicLong valkyrienskies$BLOCKED = new AtomicLong();

    @Inject(method = "tryAutoIngestChunk", at = @At("HEAD"), cancellable = true, require = 0, remap = false)
    private static void valkyrienskies$skipShipyardChunks(final LevelChunk chunk,
        final CallbackInfoReturnable<Boolean> cir) {
        if (chunk == null) {
            return;
        }
        final ChunkPos pos = chunk.getPos();
        if (!VS2ChunkAllocator.INSTANCE.isChunkInShipyardCompanion(pos.x, pos.z)) {
            return;
        }
        if (valkyrienskies$BLOCKED.incrementAndGet() == 1L) {
            valkyrienskies$LOGGER.info(
                "[voxy-lod] shipyard chunks were reaching Voxy's LOD store; blocking them (first was {}, {})",
                pos.x, pos.z);
        }
        cir.setReturnValue(false);
    }
}
