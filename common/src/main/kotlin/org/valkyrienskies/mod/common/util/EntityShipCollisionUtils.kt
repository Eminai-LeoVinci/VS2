package org.valkyrienskies.mod.common.util

import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.core.SectionPos
import net.minecraft.server.level.ServerLevel
import net.minecraft.util.Mth
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.player.Player
import net.minecraft.world.level.Level
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
import net.minecraft.world.phys.shapes.VoxelShape
import org.joml.Vector3d
import org.joml.Vector3dc
import org.joml.primitives.AABBd
import org.joml.primitives.AABBdc
import org.joml.primitives.AABBi
import org.valkyrienskies.core.api.ships.Ship
import org.valkyrienskies.core.internal.collision.VsiConvexPolygonc
import org.valkyrienskies.core.util.extend
import org.valkyrienskies.core.util.toAABBd
import org.valkyrienskies.core.api.ships.properties.ShipId
import org.valkyrienskies.core.api.world.properties.DimensionId
import org.valkyrienskies.mod.common.allShips
import org.valkyrienskies.mod.common.dimensionId
import org.valkyrienskies.mod.common.getLoadedShipManagingPos
import org.valkyrienskies.mod.common.getShipsIntersecting
import org.valkyrienskies.mod.common.shipObjectWorld
import org.valkyrienskies.mod.common.vsCore
import org.valkyrienskies.mod.mixinducks.feature.tickets.PlayerKnownShipsDuck
import org.valkyrienskies.mod.util.BugFixUtil
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue

object EntityShipCollisionUtils {

    /**
     * Tracks recently-spawned ships by their ID and the game-tick they were created. Ships within the
     * grace period are excluded from unloaded-ship collision checks to prevent freezing the player when
     * a new ship is assembled nearby but its chunks haven't loaded yet.
     *
     * Entries store an expiry DEADLINE in gameTime ticks and expire lazily in [isInSpawnGracePeriod]
     * against the level's current gameTime (the same monotonic per-tick clock the callers stamp). The old
     * version compared a tick stamp that was never cleaned up -- [isInSpawnGracePeriod] only did
     * containsKey and [cleanupExpiredGracePeriods] had no callers -- so every assembled ship stayed in
     * "grace" forever and the unloaded-ship movement guard was permanently disabled for it. (Tick-based,
     * NOT nanoTime: matches DST's existing gameTime model.) Callers may override the window per-use:
     * assembly wants a SHORT hold (the gravity clamp on mobs over the new ship should release quickly),
     * the reconnect teleport wants a LONG one (ship chunks can take several seconds to stream in).
     */
    private val recentlySpawnedShips = ConcurrentHashMap<ShipId, Long>()
    private const val SPAWN_GRACE_PERIOD_TICKS = 100L // ~5 seconds

    @JvmStatic
    @JvmOverloads
    fun markShipAsRecentlySpawned(shipId: ShipId, currentTick: Long, durationTicks: Long = SPAWN_GRACE_PERIOD_TICKS) {
        recentlySpawnedShips[shipId] = currentTick + durationTicks
    }

    @JvmStatic
    fun isInSpawnGracePeriod(shipId: ShipId, currentTick: Long): Boolean {
        val deadline = recentlySpawnedShips[shipId] ?: return false
        if (currentTick > deadline) {
            recentlySpawnedShips.remove(shipId)
            return false
        }
        return true
    }

