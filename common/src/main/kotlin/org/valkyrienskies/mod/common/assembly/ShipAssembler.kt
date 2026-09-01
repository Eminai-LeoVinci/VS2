package org.valkyrienskies.mod.common.assembly

import net.minecraft.core.BlockPos
import net.minecraft.nbt.CompoundTag
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.Clearable
import net.minecraft.world.entity.decoration.BlockAttachedEntity
import net.minecraft.world.entity.decoration.HangingEntity
import net.minecraft.world.level.ChunkPos
import net.minecraft.world.level.Level
import net.minecraft.world.level.LevelReader
import net.minecraft.world.level.ServerLevelAccessor
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.state.properties.BlockStateProperties
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureProcessor
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureProcessorType
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate
import net.minecraft.world.phys.AABB
import org.joml.Quaterniond
import org.joml.RoundingMode
import org.joml.Vector3d
import org.joml.Vector3i
import org.valkyrienskies.core.api.ships.LoadedServerShip
import org.valkyrienskies.core.api.ships.ServerShip
import org.valkyrienskies.core.api.ships.properties.ShipId
import org.valkyrienskies.core.api.util.GameTickOnly
import org.valkyrienskies.core.impl.config.VSCoreConfig
import org.valkyrienskies.core.internal.ships.VsiServerShip
import org.valkyrienskies.mod.common.config.VSGameConfig
import org.valkyrienskies.mod.common.dimensionId
import org.valkyrienskies.mod.common.executeIf
import org.valkyrienskies.mod.common.forEach
import org.valkyrienskies.mod.common.getLoadedShipManagingPos
import org.valkyrienskies.mod.common.getShipManagingPos
import org.valkyrienskies.mod.common.inAssemblyBlacklist
import org.valkyrienskies.mod.common.isChunkLoadedForVS
import org.valkyrienskies.mod.common.networking.sendRestartChunkUpdates
import org.valkyrienskies.mod.common.networking.sendStopChunkUpdates
import org.valkyrienskies.mod.common.playerWrapper
import org.valkyrienskies.mod.common.shipObjectWorld
import org.valkyrienskies.mod.common.toDenseVoxelUpdate
import org.valkyrienskies.mod.common.util.EntityShipCollisionUtils
import org.valkyrienskies.mod.common.util.SplittingDisablerAttachment
import org.valkyrienskies.mod.common.util.toJOML
import org.valkyrienskies.mod.common.util.toJOMLD
import org.valkyrienskies.mod.common.vsCore
import org.valkyrienskies.mod.common.yRange
import org.valkyrienskies.mod.compat.voxy.VoxyLodRefresh
import org.valkyrienskies.mod.util.AIR
import org.valkyrienskies.mod.util.StructureTemplateFillFromVoxelSet
import org.valkyrienskies.mod.util.loadFromTag
import org.valkyrienskies.mod.util.logger

object ShipAssembler {

    val ASSEMBLY_LOGGER = logger("(Valkyrien Skies) Sandwich Factory").logger

    class SingleItemMap<K, V>(val mkey: K, val mvalue: V, val default: V, val defaultFn: ((K) -> V)? = null): Map<K, V> {
        override val size: Int = 1
        override val keys: Set<K> = setOf(mkey)

        override val values: Collection<V> = setOf(mvalue)
        override val entries: Set<Map.Entry<K, V>> = setOf(object : Map.Entry<K, V> {
            override val key = mkey
            override val value = mvalue
        })

        override fun isEmpty(): Boolean = false
        override fun containsKey(key: K): Boolean = true
        override fun containsValue(value: V): Boolean = true
        override fun get(key: K): V? = if (key == this.mkey) mvalue else defaultFn?.invoke(key) ?: default
    }

    @JvmStatic
    fun findMinAndMax(blocks: Iterable<BlockPos>): Pair<BlockPos, BlockPos> {
        val minCorner = BlockPos.MutableBlockPos(Int.MAX_VALUE, Int.MAX_VALUE, Int.MAX_VALUE)
        val maxCorner = BlockPos.MutableBlockPos(Int.MIN_VALUE, Int.MIN_VALUE, Int.MIN_VALUE)

        for (pos in blocks) {
            minCorner.x = Math.min(minCorner.x, pos.x)
            minCorner.y = Math.min(minCorner.y, pos.y)
            minCorner.z = Math.min(minCorner.z, pos.z)

            maxCorner.x = Math.max(maxCorner.x, pos.x)
            maxCorner.y = Math.max(maxCorner.y, pos.y)
            maxCorner.z = Math.max(maxCorner.z, pos.z)
        }

        return minCorner to maxCorner
    }

    @JvmStatic
    private fun getDistinctChunksFromBlockPosSet(blocks: Set<BlockPos>): Set<ChunkPos> {
        val chunkSet = hashSetOf<ChunkPos>()
        for (blockPos in blocks) {
            val chunkPos = ChunkPos(blockPos)
            chunkSet.add(chunkPos)
        }
        return chunkSet
    }

    data class AssembleContext(val ship: ServerShip, val fromCenter: Vector3d, val toCenter: Vector3d)

