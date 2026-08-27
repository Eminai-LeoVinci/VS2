package org.valkyrienskies.mod.compat.voxy;

import com.mojang.logging.LogUtils;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.WeakHashMap;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;
import org.slf4j.Logger;
import org.valkyrienskies.mod.common.VS2ChunkAllocator;
import org.valkyrienskies.mod.common.VSGameUtilsKt;

/**
 * Tell Voxy to rebuild its LOD for the world chunks a ship just left or landed in.
 *
 * <p>WHY: Voxy voxelises a chunk when it is ingested and never revisits it. Assembly and disassembly
 * both rewrite world blocks inside the server's SIMULATION distance but outside the client's RENDER
 * distance, so the client never reloads those chunks and the stored LOD keeps showing what used to
 * be there. Assembling leaves a ghost hull standing in the LOD beside the real ship; disassembling
 * -- an outrun ship, or a wreck burying itself -- writes a hull the LOD never learns about, so it
 * simply is not there when you look from a distance. A wreck you did not watch land is unfindable.
 *
 * <p>The fix is to send the signal Voxy is missing, from the SERVER side, where the chunks are
 * loaded regardless of where any player is. {@code VoxelIngestService.tryAutoIngestChunk} resolves
 * its target LOD store from {@code chunk.getLevel()}, and a client and server {@code Level} produce
 * an equal {@code WorldIdentifier} (same dimension key, same biome seed) -- so a chunk handed over
 * from the server thread lands in the very store the client renders from. Voxy's own Chunky compat
 * works exactly this way, which is why pregenerating terrain no player has visited still fills in
 * its LODs.
 *
 * <p>NO COMPILE DEPENDENCY ON VOXY: the entry point is resolved by name once and cached. If Voxy is
 * absent -- every dedicated server, and any client that does not run it -- {@link #AVAILABLE} latches
 * false and {@link #mark} costs one static read. Both outcomes are logged, because "Voxy is not
 * installed" and "Voxy is installed and we failed to reach it" must not look alike in a log.
 *
 * <p>SERVER THREAD ONLY. Both call sites (block relocation and the assembly block move) run there,
 * and so does the tick-end flush, so the pending sets need no synchronization.
 */
public final class VoxyLodRefresh {

    private static final Logger LOGGER = LogUtils.getLogger();

    /**
     * Ticks to wait before the first refresh.
     *
     * <p>Waiting at all is worth it: Voxy's ingest worker reads the live chunk section off-thread, so
     * handing it sections that are still being rewritten is worse than losing a tick.
     *
     * <p><b>The delay is not what makes this work, though, and assuming it was cost a round.</b> The first
     * cut skipped any chunk that had gone by flush time, on the theory that a short delay would usually beat
     * the unload. In game that failed about a third of the time, and the instrumentation showed it failing
     * TOTALLY rather than marginally -- {@code accepted=0 unloaded=17} -- because a disassembly far from any
     * player writes into chunks that nothing is holding open. {@code relocateBlock} loads each one to write
     * to it, and the chunk manager drops it again almost immediately; there is no ticket, and no delay short
     * enough to reliably win that race.
     *
     * <p>So the early pass now RELOADS a chunk that has gone. That is cheap and correct: it was written and
     * saved moments ago, so it comes back off disk with the ship's blocks and its stored light already in
     * it, which is exactly the state that needs voxelising. It is also bounded -- a few dozen chunks, once
     * per disassembly.
     *
     * <p>Assembly never hit this, and that is consistent rather than lucky: a ship assembles where a player
     * triggered it, so its chunks are inside somebody's simulation distance and stay put.
     */
    private static final int EARLY_DELAY_TICKS = 5;

    /**
     * Ticks to wait before the second refresh.
     *
     * <p>The early pass gets the geometry right but may bake half-propagated light -- assembly opens
     * a ship-sized hole for the sky to pour into, and disassembly drops a hull into open ground. This
     * pass runs once the light engine has caught up, and skips any chunk that has since unloaded.
     * Re-ingesting is cheap and idempotent: Voxy only marks a section dirty when the data actually
     * differs from what it stored.
     */
    private static final int SETTLE_DELAY_TICKS = 40;

    private static final Method INGEST_CHUNK;
    private static final boolean AVAILABLE;