    /**
     * Shared per-entity-per-tick "which ships are near this entity's feet" CANDIDATE query. Walk-anim,
     * the drag-standing stamp, and the standing-on probes (getBlockPosBelowThatAffectsMyMovement /
     * getOnPos / sprint particles) previously each issued their own spatial query -- up to 4 per living
     * entity per tick; this runs ONE and memoizes it on the entity's dragging info for the tick.
     *
     * The probe box is a padded UNION of every caller's box (XZ +-1.0; Y from y-1.5 to y+0.5, containing
     * the walk-anim +-0.4/-1.0..+0.1 box and both 0.5-radius standing probes incl. their one-block-below
     * fence checks, with slack for within-tick movement between move() and the later probes). Callers
     * MUST still run their own per-ship validation (block-under-feet check, or an exact ship-AABB
     * intersect) -- this list is a superset of every individual query's result, never a substitute.
     *
     * Returns an empty list when the world has no ships (the common case) without querying.
     */
    @JvmStatic
    fun shipsNearEntityFeet(entity: Entity): List<Ship> {
        val level = entity.level()
        val info = (entity as? IEntityDraggingInformationProvider)?.draggingInformation
        if (info != null && info.footShipsQueryTick == level.gameTime && info.footShipsQueryLevel === level) {
            return info.footShipsQueryResult
        }
        val result: List<Ship>
        if (level.allShips.none()) {
            result = emptyList()
        } else {
            val x = entity.x
            val y = entity.y
            val z = entity.z
            val union = AABBd(x - 1.0, y - 1.5, z - 1.0, x + 1.0, y + 0.5, z + 1.0)
            val iter = level.getShipsIntersecting(union).iterator()
            if (!iter.hasNext()) {
                result = emptyList()
            } else {
                val list = ArrayList<Ship>(2)
                while (iter.hasNext()) {
                    list.add(iter.next())
                }
                result = list
            }
        }
        if (info != null) {
            info.footShipsQueryTick = level.gameTime
            info.footShipsQueryLevel = level
            info.footShipsQueryResult = result
        }
        return result
    }

    private const val PARTICLE_COLLISION_BOX_EXPANSION = 0.00390625 //1.0 / 256.0

    private val collider = vsCore.entityPolygonCollider

    private fun getShipyardChunkAABBAround(ship: Ship): AABBi {
        val box = AABBi()
        // Since we don't know how big the ship is vertically we'll just have to trust the shipAABB and add some margin of error.
        val minY = (ship.shipAABB?.minY() ?: Mth.floor(ship.transform.position.y())) - 16
        val maxY = (ship.shipAABB?.maxY() ?: Mth.ceil(ship.transform.position.y())) + 16
        ship.activeChunksSet.forEach { x, z ->
            val minX = SectionPos.sectionToBlockCoord(x)
            val minZ = SectionPos.sectionToBlockCoord(z)
            val maxX = SectionPos.sectionToBlockCoord(x, 15)
            val maxZ = SectionPos.sectionToBlockCoord(z, 15)
            box.union(minX, minY, minZ).union(maxX, maxY, maxZ)
        }
        return box
    }

    /**
     * Per-ship, per-tick cache of the rough world-space AABB derived from the ship's active chunk set.
     * Rebuilding the chunk-set union for EVERY entity move (this sits at the HEAD of Entity.move()) was
     * the single hottest per-entity cost on the server tick; the underlying data only changes once per
     * tick. Client and server keep separate caches (singleplayer runs both in one JVM, transforms differ).
     */
    private class RoughAABBEntry {
        var gameTime = Long.MIN_VALUE
        val aabb = AABBd()
    }

    private val roughAABBCacheServer = ConcurrentHashMap<ShipId, RoughAABBEntry>()
    private val roughAABBCacheClient = ConcurrentHashMap<ShipId, RoughAABBEntry>()
    private var lastCacheSweepServer = Long.MIN_VALUE
    private var lastCacheSweepClient = Long.MIN_VALUE
    private const val CACHE_SWEEP_INTERVAL_TICKS = 1200L // drop entries for deleted ships ~once a minute

    /**
     * Per-dimension, per-tick snapshot of the ships the unloaded-ship guard has to test, paired with
     * their rough world AABBs.
     *
     * [isCollidingWithUnloadedShips] runs at the HEAD of every [Entity.move] call, and it used to walk
     * the WHOLE ship world there -- every ship in every dimension -- rejecting the wrong-dimension ones
     * with a string compare and looking each survivor's AABB up in a concurrent map. That work is
     * identical for every entity in a dimension within a tick, so it is now done once and the per-entity
     * path scans two plain arrays.
     *
     * Invalidated when the tick advances OR the ship count changes, so a ship assembled or removed
     * mid-tick is never tested against a stale list. A ship that changes DIMENSION mid-tick can be
     * one tick stale, exactly as its rough AABB already could be.
     *
     * The AABBs are the live per-tick cache entries from [roughWorldAABB], which are only rewritten
     * when the tick advances -- the same event that invalidates this snapshot.
     */
    private class DimShipSnapshot(
        val gameTime: Long,
        val shipCount: Int,
        val ships: Array<Ship>,
        val aabbs: Array<AABBdc>
    )