    @JvmStatic
    @OptIn(GameTickOnly::class)
    fun assembleToShipFull(level: ServerLevel, blocks: Set<BlockPos>, scale: Double = 1.0): AssembleContext {
        if (blocks.isEmpty()) {
            val error = RuntimeException("Assembly function received an empty set of blocks")
            ASSEMBLY_LOGGER.error(error)
            throw error
        }

        val (minB, maxB) = findMinAndMax(blocks)
        val oldMin = minB.toJOMLD()
        val oldMax = maxB.toJOMLD()
        //offset to center from corner of structure
        val offset = oldMax.get(Vector3d())
            .sub(oldMin)
            .add(1.0, 1.0, 1.0)
            .div(2.0)
        val fromCenter = offset.get(Vector3d()).add(oldMin)

        val fromShip = level.getLoadedShipManagingPos(fromCenter) ?: level.getShipManagingPos(fromCenter)
        val oldScale = fromShip?.transform?.scaling?.x() ?: 1.0
        val worldOldCenter = fromShip?.shipToWorld?.transformPosition(fromCenter.get(Vector3d())) ?: fromCenter.get(Vector3d())

        val toShip = level.shipObjectWorld.createNewShipAtBlock(Vector3i(worldOldCenter, RoundingMode.FLOOR), false, scale * oldScale, level.dimensionId)
        toShip.isStatic = fromShip == null || fromShip.isStatic

        // Mark as recently spawned immediately so player movement packets processed
        // during chunk loading don't treat this new ship as "unloaded".
        EntityShipCollisionUtils.markShipAsRecentlySpawned(toShip.id, 2_000_000_000L) // ~2s gravity-hold on assembly (mobs/entities only)

        val (wasSuccessful, _, toCenter) = moveBlocksFromTo(level, blocks, fromShip, toShip, minB, maxB, toShip.chunkClaim.getCenterBlockCoordinates(level.yRange, Vector3i()))

        if (!wasSuccessful) {
            level.shipObjectWorld.deleteShip(toShip)
            val error = AssertionError("Couldn't move blocks")
            ASSEMBLY_LOGGER.error(error)
            throw error
        }

        //teleport fn uses COM as center of ship, so it calculates such offset that centerOfShip will be "center" instead
        val posOffset =
            Vector3d(toShip.inertiaData.centerOfMass)
                .sub(Vector3d(toCenter))
                .let { fromShip?.shipToWorld?.transformDirection(it) ?: it }

        (toShip as VsiServerShip).unsafeSetKinematics(vsCore.newBodyKinematics(
            fromShip?.velocity ?: Vector3d(),
            fromShip?.angularVelocity ?: Vector3d(),
            vsCore.newBodyTransform(
                (fromShip?.shipToWorld?.transformPosition(Vector3d(fromCenter)) ?: fromCenter).add(posOffset),
                fromShip?.transform?.shipToWorldRotation ?: Quaterniond(),
                Vector3d(scale * oldScale, scale * oldScale, scale * oldScale),
                toCenter
            )
        ))
        toShip.isStatic = false

        return AssembleContext(toShip, fromCenter, toCenter)
    }

    data class MoveContext(val wasSuccessful: Boolean, val fromCenter: Vector3d, val toCenter: Vector3d)
    private val failedMove = MoveContext(false, Vector3d(), Vector3d())

