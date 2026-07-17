package org.valkyrienskies.mod.common.world

import net.minecraft.server.level.TicketType
import net.minecraft.world.level.ChunkPos
import java.util.Comparator

/**
 * Custom ticket type for transient shipyard chunk access that only needs FULL status.
 *
 * Used with radius 0, this loads only the requested chunk with zero neighbor chunks:
 * - Vanilla FORCED ticket: level 31 (entity ticking) = 2-chunk radius = ~25 chunks per ship chunk
 * - Previous VS2 ticket: radius 1 (level 32, block ticking) = 1-chunk radius = ~9 chunks per ship chunk
 * - SHIP_CHUNK ticket: radius 0 (level 33, FULL) = 0-chunk radius = 1 chunk per ship chunk
 *
 * This is appropriate for preload/copy/update flows where VS only needs the chunk data itself.
 * It is not appropriate for normal live ship chunk management because ships still need vanilla
 * random ticking, block ticking, and entity ticking.
 */
object VSTicketType {
    @JvmField
    val SHIP_CHUNK: TicketType<ChunkPos> = TicketType.create(
        "vs_ship_chunk", Comparator.comparingLong(ChunkPos::toLong)
    )

    /**
     * Ticket for a ship's own SHIPYARD chunks (its blocks), placed by [ShipActivationManager] on EVERY
     * active chunk of an active ship — not just the subset vs-core's watch system tickets.
     *
     * vs-core only physics-steps a ship while [org.valkyrienskies.core.impl.api.ServerShipInternal.areVoxelsFullyLoaded]
     * is true, i.e. NONE of the ship's active shipyard chunks are unloaded. A large ship spans many
     * shipyard chunks; the watch system only loads the ones near a watcher, so a big craft's bow/stern
     * chunks unload and the WHOLE ship freezes (the size-dependent cruise stall). This radius-0
     * (level 33 FULL) ticket pins them all loaded.
     *
     * Deliberately a SEPARATE type from [SHIP_CHUNK] so it doesn't collide with ChunkManagement's
     * watch-driven SHIP_CHUNK add/remove (a shared type dedupes to one ticket, so an unwatch-remove
     * would drop the chunk we're trying to keep). The manager releases these as the ship deactivates.
     */
    @JvmField
    val SHIP_ACTIVE_VOXEL: TicketType<ChunkPos> = TicketType.create(
        "vs_ship_active_voxel", Comparator.comparingLong(ChunkPos::toLong)
    )
}