    static {
        Method ingest = null;
        try {
            final Class<?> service = Class.forName("me.cortex.voxy.common.world.service.VoxelIngestService");
            ingest = service.getMethod("tryAutoIngestChunk", LevelChunk.class);
            LOGGER.info("[voxy-lod] Voxy found; ship assembly and disassembly will refresh its LOD chunks");
        } catch (final ClassNotFoundException absent) {
            LOGGER.info("[voxy-lod] Voxy not installed; LOD refresh disabled");
        } catch (final Throwable broken) {
            LOGGER.warn("[voxy-lod] Voxy is installed but its ingest entry point did not resolve; "
                + "LOD refresh disabled. Ships will leave stale LODs behind.", broken);
        }
        INGEST_CHUNK = ingest;
        AVAILABLE = ingest != null;
    }

    /** Chunks waiting to be handed to Voxy, per level. One batch per level at a time. */
    private static final Map<ServerLevel, LongOpenHashSet> PENDING = new WeakHashMap<>();

    private VoxyLodRefresh() {
    }

    /**
     * Note that a world chunk's blocks changed and its LOD is now stale.
     *
     * <p>Cheap enough to call per relocated block -- a big hull is tens of thousands of them -- so
     * this does nothing but add to a set. The shipyard filter and the chunk lookup both run once per
     * distinct chunk at flush time instead of once per block.
     */
    public static void mark(final ServerLevel level, final int chunkX, final int chunkZ) {
        if (!AVAILABLE || level == null) {
            return;
        }
        LongOpenHashSet pending = PENDING.get(level);
        if (pending == null) {
            pending = new LongOpenHashSet();
            PENDING.put(level, pending);
            arm(level, pending);
        }
        pending.add(ChunkPos.asLong(chunkX, chunkZ));
    }

    /** Convenience for callers that already hold chunk positions. */
    public static void mark(final ServerLevel level, final ChunkPos pos) {
        if (pos != null) {
            mark(level, pos.x, pos.z);
        }
    }

    /**
     * Schedule the two refresh passes for this batch.
     *
     * <p>Armed once, on the empty-to-occupied transition, and never per block: assembling a
     * twenty-thousand block hull must not register twenty thousand tick handlers. Uses the same
     * {@code executeIf} the assembler already leans on to resume chunk updates -- it re-tests each
     * tick end and unregisters itself.
     */
    private static void arm(final ServerLevel level, final LongOpenHashSet batch) {
        final MinecraftServer server = level.getServer();
        if (server == null) {
            PENDING.remove(level);
            return;
        }
        final int armedAt = server.getTickCount();

        VSGameUtilsKt.executeIf(
            server,
            () -> server.getTickCount() - armedAt >= EARLY_DELAY_TICKS,
            () -> {
                // Drop the batch first so anything that happens after this tick starts a fresh one.
                PENDING.remove(level);
                flush(level, batch, "early", true);
            }
        );
        VSGameUtilsKt.executeIf(
            server,
            () -> server.getTickCount() - armedAt >= SETTLE_DELAY_TICKS,
            // Best-effort only, and NOT allowed to reload: the early pass has already got the geometry in,
            // so dragging the same chunks off disk a second time would buy nothing but a hitch.
            () -> flush(level, batch, "settle", false)
        );
    }