    @JvmStatic
    @OptIn(GameTickOnly::class)
    fun moveBlocksFromTo(
        level: ServerLevel,
        blocks: Set<BlockPos>,
        fromShip: ServerShip?, toShip: ServerShip?,
        minStructurePos: BlockPos, maxStructurePos: BlockPos,
        toCenter: Vector3i,
        removeOriginal: Boolean = true)
    : MoveContext {
        val blocks = blocks.filter { level.getBlockState(it).let{!it.isAir && !it.inAssemblyBlacklist()} }.toSet()
        if (blocks.isEmpty()) return failedMove

        val fromId = fromShip?.id ?: -1L
        val eventData = mutableMapOf<String, CompoundTag>()

        val oldMin = minStructurePos.toJOMLD()
        val oldMax = maxStructurePos.toJOMLD()
        //offset to center from corner of structure
        val offset = oldMax.get(Vector3d())
            .sub(oldMin)
            .add(1.0, 1.0, 1.0)
            .div(2.0)
        val fromCenter = offset.get(Vector3d()).add(oldMin)

        var wasSplittingEnabled = true
        if (fromShip is LoadedServerShip) {
            val splittingDisabler = fromShip.getAttachment(SplittingDisablerAttachment::class.java)
            wasSplittingEnabled = splittingDisabler?.canSplit() != false
            splittingDisabler?.disableSplitting()
        }

        // ========== Copy Blocks
        VSAssemblyEvents.beforeCopy.emit(VSAssemblyEvents.BeforeCopy(level, oldMin, oldMax, fromCenter, fromShip, blocks, eventData))

        val template = StructureTemplate()
        template as StructureTemplateFillFromVoxelSet
        template.`vs$fillFromVoxelSet`(
            level, blocks,
            fromShip?.let { listOf(it) } ?: emptyList(),
            SingleItemMap(fromId, fromCenter, Vector3d()),
            minStructurePos, maxStructurePos
        )

        // ========== Pause Chunk Updates

        val toChunkCenter = ChunkPos(toCenter.x.toInt() shr 4, toCenter.z.toInt() shr 4)

        val fromChunkX = ((minStructurePos.x + maxStructurePos.x) / 2) shr 4
        val fromChunkZ = ((minStructurePos.z + maxStructurePos.z) / 2) shr 4

        val deltaX = fromChunkX - toChunkCenter.x
        val deltaZ = fromChunkZ - toChunkCenter.z

        val chunksToBeUpdated = mutableMapOf<ChunkPos, Pair<ChunkPos, ChunkPos>>()
        getDistinctChunksFromBlockPosSet(blocks).forEach { sourcePos ->
            val destPos = ChunkPos(sourcePos.x - deltaX, sourcePos.z - deltaZ)
            chunksToBeUpdated[sourcePos] = Pair(sourcePos, destPos)
        }
        val chunkPairs = chunksToBeUpdated.values.toList()
        val chunkPoses = chunkPairs.flatMap { it.toList() }
        val chunkPosesJOML = chunkPoses.map { it.toJOML() }

        level.players().forEach { player ->
            ASSEMBLY_LOGGER.debug("Pausing chunk updates for ${player.name}")
            with(vsCore.simplePacketNetworking) {
                sendStopChunkUpdates(chunkPosesJOML, player.playerWrapper)
            }
        }

        // ========== Removing Old Blocks
        if (removeOriginal) {
            // Single-pass removal: clear block entities and set to air in one pass.
            // Use UPDATE_KNOWN_SHAPE to skip expensive neighbor shape updates during bulk removal,
            // and UPDATE_SUPPRESS_DROPS to prevent item drops. We use flag 2 (UPDATE_CLIENTS) to
            // send changes to clients. Skip the old two-pass approach (barrier then remove) which
            // doubled the work and triggered expensive recursive neighbor updates per block.
            val flags = Block.UPDATE_CLIENTS or Block.UPDATE_KNOWN_SHAPE or Block.UPDATE_SUPPRESS_DROPS or Block.UPDATE_MOVE_BY_PISTON
            for (pos in blocks) {
                val vacated = vacatedBy(level.getBlockState(pos))
                level.getBlockEntity(pos)?.let {
                    if (it is Clearable) {
                        it.clearContent()
                    } else {
                        it.loadFromTag(CompoundTag(), level.registryAccess())
                    }
                    level.removeBlockEntity(pos)
                }
                level.setBlock(pos, vacated, flags)
            }
            // Batch light updates after all blocks are removed
            for (pos in blocks) {
                level.chunkSource.lightEngine.checkBlock(pos)
            }
        }
        // ========== Placing New Blocks

        //structure template builds from a corner, so offset center of plot so that structure's center and center of
        //plot roughly align
        val cornerOfShip = Vector3d(toCenter)
            .sub(offset)
            .ceil()
            .let { BlockPos(
                it.x.toInt(),
                it.y.toInt(),
                it.z.toInt(),
            ) }

        val centerOfShip = cornerOfShip.toJOMLD().add(offset)

        val structureSettings = StructurePlaceSettings().addProcessor(
            ICopyableProcessor(
                SingleItemMap(fromId, toShip?.id ?: -1L, -1L) {it},
                SingleItemMap(fromId, Pair(fromCenter, Vector3d(centerOfShip)), Pair(Vector3d(), Vector3d()))
            )
        )

        structureSettings.rotationPivot = cornerOfShip

        VSAssemblyEvents.onPasteBeforeBlocksAreLoaded.emit(VSAssemblyEvents.OnPasteBeforeBlocksAreLoaded(level, fromShip, toShip, Pair(fromCenter, centerOfShip), eventData))
        template.placeInWorld(level, cornerOfShip, cornerOfShip, structureSettings, level.random, Block.UPDATE_CLIENTS)

        // Compute correct sky light for the destination blocks using column-based shadows.
        val moveDestPositions = blocks.map { srcPos ->
            val dx = srcPos.x - minStructurePos.x
            val dy = srcPos.y - minStructurePos.y
            val dz = srcPos.z - minStructurePos.z
            BlockPos(cornerOfShip.x + dx, cornerOfShip.y + dy, cornerOfShip.z + dz)
        }
        initSkyLightForShip(level, moveDestPositions)

        // ========== Carrying Attached Entities
        // Only when the originals are actually leaving: a copy leaves the source structure standing, so the
        // frames hanging on it are still supported and belong exactly where they are.
        if (removeOriginal) {
            carryBlockAttachedEntities(level, blocks, minStructurePos, maxStructurePos, cornerOfShip)
        }

        // ========== Resume Chunk Updates
        val timeAtExecution = level.server.tickCount
        level.server.executeIf(
            // This condition will return true if all modified chunks have been both loaded AND
            // chunk update packets were sent to players
            { chunkPoses.all(level::isChunkLoadedForVS) || level.server.tickCount - timeAtExecution > 60 }
        ) {
            if (level.server.tickCount - timeAtExecution > 60) {
                ASSEMBLY_LOGGER.warn("Timed out waiting for chunks to start ticking after assembly! Forcibly resuming...")
                ASSEMBLY_LOGGER.warn("All chunks involved in assembly: $chunkPoses")
                ASSEMBLY_LOGGER.warn("Chunks that were not loaded: ${chunkPoses.filterNot { level.isChunkLoadedForVS(it) }}")
            }
            // Once all the chunk updates are sent to players, we can tell them to restart chunk updates
            level.players().forEach { player ->
                ASSEMBLY_LOGGER.debug("Resuming chunk updates for ${player.name}")
                with (vsCore.simplePacketNetworking) {
                    sendRestartChunkUpdates(chunkPosesJOML, player.playerWrapper)
                }
            }
            VSAssemblyEvents.onPasteAfterBlocksAreLoaded.emit(VSAssemblyEvents.OnPasteAfterBlocksAreLoaded(level, fromShip, toShip, Pair(fromCenter, centerOfShip), eventData))
            //force update connectivity because this new assemblyslop doesn't update it :(
            if (VSCoreConfig.SERVER.sp.enableConnectivity) {
                for (pos in chunkPoses) {
                    val worldChunk = level.getChunk(pos.x, pos.z) ?: continue
                    val chunkSections = worldChunk.sections ?: continue
                    for (sectionY in 0 until worldChunk.sectionsCount) {
                        val sectionPos = Vector3i(pos.x, worldChunk.getSectionYFromSectionIndex(sectionY), pos.z)
                        val section = chunkSections[sectionY] ?: continue
                        if (section.hasOnlyAir()) continue
                        val update = section.toDenseVoxelUpdate(sectionPos)
                        level.shipObjectWorld.forceUpdateConnectivityChunk(
                            level.dimensionId,
                            sectionPos.x,
                            sectionPos.y,
                            sectionPos.z,
                            update
                        )
                    }
                }
            }

            if (fromShip is LoadedServerShip) {
                val splittingDisabler = fromShip.getAttachment(SplittingDisablerAttachment::class.java)
                if (wasSplittingEnabled) {
                    splittingDisabler?.enableSplitting()
                }
            }
        }

        // Voxy only rebuilds a chunk's LOD when that chunk is ingested, and assembly empties world
        // chunks that a distant client will never reload -- so the hull it just lost goes on standing
        // in the LOD next to the real ship, and only clears if someone flies out and reloads it. These
        // are the same columns the chunk-update packets above already cover: source and destination,
        // with the shipyard end filtered out when the batch is flushed, so one call is right whichever
        // direction the blocks moved.
        chunkPoses.forEach { VoxyLodRefresh.mark(level, it) }

        return MoveContext(true, fromCenter, centerOfShip)
    }