    private val emptyShips = emptyArray<Ship>()
    private val emptyAabbs = emptyArray<AABBdc>()
    private val dimSnapshotsServer = ConcurrentHashMap<DimensionId, DimShipSnapshot>()
    private val dimSnapshotsClient = ConcurrentHashMap<DimensionId, DimShipSnapshot>()

    private fun dimShipSnapshot(level: Level): DimShipSnapshot {
        val cache = if (level.isClientSide) dimSnapshotsClient else dimSnapshotsServer
        val dimensionId = level.dimensionId
        val gameTime = level.gameTime
        val allShips = level.allShips
        val shipCount = allShips.size
        val cached = cache[dimensionId]
        if (cached != null && cached.gameTime == gameTime && cached.shipCount == shipCount) {
            return cached
        }
        val snapshot: DimShipSnapshot
        if (shipCount == 0) {
            snapshot = DimShipSnapshot(gameTime, 0, emptyShips, emptyAabbs)
        } else {
            val ships = ArrayList<Ship>(shipCount)
            val aabbs = ArrayList<AABBdc>(shipCount)
            for (ship in allShips) {
                if (ship.chunkClaimDimension != dimensionId) continue
                ships.add(ship)
                aabbs.add(roughWorldAABB(ship, level, gameTime))
            }
            snapshot = DimShipSnapshot(gameTime, shipCount, ships.toTypedArray(), aabbs.toTypedArray())
        }
        cache[dimensionId] = snapshot
        return snapshot
    }

    private fun roughWorldAABB(ship: Ship, level: Level, gameTime: Long): AABBdc {
        val cache: ConcurrentHashMap<ShipId, RoughAABBEntry>
        if (level.isClientSide) {
            cache = roughAABBCacheClient
            if (gameTime - lastCacheSweepClient >= CACHE_SWEEP_INTERVAL_TICKS) {
                cache.clear()
                lastCacheSweepClient = gameTime
            }
        } else {
            cache = roughAABBCacheServer
            if (gameTime - lastCacheSweepServer >= CACHE_SWEEP_INTERVAL_TICKS) {
                cache.clear()
                lastCacheSweepServer = gameTime
            }
        }
        val entry = cache.computeIfAbsent(ship.id) { RoughAABBEntry() }
        if (entry.gameTime != gameTime) {
            // shipAABB and worldAABB are sometimes too small when the ship was just loaded for the
            // first time, so use activeChunksSet for a rougher box that always contains the ship.
            getShipyardChunkAABBAround(ship).toAABBd(entry.aabb).transform(ship.shipToWorld)
            entry.gameTime = gameTime
        }
        return entry.aabb
    }

    @JvmStatic
    fun isCollidingWithUnloadedShips(entity: Entity): Boolean {
        val level = entity.level()

        if (level is ServerLevel || (level.isClientSide && level is ClientLevel)) {
            if (level.isClientSide && level is ClientLevel && !level.shipObjectWorld.isSyncedWithServer) {
                return true
            }

            // Plain array scan over the per-tick dimension snapshot instead of a Stream pipeline (or a
            // walk of every ship in every dimension): this runs at the HEAD of every Entity.move()
            // call, so per-entity allocation, lambda overhead and repeated dimension filtering matter.
            val snapshot = dimShipSnapshot(level)
            val ships = snapshot.ships
            if (ships.isEmpty()) {
                return false
            }
            val aabbs = snapshot.aabbs
            val gameTime = level.gameTime
            val aabb = entity.boundingBox.toJOML()
            for (i in ships.indices) {
                val ship = ships[i]
                if (!aabbs[i].intersectsAABB(aabb)) continue
                // Skip collision check for recently-spawned ships whose chunks are still
                // loading. Without this, spawning a new ship near a player would freeze
                // them because isCollidingWithUnloadedShips returns true (the new ship's
                // chunks haven't loaded yet), which cancels all entity movement.
                // This must be checked BEFORE vs_isKnownShip, because the player won't
                // know about a brand-new ship yet either.
                if (isInSpawnGracePeriod(ship.id, gameTime)) continue // pretend it's loaded → don't block movement
                if (entity is PlayerKnownShipsDuck && !entity.vs_isKnownShip(ship.id)) {
                    return true
                }
                val aabbInShip = AABBd(aabb).transform(ship.worldToShip)
                if (!areAllChunksLoaded(ship, aabbInShip, level)) {
                    return true
                }
            }
            return false
        }

        return false
    }

