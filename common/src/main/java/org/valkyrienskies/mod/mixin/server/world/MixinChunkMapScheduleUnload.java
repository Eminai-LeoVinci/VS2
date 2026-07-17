package org.valkyrienskies.mod.mixin.server.world;

import it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.world.level.ChunkPos;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.valkyrienskies.mod.common.VS2ChunkAllocator;

/**
 * Fast-paths shipyard chunk unloads. Two interventions; they work together.
 *
 * <p><b>1. Fast scheduleUnload:</b> for shipyard chunks, skip the async save +
 * whenComplete cleanup chain entirely. Just remove the holder from pendingUnloads
 * and return. The per-chunk cost goes from "wait on save sync future, call
 * saveChunkIfNeeded, fire ChunkEvent.Unload, tear down block entity tickers,
 * clean up light/POI state" to "one hashmap remove."
 *
 * <p><b>2. Bulk evict at processUnloads HEAD:</b> when processUnloads runs, walk
 * the toDrop set once and pull out every shipyard entry: remove it from toDrop
 * (so MC's per-entry loop doesn't see it), remove it from updatingChunkMap
 * (freeing it for GC and shrinking the map MC iterates every tick). MC's natural
 * drain is rate-limited by the distance manager committing ticket changes in
 * batches, which spreads 62k cascaded holders across hundreds of ticks. Bulk
 * eviction lets the chunk map shrink in a single pass so ServerChunkCache.tickChunks
 * stops iterating stale holders.
 *
 * <p><b>Risks:</b> we never run the async unload-complete callback, which normally
 * removes the holder from pendingUnloads and runs per-chunk cleanup. For shipyard
 * chunks that's acceptable in practice: they hold almost no state (no players, no
 * real block entities in our use case, no POIs on a voxel island), and the holder
 * object itself is released so the JVM GCs its transitive references.
 */
@Mixin(ChunkMap.class)
public abstract class MixinChunkMapScheduleUnload {

    @Shadow
    @Final
    private Long2ObjectLinkedOpenHashMap<ChunkHolder> updatingChunkMap;

    @Shadow
    @Final
    private Long2ObjectLinkedOpenHashMap<ChunkHolder> pendingUnloads;

    @Shadow
    @Final
    private LongSet toDrop;

    @Inject(method = "scheduleUnload", at = @At("HEAD"), cancellable = true)
    private void vs$fastScheduleUnloadForShipyard(final long chunkPos, final ChunkHolder holder,
                                                  final CallbackInfo ci) {
        final int cx = ChunkPos.getX(chunkPos);
        final int cz = ChunkPos.getZ(chunkPos);
        if (!VS2ChunkAllocator.INSTANCE.isChunkInShipyardCompanion(cx, cz)) return;

        // Shipyard chunks on their way out: skip the whole async save + cleanup
        // chain. The only bookkeeping we have to do before returning is the
        // pendingUnloads entry that processUnloads just added — otherwise it leaks.
        pendingUnloads.remove(chunkPos);
        ci.cancel();
    }

    /**
     * Bulk-evict shipyard entries before MC's per-entry processUnloads loop runs.
     * MC's loop iterates the toDrop set at a rate that's bounded by how quickly
     * the DistanceManager commits ticket changes — with 62k cascaded shipyard
     * holders queued up, that stretches into hundreds of ticks while
     * `ServerChunkCache.tickChunks` keeps iterating them every tick. Pulling them
     * out of both toDrop and updatingChunkMap up front cuts the per-tick iteration
     * cost to the non-shipyard portion immediately.
     */
    @Inject(method = "processUnloads", at = @At("HEAD"))
    private void vs$bulkEvictShipyardAtHead(final java.util.function.BooleanSupplier shouldContinue,
                                            final CallbackInfo ci) {
        if (toDrop.isEmpty()) return;

        // Walk toDrop once, pulling out every shipyard entry. iterator.remove()
        // drops from toDrop; updatingChunkMap.remove drops the holder itself.
        final LongIterator it = toDrop.iterator();
        while (it.hasNext()) {
            final long chunkPos = it.nextLong();
            final int cx = ChunkPos.getX(chunkPos);
            final int cz = ChunkPos.getZ(chunkPos);
            if (!VS2ChunkAllocator.INSTANCE.isChunkInShipyardCompanion(cx, cz)) continue;
            updatingChunkMap.remove(chunkPos);
            pendingUnloads.remove(chunkPos);
            it.remove();
        }
    }
}