    @JvmStatic
    @OptIn(GameTickOnly::class)
    fun assembleToShip(level: ServerLevel, blocks: Set<BlockPos>, scale: Double = 1.0): ServerShip {
        return assembleToShipFull(level, blocks, scale).ship
    }
    //legacy method to not break shit
    @Deprecated("Old")
    fun assembleToShip(level: Level, blocks: List<BlockPos>, removeOriginal: Boolean, scale: Double = 1.0, shouldDisableSplitting: Boolean = false): ServerShip {
        return assembleToShip(level as ServerLevel, blocks.toSet(), scale)
    }

    /**
     * Batch-assembles multiple independent block sets into separate ships.
     * This is much faster than calling [assembleToShipFull] in a loop because it:
     * 1. Sends a single PacketStopChunkUpdates/PacketRestartChunkUpdates for all ships
     * 2. Batches all connectivity updates into one pass
     * 3. Uses a single executeIf callback instead of one per ship
     *
     * Each entry in [blockSets] is assembled into its own ship. Returns a list of [AssembleContext].
     */
    @JvmStatic
    @OptIn(GameTickOnly::class)
    fun batchAssembleToShips(level: ServerLevel, blockSets: List<Set<BlockPos>>, scale: Double = 1.0): List<AssembleContext> {
        if (blockSets.isEmpty()) return emptyList()

        val results = mutableListOf<AssembleContext>()
        val allDestChunkPoses = linkedSetOf<ChunkPos>()
        val allSourceChunkPoses = linkedSetOf<ChunkPos>()

        // Phase 1: Create all ships and compute chunk positions (no packets sent yet)
        data class PendingAssembly(
            val blocks: Set<BlockPos>,
            val fromShip: ServerShip?,
            val toShip: ServerShip,
            val minB: BlockPos,
            val maxB: BlockPos,
            val fromCenter: Vector3d,
            val toCenter: Vector3i,
            val offset: Vector3d,
            val sourceChunks: Set<ChunkPos>,
            val destChunks: Set<ChunkPos>
        )

        val pendingAssemblies = mutableListOf<PendingAssembly>()

        for (blockSet in blockSets) {
            if (blockSet.isEmpty()) continue

            val (minB, maxB) = findMinAndMax(blockSet)
            val oldMin = minB.toJOMLD()
            val oldMax = maxB.toJOMLD()
            val offset = oldMax.get(Vector3d()).sub(oldMin).add(1.0, 1.0, 1.0).div(2.0)
            val fromCenter = offset.get(Vector3d()).add(oldMin)

            val fromShip = level.getLoadedShipManagingPos(fromCenter) ?: level.getShipManagingPos(fromCenter)
            val oldScale = fromShip?.transform?.scaling?.x() ?: 1.0
            val worldOldCenter = fromShip?.shipToWorld?.transformPosition(fromCenter.get(Vector3d())) ?: fromCenter.get(Vector3d())

            val toShip = level.shipObjectWorld.createNewShipAtBlock(
                Vector3i(worldOldCenter, RoundingMode.FLOOR), false, scale * oldScale, level.dimensionId
            )
            toShip.isStatic = fromShip == null || fromShip.isStatic

            // Mark ship as recently spawned immediately so that player movement packets
            // processed during managedBlock (in the preload phase) don't treat this new
            // ship as "unloaded" and freeze the player.
            EntityShipCollisionUtils.markShipAsRecentlySpawned(toShip.id, 2_000_000_000L) // ~2s gravity-hold on assembly (mobs/entities only)

            val toCenter = toShip.chunkClaim.getCenterBlockCoordinates(level.yRange, Vector3i())
            val toChunkCenter = ChunkPos(toCenter.x.toInt() shr 4, toCenter.z.toInt() shr 4)
            val fromChunkX = ((minB.x + maxB.x) / 2) shr 4
            val fromChunkZ = ((minB.z + maxB.z) / 2) shr 4
            val deltaX = fromChunkX - toChunkCenter.x
            val deltaZ = fromChunkZ - toChunkCenter.z

            val sourceChunks = getDistinctChunksFromBlockPosSet(blockSet)
            val destChunks = sourceChunks.map { ChunkPos(it.x - deltaX, it.z - deltaZ) }.toSet()

            allSourceChunkPoses.addAll(sourceChunks)
            allDestChunkPoses.addAll(destChunks)

            pendingAssemblies.add(PendingAssembly(
                blockSet, fromShip, toShip, minB, maxB, fromCenter, toCenter, offset, sourceChunks, destChunks
            ))
        }

        if (pendingAssemblies.isEmpty()) return emptyList()

        // Phase 2: Send ONE batch PacketStopChunkUpdates for ALL ships
        val allChunkPoses = allSourceChunkPoses + allDestChunkPoses // already deduplicated (sets)
        val allChunkPosesJOML = allChunkPoses.map { it.toJOML() }
        level.players().forEach { player ->
            with(vsCore.simplePacketNetworking) {
                sendStopChunkUpdates(allChunkPosesJOML, player.playerWrapper)
            }
        }

        // Pre-load all destination chunks.
        // Uses addTicketWithRadius to schedule all chunk loads concurrently, then runs the
        // distance manager + main thread tasks until all chunks reach FULL status.
        // This is much faster than calling level.getChunk() 1000 times sequentially,
        // because the chunk pipeline processes multiple chunks on its worker thread pool.
        val chunkSource = level.chunkSource

        // Add tickets for all dest chunks first (non-blocking, just queues them)
        for (cp in allDestChunkPoses) {
            chunkSource.addTicketWithRadius(
                org.valkyrienskies.mod.common.world.VSTicketType.SHIP_CHUNK, cp, 0
            )
        }

        // Process tickets and wait for all chunks to reach FULL status.
        // runDistanceManagerUpdates processes the tickets we just added, creating
        // ChunkHolders and starting their pipelines. Then getChunk blocks on each
        // one — but since all pipelines are already running concurrently on the worker
        // thread pool, most will complete quickly.
        (chunkSource as org.valkyrienskies.mod.mixin.accessors.server.level.ServerChunkCacheAccessor)
            .callRunDistanceManagerUpdates()
        for (cp in allDestChunkPoses) {
            level.getChunk(cp.x, cp.z)
        }

        // Phase 3: Execute all block moves
        for (pending in pendingAssemblies) {
            // Cache block states during filtering to avoid double getBlockState calls
            val filteredBlocksWithState = mutableListOf<Pair<BlockPos, BlockState>>()
            for (pos in pending.blocks) {
                val state = level.getBlockState(pos)
                if (!state.isAir && !state.inAssemblyBlacklist()) {
                    filteredBlocksWithState.add(pos to state)
                }
            }

            if (filteredBlocksWithState.isEmpty()) {
                level.shipObjectWorld.deleteShip(pending.toShip)
                continue
            }

            val filteredBlocks = filteredBlocksWithState.map { it.first }.toSet()

            val fromId = pending.fromShip?.id ?: -1L
            val eventData = mutableMapOf<String, CompoundTag>()
            val oldMin = pending.minB.toJOMLD()
            val oldMax = pending.maxB.toJOMLD()
            val offset = pending.offset
            val fromCenter = pending.fromCenter

            // Disable splitting on source ship
            var wasSplittingEnabled = true
            if (pending.fromShip is LoadedServerShip) {
                val splittingDisabler = pending.fromShip.getAttachment(SplittingDisablerAttachment::class.java)
                wasSplittingEnabled = splittingDisabler?.canSplit() != false
                splittingDisabler?.disableSplitting()
            }

            VSAssemblyEvents.beforeCopy.emit(VSAssemblyEvents.BeforeCopy(level, oldMin, oldMax, fromCenter, pending.fromShip, filteredBlocks, eventData))

            // Place blocks at destination
            val cornerOfShip = Vector3d(pending.toCenter)
                .sub(offset)
                .ceil()
                .let { BlockPos(it.x.toInt(), it.y.toInt(), it.z.toInt()) }
            val centerOfShip = cornerOfShip.toJOMLD().add(offset)

            val removeFlags = Block.UPDATE_CLIENTS or Block.UPDATE_KNOWN_SHAPE or Block.UPDATE_SUPPRESS_DROPS or Block.UPDATE_MOVE_BY_PISTON

            // Fast path for small block sets (<=8 blocks): directly copy block state
            // instead of going through StructureTemplate (which serializes to NBT,
            // creates a template, then deserializes — ~40ms overhead per ship).
            // For 125 1-block ships, this saves ~5 seconds total.
            if (filteredBlocksWithState.size <= 8) {
                val destPositions = ArrayList<BlockPos>(filteredBlocksWithState.size)
                for ((srcPos, state) in filteredBlocksWithState) {
                    val beTag = level.getBlockEntity(srcPos)?.saveWithFullMetadata(level.registryAccess())
                    val dx = srcPos.x - pending.minB.x
                    val dy = srcPos.y - pending.minB.y
                    val dz = srcPos.z - pending.minB.z
                    val destPos = BlockPos(cornerOfShip.x + dx, cornerOfShip.y + dy, cornerOfShip.z + dz)
                    destPositions.add(destPos)

                    // Remove source — use chunk-level setBlockState to bypass all MC
                    // neighbor update machinery. Skip sendBlockUpdated since source chunks
                    // are stalled by PacketStopChunkUpdates.
                    level.getBlockEntity(srcPos)?.let {
                        if (it is Clearable) it.clearContent()
                        level.removeBlockEntity(srcPos)
                    }
                    val srcChunk = level.getChunkAt(srcPos)
                    srcChunk.setBlockState(srcPos, vacatedBy(state), 0)

                    // Place at destination using chunk-level setBlockState directly.
                    // This bypasses Level.setBlock's sendBlockUpdated + onBlockStateChange
                    // which are unnecessary while dest chunks are stalled.
                    // LevelChunk.setBlockState handles block entity creation internally.
                    val destChunk = level.getChunkAt(destPos)
                    destChunk.setBlockState(destPos, dried(state), 0)
                    beTag?.let { tag ->
                        tag.putInt("x", destPos.x)
                        tag.putInt("y", destPos.y)
                        tag.putInt("z", destPos.z)
                        level.getBlockEntity(destPos)?.loadFromTag(tag, level.registryAccess())
                    }
                }

                initSkyLightForShip(level, destPositions)
            } else {
                // Full StructureTemplate path for larger block sets
                val template = StructureTemplate()
                template as StructureTemplateFillFromVoxelSet
                template.`vs$fillFromVoxelSet`(
                    level, filteredBlocks,
                    pending.fromShip?.let { listOf(it) } ?: emptyList(),
                    SingleItemMap(fromId, fromCenter, Vector3d()),
                    pending.minB, pending.maxB
                )

                for (pos in filteredBlocks) {
                    val vacated = vacatedBy(level.getBlockState(pos))
                    level.getBlockEntity(pos)?.let {
                        if (it is Clearable) it.clearContent()
                        level.removeBlockEntity(pos)
                    }
                    level.setBlock(pos, vacated, removeFlags)
                }
                for (pos in filteredBlocks) {
                    level.chunkSource.lightEngine.checkBlock(pos)
                }

                val structureSettings = StructurePlaceSettings().addProcessor(
                    ICopyableProcessor(
                        SingleItemMap(fromId, pending.toShip.id, -1L) { it },
                        SingleItemMap(fromId, Pair(fromCenter, Vector3d(centerOfShip)), Pair(Vector3d(), Vector3d()))
                    )
                )
                structureSettings.rotationPivot = cornerOfShip

                VSAssemblyEvents.onPasteBeforeBlocksAreLoaded.emit(
                    VSAssemblyEvents.OnPasteBeforeBlocksAreLoaded(level, pending.fromShip, pending.toShip, Pair(fromCenter, centerOfShip), eventData)
                )
                template.placeInWorld(level, cornerOfShip, cornerOfShip, structureSettings, level.random, Block.UPDATE_CLIENTS)

                // Compute correct sky light for the ship using column-based shadows.
                val destPositions2 = filteredBlocks.map { srcPos ->
                    val dx = srcPos.x - pending.minB.x
                    val dy = srcPos.y - pending.minB.y
                    val dz = srcPos.z - pending.minB.z
                    BlockPos(cornerOfShip.x + dx, cornerOfShip.y + dy, cornerOfShip.z + dz)
                }
                initSkyLightForShip(level, destPositions2)
            }

            carryBlockAttachedEntities(level, filteredBlocks, pending.minB, pending.maxB, cornerOfShip)

            // Set kinematics
            val posOffset = Vector3d(pending.toShip.inertiaData.centerOfMass)
                .sub(Vector3d(centerOfShip))
                .let { pending.fromShip?.shipToWorld?.transformDirection(it) ?: it }

            val oldScale = pending.fromShip?.transform?.scaling?.x() ?: 1.0
            (pending.toShip as VsiServerShip).unsafeSetKinematics(vsCore.newBodyKinematics(
                pending.fromShip?.velocity ?: Vector3d(),
                pending.fromShip?.angularVelocity ?: Vector3d(),
                vsCore.newBodyTransform(
                    (pending.fromShip?.shipToWorld?.transformPosition(Vector3d(fromCenter)) ?: fromCenter).add(posOffset),
                    pending.fromShip?.transform?.shipToWorldRotation ?: Quaterniond(),
                    Vector3d(scale * oldScale, scale * oldScale, scale * oldScale),
                    centerOfShip
                )
            ))
            pending.toShip.isStatic = false

            results.add(AssembleContext(pending.toShip, fromCenter, centerOfShip))

            // Re-enable splitting on source
            if (pending.fromShip is LoadedServerShip && wasSplittingEnabled) {
                pending.fromShip.getAttachment(SplittingDisablerAttachment::class.java)?.enableSplitting()
            }
        }

        // Phase 4: ONE batch executeIf callback for ALL ships
        val destChunkPoses = allDestChunkPoses
        val timeAtExecution = level.server.tickCount
        level.server.executeIf(
            { destChunkPoses.all(level::isChunkLoadedForVS) || level.server.tickCount - timeAtExecution > 60 }
        ) {
            if (level.server.tickCount - timeAtExecution > 60) {
                ASSEMBLY_LOGGER.warn("Batch assembly: timed out waiting for ${destChunkPoses.size} chunks")
            }
            // Resume chunk updates for ALL ships at once
            level.players().forEach { player ->
                with(vsCore.simplePacketNetworking) {
                    sendRestartChunkUpdates(allChunkPosesJOML, player.playerWrapper)
                }
            }
            // Batch connectivity updates for all destination chunks
            if (VSCoreConfig.SERVER.sp.enableConnectivity) {
                for (pos in destChunkPoses) {
                    val worldChunk = level.getChunk(pos.x, pos.z) ?: continue
                    val chunkSections = worldChunk.sections ?: continue
                    for (sectionY in 0 until worldChunk.sectionsCount) {
                        val sectionPos = Vector3i(pos.x, worldChunk.getSectionYFromSectionIndex(sectionY), pos.z)
                        val section = chunkSections[sectionY] ?: continue
                        if (section.hasOnlyAir()) continue
                        val update = section.toDenseVoxelUpdate(sectionPos)
                        level.shipObjectWorld.forceUpdateConnectivityChunk(
                            level.dimensionId, sectionPos.x, sectionPos.y, sectionPos.z, update
                        )
                    }
                }
            }
        }

        return results
    }