    /**
     * Hand this batch's chunks to Voxy.
     *
     * [mayLoad] force-loads a chunk that has since gone, rather than skipping it. Only the FIRST pass does
     * that, and it is the whole fix for the failure this feature shipped with -- see the note on
     * [#EARLY_DELAY_TICKS].
     */
    private static void flush(final ServerLevel level, final LongOpenHashSet batch, final String pass,
        final boolean mayLoad) {
        if (batch.isEmpty()) {
            return;
        }
        reportStore(level);
        final ServerChunkCache chunks = level.getChunkSource();
        int sent = 0;
        int gone = 0;
        int refused = 0;
        int shipyard = 0;
        int recovered = 0;

        final LongIterator keys = batch.iterator();
        while (keys.hasNext()) {
            final long key = keys.nextLong();
            final int chunkX = ChunkPos.getX(key);
            final int chunkZ = ChunkPos.getZ(key);

            // The shipyard is not terrain and must never reach the LOD store. Filtered here rather
            // than in mark() so the per-block path stays a bare set insertion.
            if (VS2ChunkAllocator.INSTANCE.isChunkInShipyardCompanion(chunkX, chunkZ)) {
                shipyard++;
                continue;
            }

            LevelChunk chunk = chunks.getChunkNow(chunkX, chunkZ);
            if (chunk == null && mayLoad) {
                // Bring it back. This is the case the whole feature exists for: a ship that came apart
                // where nobody was. See [#EARLY_DELAY_TICKS] for why skipping it was wrong.
                chunk = level.getChunk(chunkX, chunkZ);
                if (chunk != null) {
                    recovered++;
                }
            }
            if (chunk == null) {
                gone++;
                continue;
            }
            if (ingest(chunk)) {
                sent++;
            } else {
                refused++;
            }
        }

        // INFO, not debug, and deliberately so while this is being proven out: the first cut logged these
        // counts at debug, the feature did not work, and the log had nothing in it to say WHICH of the four
        // possible failures had happened. Each count sends the investigation somewhere different:
        //   marked=0        -> the hooks never fired; the mark call sites are wrong
        //   gone=most       -> the chunks unloaded before the flush; EARLY_DELAY_TICKS is too long
        //   refused=most    -> Voxy took the call and declined it; ingest disabled, or the server level
        //                      resolves to a DIFFERENT WorldEngine than the one the client renders from
        //   sent=most       -> Voxy accepted the chunks and the problem is downstream of ingest entirely
        LOGGER.info(
            "[voxy-lod] {} pass: marked={} accepted={} refused={} unloaded={} reloaded={} shipyard-skipped={}",
            pass, batch.size(), sent, refused, gone, recovered, shipyard
        );
    }

    /**
     * Say, once, which LOD store the SERVER side resolves this level to.
     *
     * The whole feature rests on an assumption that has never actually been checked: that a chunk handed
     * over from the server thread lands in the same {@code WorldEngine} the client renders from. Both sides
     * build a {@code WorldIdentifier} from (dimension key, biome seed, dimension type), and they are
     * SUPPOSED to agree -- but if they do not, every ingest quietly populates a second, invisible store and
     * the feature looks broken while reporting success.
     *
     * {@code getWorldId()} is the hash Voxy names the storage directory with, so the answer is checkable
     * from outside the game: the id logged here must match a folder under
     * {@code saves/<world>/voxy/}. A different id, or a second folder appearing, is the bug.
     */
    private static void reportStore(final ServerLevel level) {
        if (storeReported) {
            return;
        }
        storeReported = true;
        try {
            final Class<?> identifier = Class.forName("me.cortex.voxy.commonImpl.WorldIdentifier");
            // Level.class, NOT Class.forName("net.minecraft.world.level.Level"): our jar is remapped to
            // intermediary, so the literal resolves to the right runtime class while the NAMED string does
            // not exist at all. The first cut used the string and logged "could not read the server-side
            // WorldIdentifier" every run -- a diagnostic that only ever diagnosed itself.
            final Object id = identifier.getMethod("of", Level.class).invoke(null, level);
            if (id == null) {
                LOGGER.warn("[voxy-lod] this level has no Voxy WorldIdentifier at all");
                return;
            }
            LOGGER.info(
                "[voxy-lod] server-side store for {}: {} (dir '{}') -- must match the client's folder "
                    + "under saves/<world>/voxy/",
                level.dimension().location(), id,
                identifier.getMethod("getWorldId").invoke(id)
            );
        } catch (final Throwable t) {
            LOGGER.warn("[voxy-lod] could not read the server-side WorldIdentifier", t);
        }
    }

    private static boolean storeReported = false;

    /** One complaint per session. A batch caught by a world unload or a shutdown is fifty of them. */
    private static boolean warned = false;

    private static boolean ingest(final LevelChunk chunk) {
        try {
            return Boolean.TRUE.equals(INGEST_CHUNK.invoke(null, chunk));
        } catch (final Throwable t) {
            if (!warned) {
                warned = true;
                LOGGER.warn("[voxy-lod] Voxy refused a chunk; leaving its LOD stale. "
                    + "Expected during shutdown or a dimension unload, a real problem otherwise.", t);
            }
            return false;
        }
    }
}