    private fun areAllChunksLoaded(ship: Ship, aABB: AABBdc, level: Level): Boolean {
        val minX = (Mth.floor(aABB.minX() - 1.0E-7) - 1) shr 4
        val maxX = (Mth.floor(aABB.maxX() + 1.0E-7) + 1) shr 4
        val minZ = (Mth.floor(aABB.minZ() - 1.0E-7) - 1) shr 4
        val maxZ = (Mth.floor(aABB.maxZ() + 1.0E-7) + 1) shr 4

        for (chunkX in minX..maxX) {
            for (chunkZ in minZ..maxZ) {
                if (ship.activeChunksSet.contains(chunkX, chunkZ) &&
                    level.getChunkForCollisions(chunkX, chunkZ) == null
                ) {
                    return false
                }
            }
        }

        return true
    }

    // === Gravity-only hold through ship transitions ===
    // A short, bounded "hold the entity's downward movement" window used while a ship's voxel collision isn't
    // solid yet (login / assembly / disassembly). Login + assembly reuse the ship-id [recentlySpawnedShips]
    // grace path (the ship still exists); DISASSEMBLY has no ship to key on, so it arms a world-space box here.
    private class WorldFreezeEntry(val dimensionId: String, val aabb: AABBd, val deadlineGameTime: Long)
    private val worldFreezes = ConcurrentLinkedQueue<WorldFreezeEntry>()

    /** The ship's world-space bounding box (from its shipyard AABB transformed to world), for [markWorldFreeze]. */
    @JvmStatic
    fun worldAABBForShip(ship: Ship): AABBd {
        val sb = ship.shipAABB
        return if (sb != null) {
            AABBd(
                sb.minX().toDouble(), sb.minY().toDouble(), sb.minZ().toDouble(),
                (sb.maxX() + 1).toDouble(), (sb.maxY() + 1).toDouble(), (sb.maxZ() + 1).toDouble()
            ).transform(ship.shipToWorld)
        } else {
            val p = ship.transform.position
            AABBd(p.x() - 32.0, p.y() - 32.0, p.z() - 32.0, p.x() + 32.0, p.y() + 32.0, p.z() + 32.0)
        }
    }

    /**
     * Arm a bounded gravity-hold over a WORLD-space box for [durationTicks] (used on ship DISASSEMBLY, where the
     * ship no longer exists to key on). Tick-based against level.gameTime, matching the rest of this class (NOT
     * System.nanoTime). The disassembly caller lives in the Eureka repo.
     */
    @JvmStatic
    fun markWorldFreeze(level: Level, aabb: AABBd, durationTicks: Long) {
        worldFreezes.add(WorldFreezeEntry(level.dimensionId, aabb, level.gameTime + durationTicks))
    }

    @JvmStatic
    fun isInWorldFreeze(entity: Entity): Boolean {
        if (worldFreezes.isEmpty()) return false
        val now = entity.level().gameTime
        val dim = entity.level().dimensionId
        val px = entity.x
        val py = entity.y
        val pz = entity.z
        var held = false
        val it = worldFreezes.iterator()
        while (it.hasNext()) {
            val e = it.next()
            if (now - e.deadlineGameTime > 0) {
                it.remove()
                continue
            }
            if (!held && e.dimensionId == dim &&
                px >= e.aabb.minX() && px <= e.aabb.maxX() &&
                py >= e.aabb.minY() && py <= e.aabb.maxY() &&
                pz >= e.aabb.minZ() && pz <= e.aabb.maxZ()
            ) {
                held = true
            }
        }
        return held
    }