    /**
     * What to leave behind where a ship block used to stand.
     *
     * Air, except where the block was holding water: a waterlogged fence taken out of the sea has to leave
     * SEA behind it, not a hole. Setting air there punched a block-shaped bubble in the water for every rail
     * and stair the hull owned, which vanilla then had to flow back into one tick at a time -- visible, slow,
     * and on a big ship enough of them to look like the assembly had bitten a chunk out of the ocean.
     *
     * This is the other half of StructureTemplateMixin.vs$dry: that one keeps the water out of the ship, this
     * one keeps it in the world. The two have to agree, or the water is either duplicated or destroyed.
     */
    private fun vacatedBy(state: BlockState): BlockState =
        if (state.hasProperty(BlockStateProperties.WATERLOGGED) && state.getValue(BlockStateProperties.WATERLOGGED)) {
            Blocks.WATER.defaultBlockState()
        } else {
            Blocks.AIR.defaultBlockState()
        }

    /**
     * The same block, minus any water it was holding -- the Kotlin twin of
     * StructureTemplateMixin.vs$dry, needed here because the small-ship fast path copies states directly
     * instead of going through a template.
     */
    private fun dried(state: BlockState): BlockState =
        if (state.hasProperty(BlockStateProperties.WATERLOGGED) && state.getValue(BlockStateProperties.WATERLOGGED)) {
            state.setValue(BlockStateProperties.WATERLOGGED, false)
        } else {
            state
        }

    @Suppress("unused")
    fun isValidShipBlock(state: BlockState?) : Boolean {
        if (state == null) return false
        if (state.isAir) return false
        val block: Block = state.block
        //assembly blacklist check
        return !state.inAssemblyBlacklist()
    }

    fun deleteShip(level: ServerLevel, ship: ServerShip, deleteBlocks: Boolean, dropBlocks: Boolean): Int {
        if (ship is LoadedServerShip) {
            val splittingDisabler = ship.getAttachment(SplittingDisablerAttachment::class.java)
            splittingDisabler?.disableSplitting()
        }
        if (deleteBlocks) {
            val aabb = ship.shipAABB ?: return 0
            // The same flags the bulk removal in assembleToShip uses, and for the same reasons. Flag 2 alone
            // (what this used, borrowed from /fill) leaves neighbour shape updates on, so taking a hull apart
            // block by block knocks every ladder, door, lantern and torch off whatever it was attached to on
            // the way past -- and each one drops as an item. For a ship being deleted because its contents
            // were saved somewhere first, that is a duplication bug: the fixtures come back with the ship AND
            // litter the sea where it used to be.
            val flags = Block.UPDATE_CLIENTS or Block.UPDATE_KNOWN_SHAPE or
                Block.UPDATE_SUPPRESS_DROPS or Block.UPDATE_MOVE_BY_PISTON
            val cursor = BlockPos.MutableBlockPos()
            aabb.forEach { x, y, z ->
                cursor.set(x, y, z)
                if (dropBlocks) {
                    level.destroyBlock(cursor, true)
                } else {
                    // Empty containers before the block goes. SUPPRESS_DROPS stops the BLOCK dropping itself;
                    // what is inside it is a separate question, and one a chest answers by spilling.
                    (level.getBlockEntity(cursor) as? Clearable)?.clearContent()
                    level.setBlock(cursor.immutable(), Blocks.AIR.defaultBlockState(), flags)
                }
            }
        }

        vsCore.deleteShips(level.shipObjectWorld, listOf<ServerShip>(ship))
        return 1
    }