    /**
     * Whether [entity]'s GRAVITY (only) should be held this tick: it is in a ship-transition zone where the
     * deck collision isn't solid yet -- a DISASSEMBLY world-freeze, OR over a freshly-loaded/assembled ship
     * still in its spawn-grace (login / assembly). Used by MixinEntity.vs$holdGravityDuringShipTransition to
     * clamp ONLY the downward movement, so the entity keeps full X/Z + camera control but cannot fall through.
     * Deadline-bounded by both [worldFreezes] and [recentlySpawnedShips], so it never holds forever.
     *
     * MOBS / ENTITIES ONLY -- players are excluded (the downward-clamp made elytra/creative flight feel floaty,
     * and they don't fall through on transitions anyway), keeping player flight + gravity completely vanilla.
     */
    @JvmStatic
    fun shouldHoldGravity(entity: Entity): Boolean {
        if (entity is Player) return false
        if (isInWorldFreeze(entity)) return true
        val level = entity.level()
        if (!(level is ServerLevel || (level.isClientSide && level is ClientLevel))) return false
        val snapshot = dimShipSnapshot(level)
        val ships = snapshot.ships
        if (ships.isEmpty()) return false
        val aabbs = snapshot.aabbs
        val gameTime = level.gameTime
        val aabb = entity.boundingBox.toJOML()
        for (i in ships.indices) {
            if (!aabbs[i].intersectsAABB(aabb)) continue
            if (isInSpawnGracePeriod(ships[i].id, gameTime)) return true
        }
        return false
    }

    /**
     * @return [movement] modified such that the entity collides with ships.
     */
    fun adjustEntityMovementForShipCollisions(
        entity: Entity?,
        movement: Vec3,
        entityBoundingBox: AABB,
        world: Level
    ): Vec3 {
        // Inflate the bounding box more for players than other entities, to give players a better collision result.
        // Note that this increases the cost of doing collision, so we only do it for the players
        val inflation = if (entity is Player) 0.5 else 0.1
        val stepHeight: Double = entity?.maxUpStep()?.toDouble() ?: 0.0
        // Add [max(stepHeight - inflation, 0.0)] to search for polygons we might collide with while stepping

        // This part was slightly changed to inflate the bounding box in y-axis and adjust the center point. - Bunting_chj
        val collidingShipPolygons =
            getShipPolygonsCollidingWithEntity(
                entity, Vec3(movement.x(), movement.y() + stepHeight / 2, movement.z()),
                entityBoundingBox.inflate(inflation, inflation + stepHeight / 2, inflation), world
            )

        if (collidingShipPolygons.isEmpty()) {
            return movement
        }

        val collisionBoundingBox = if (entity == null) {
            entityBoundingBox.inflate(PARTICLE_COLLISION_BOX_EXPANSION)
        } else {
            entityBoundingBox
        }

        val (newMovement, shipCollidingWith) = collider.adjustEntityMovementForPolygonCollisions(
            movement.toJOML(), collisionBoundingBox.toJOML(), stepHeight, collidingShipPolygons
        )

        // 2.4.88 fix: per-axis magnitude clamp for gliding (fall-flying / elytra) entities.
        // The polygon collider is only allowed to DECREASE movement magnitude on each axis,
        // never INCREASE it. Without this, two cases inject boost velocity into the glider:
        //
        //   1. Polygon extraction: when the player tunnels into a ship block at high glide
        //      speed, the collider's next-tick response is a large normal-direction push to
        //      extract them. That push has magnitude far exceeding requested movement, and
        //      the 2.4.86 projection mixin converts the resulting deltaMovement into
        //      tangent-to-hull velocity — which elytra realignment then rotates back into
        //      the look direction next tick. Speed never bleeds off, and the condition is
        //      re-triggered every subsequent glide near the ship, so the boost persists
        //      until the world reloads.
        //   2. Ship-velocity carry-over: an ascending or moving ship's per-tick velocity
        //      gets baked into the collider's adjusted movement (e.g., to make passengers
        //      ride along). A gliding player isn't a passenger, but if the collider treats
        //      their overlapping bounding box as one, ship velocity injects into the
        //      player's movement and accumulates the same way as case 1.
        //
        // Per-axis rule: if |adjusted_axis| > |requested_axis|, clamp the axis to the
        // requested value (sign of requested preserved). Otherwise pass through. This
        // means normal collision responses (collider clipping movement on a wall) work as
        // before — only the over-injection cases are neutralized. It also fixes a subtle
        // bug in 2.4.87's Y-only clamp: that condition was `newMovement.y > movement.y`,
        // which is true both when a ship pushes a hovering glider upward AND when a ship
        // slows a falling glider. The latter shouldn't be clamped (the slow is the deck
        // catching the player, not an injection); the magnitude rule handles both cases
        // correctly.
        //
        // Tradeoff: gliding entities can no longer be PUSHED by ships, only STOPPED. A
        // player phased inside a ship will need to stop gliding before the polygon collider
        // can extract them. We also skip the lastShipStoodOn assignment so the player isn't
        // implicitly tracked as "on the ship" while gliding above it (this would otherwise
        // route ship-block speed factors and dragger state through them via
        // EntityDragger and getBlockPosBelowThatAffectsMyMovement).
        val isGliding = entity is IEntityDraggingInformationProvider && (entity as IEntityDraggingInformationProvider).`vs$isGliding`()
        val finalMovement: Vector3dc = if (isGliding) {
            Vector3d(
                if (kotlin.math.abs(newMovement.x()) > kotlin.math.abs(movement.x())) movement.x() else newMovement.x(),
                if (kotlin.math.abs(newMovement.y()) > kotlin.math.abs(movement.y())) movement.y() else newMovement.y(),
                if (kotlin.math.abs(newMovement.z()) > kotlin.math.abs(movement.z())) movement.z() else newMovement.z()
            )
        } else {
            newMovement
        }

        if (entity != null && !isGliding) {
            val standingOnShip = entity.level().getLoadedShipManagingPos(entity.onPos)
            if (shipCollidingWith != null && standingOnShip != null && standingOnShip.id == shipCollidingWith) {
                // Update the [IEntity.lastShipStoodOn]
                (entity as IEntityDraggingInformationProvider).draggingInformation.lastShipStoodOn = shipCollidingWith
                for (entityRiding in entity.indirectPassengers) {
                    (entityRiding as IEntityDraggingInformationProvider).draggingInformation.lastShipStoodOn = shipCollidingWith
                }
            }
        }
        return finalMovement.toMinecraft()
    }