    class ICopyableProcessor(
        val oldShipIdToNewShipId: Map<ShipId, ShipId>,
        val centerPositions: Map<Long, Pair<Vector3d, Vector3d>>
    ): StructureProcessor() {
        override fun processBlock(
            levelReader: LevelReader, oldBPos: BlockPos, newBPos: BlockPos,
            oldStructureBlockInfo: StructureTemplate.StructureBlockInfo,
            newStructureBlockInfo: StructureTemplate.StructureBlockInfo, structurePlaceSettings: StructurePlaceSettings
        ): StructureTemplate.StructureBlockInfo? {
            val block = newStructureBlockInfo.state.block
            if (block !is ICopyableBlock) return newStructureBlockInfo
            block.onPaste((levelReader as ServerLevelAccessor).level, newBPos, newStructureBlockInfo.state, oldShipIdToNewShipId, centerPositions, newStructureBlockInfo.nbt)
            return newStructureBlockInfo
        }

        // getType is used for referencing this processor from a datapack, which we don't need
        override fun getType(): StructureProcessorType<*>? = null
    }

    /**
     * Carries item frames, glow item frames, paintings and leash knots onto the ship along with the block each
     * one is attached to.
     *
     * These are ENTITIES, not blocks, so the voxel set an assembly copies never contains them: the wall goes to
     * the shipyard and the frame stays behind at its old coordinates, now hanging on nothing.
     * [BlockAttachedEntity.tick] re-runs its survives() check every 100 ticks, finds no support, and discards
     * itself with a dropItem -- so a few seconds after the ship sails the frame and its contents are lying on
     * the ground where the hull used to be.
     *
     * VS2 already intends these to live inside the ship: the vs_entities data pairs item_frame,
     * glow_item_frame, painting and leash_knot to valkyrienskies:shipyard, and the shipyard_entities mixins
     * render, collide and section them there. Nothing ever moved them there, because the generic
     * world-to-shipyard transfer keys off position CHANGES and a block-attached entity never moves on its own.
     * So carry them explicitly, by the same integer translation the blocks take.
     *
     * Only entities whose SUPPORT assembled are carried. For a hanging entity that support is the block BEHIND
     * it, not the one it occupies -- the entity itself hangs in the air block in front of the wall, which is
     * never part of the ship. Leash knots are the exception: they sit in the fence they are tied to.
     *
     * Must run AFTER the destination blocks are placed, so nothing is briefly unsupported at either end.
     */
    private fun carryBlockAttachedEntities(
        level: ServerLevel,
        blocks: Set<BlockPos>,
        minStructurePos: BlockPos,
        maxStructurePos: BlockPos,
        cornerOfShip: BlockPos
    ) {
        val dx = cornerOfShip.x - minStructurePos.x
        val dy = cornerOfShip.y - minStructurePos.y
        val dz = cornerOfShip.z - minStructurePos.z
        if (dx == 0 && dy == 0 && dz == 0) return

        // Inflated a block on every side: a hanging entity occupies the air block in FRONT of its wall, so one
        // mounted on an outward-facing hull face sits just outside the structure's own bounds.
        val searchBox = AABB(
            minStructurePos.x - 1.0, minStructurePos.y - 1.0, minStructurePos.z - 1.0,
            maxStructurePos.x + 2.0, maxStructurePos.y + 2.0, maxStructurePos.z + 2.0
        )

        for (entity in level.getEntitiesOfClass(BlockAttachedEntity::class.java, searchBox)) {
            if (entity.isRemoved) continue

            val anchor = entity.pos
            val support = if (entity is HangingEntity) anchor.relative(entity.direction.opposite) else anchor
            if (!blocks.contains(support)) continue

            // setPos on a BlockAttachedEntity re-derives its anchor BlockPos and bounding box from the
            // coordinates given, so aiming at the centre of the destination block moves the whole thing. A raw
            // position write would leave the anchor -- and so the survives() check -- pointing at the old block.
            entity.setPos(anchor.x + dx + 0.5, anchor.y + dy + 0.5, anchor.z + dz + 0.5)
        }
    }