    fun getShipPolygonsCollidingWithEntity(
        entity: Entity?,
        movement: Vec3,
        entityBoundingBox: AABB,
        world: Level
    ): List<VsiConvexPolygonc> {
        val entityBoxWithMovement = entityBoundingBox.expandTowards(movement)
        val collidingPolygons: MutableList<VsiConvexPolygonc> = ArrayList()
        val entityBoundingBoxExtended = entityBoundingBox.toJOML().extend(movement.toJOML())
        for (shipObject in world.shipObjectWorld.loadedShips.getIntersecting(entityBoundingBoxExtended, world.dimensionId)) {
            val shipTransform = shipObject.transform
            val entityPolyInShipCoordinates: VsiConvexPolygonc = collider.createPolygonFromAABB(
                entityBoxWithMovement.toJOML(),
                shipTransform.worldToShip
            )
            val entityBoundingBoxInShipCoordinates: AABBdc = entityPolyInShipCoordinates.getEnclosingAABB(AABBd())
            if (BugFixUtil.isCollisionBoxTooBig(entityBoundingBoxInShipCoordinates.toMinecraft())) {
                // Box too large, skip it
                continue
            }
            val shipBlockCollisionStream =
                world.getBlockCollisions(entity, entityBoundingBoxInShipCoordinates.toMinecraft())
            shipBlockCollisionStream.forEach { voxelShape: VoxelShape ->
                voxelShape.forAllBoxes { minX, minY, minZ, maxX, maxY, maxZ ->
                    val shipPolygon: VsiConvexPolygonc = vsCore.entityPolygonCollider.createPolygonFromAABB(
                        AABBd(minX, minY, minZ, maxX, maxY, maxZ),
                        shipTransform.shipToWorld,
                        shipObject.id
                    )
                    collidingPolygons.add(shipPolygon)
                }
            }
        }
        return collidingPolygons
    }
}