    // Pre-computed "all sky light 15" DataLayer template (2048 bytes, every nibble = 0xF).
    // Cloning this is ~1000x faster than iterating 4096 voxels with DataLayer.set() per section.


    /**
     * Computes correct sky light for ship blocks using column shadows + BFS propagation.
     *
     * 1. Column pass: sky light 15 above all opaque blocks, 0 at and below them
     * 2. BFS pass: propagate light horizontally into shadow zones from lit neighbors
     *    (like vanilla's SkyLightEngine, but synchronous since checkBlock doesn't work
     *    for ship chunks on the threaded light engine)
     * 3. Queue the computed data via the light engine
     *
     * Optimized: uses a pre-filled "all 15" template and only modifies shadow columns,
     * avoiding 4096 iterations per section. For ships with no opaque blocks, skips BFS entirely.
     */
    internal fun initSkyLightForShip(level: ServerLevel, destPositions: List<BlockPos>) {
        if (destPositions.isEmpty()) return

        val lightEngine = level.chunkSource.lightEngine

        // Tell the light engine about each section that now has blocks.
        // During chunk generation (initializeLight), these sections were all air,
        // so the light engine doesn't know they contain blocks yet.
        val sectionPositions = destPositions.map {
            net.minecraft.core.SectionPos.of(it)
        }.distinct()
        for (sp in sectionPositions) {
            lightEngine.updateSectionStatus(sp, false)
        }

        // NOTE: We do NOT call propagateLightSources here. The vanilla lightChunk phase
        // already ran during chunk generation and initialized sky light for the empty chunk.
        // Calling propagateLightSources again would reinitialize from the heightmap, which
        // may be stale (not yet visible to the light thread), causing enclosed interiors
        // to be incorrectly filled with sky light 15.
        //
        // Instead, we rely on checkBlock to cast shadows from the newly placed blocks.
        // Since the chunk started with sky light 15 everywhere (empty), checkBlock for
        // each opaque block will reduce light to 0 and cascade to darken enclosed spaces.

        // Queue a checkBlock for every block position AND its 6 neighbors.
        // The block positions themselves need shadow computation, and the neighbor
        // air blocks (which may be in adjacent chunks) need their sky light updated
        // to account for the new opaque blocks.
        val checked = hashSetOf<BlockPos>()
        for (pos in destPositions) {
            if (checked.add(pos)) {
                lightEngine.checkBlock(pos)
            }
            for (dir in net.minecraft.core.Direction.entries) {
                val neighbor = pos.relative(dir)
                if (checked.add(neighbor)) {
                    lightEngine.checkBlock(neighbor)
                }
            }
        }
    }
}
