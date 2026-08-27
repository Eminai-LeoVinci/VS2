package org.valkyrienskies.mod.util

import net.minecraft.core.BlockPos
import net.minecraft.nbt.CompoundTag
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.Clearable
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.Rotation
import net.minecraft.world.level.block.Rotation.NONE
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.entity.RandomizableContainerBlockEntity
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.chunk.LevelChunk
import org.valkyrienskies.core.api.ships.ServerShip
import org.valkyrienskies.mod.compat.voxy.VoxyLodRefresh

val AIR = Blocks.AIR.defaultBlockState()

/**
 * Relocate block
 *
 * @param fromChunk
 * @param from coordinate (can be local or global coord)
 * @param toChunk
 * @param to coordinate (can be local or global coord)
 * @param toShip should be set when you're relocating to a ship
 * @param rotation Rotation.NONE is no change in direction, Rotation.CLOCKWISE_90 is 90 degrees clockwise, etc.
 */
fun relocateBlock(
    fromChunk: LevelChunk, from: BlockPos, toChunk: LevelChunk, to: BlockPos, doUpdate: Boolean, toShip: ServerShip?,
    rotation: Rotation = NONE
) {
    var state = fromChunk.getBlockState(from)
    val entity = fromChunk.getBlockEntity(from)

    // Voxy voxelises a chunk once, when it is ingested, and never revisits it. Eureka takes a ship apart
    // one relocation at a time, and it does so wherever she happened to die -- routinely inside the
    // server's simulation distance but well outside the client's render distance, so the client never
    // reloads those chunks and the LOD never learns the hull is there. The ship simply vanishes at range.
    // Both ends are noted; whichever one is the shipyard is filtered out when the batch is flushed.
    (fromChunk.level as? ServerLevel)?.let { VoxyLodRefresh.mark(it, fromChunk.pos.x, fromChunk.pos.z) }
    (toChunk.level as? ServerLevel)?.let { VoxyLodRefresh.mark(it, toChunk.pos.x, toChunk.pos.z) }

	val level = toChunk.level
	
    val tag = entity?.let {
        val tag = it.saveWithFullMetadata(fromChunk.level.registryAccess())
        tag.putInt("x", to.x)
        tag.putInt("y", to.y)
        tag.putInt("z", to.z)

        // so that it won't drop its contents
        if (it is Clearable) {
			val blockEntity = it as BlockEntity
			val emptyTag = CompoundTag()
            blockEntity.loadWithComponents(emptyTag, level.registryAccess())
			
            //it.clearContent()
        }

        // so loot containers dont drop its content
        if (it is RandomizableContainerBlockEntity) {
            it.setLootTable(null, 0)
        }
		level.removeBlockEntity(from)
        tag
    }

    // Kept unrotated for the POI bookkeeping below, which has to describe what was actually at `from`.
    val originalState = state
    val previousToState = toChunk.getBlockState(to)

    state = state.rotate(rotation)

    fromChunk.setBlockState(from, AIR, false)
    toChunk.setBlockState(to, state, false)

    // Keep the point-of-interest index in step with the blocks we just moved.
    //
    // Vanilla does this from Level.setBlock, and these are CHUNK-level writes that deliberately bypass it --
    // so without this call the POI manager is never told a workstation moved. Both halves go wrong, and both
    // were visible in game:
    //
    //  - Assembling left a STALE record behind at the old world position. Villagers went on treating it as a
    //    live job site: they would walk right past an adjacent bench to claim a helm that no longer existed,
    //    stand at nothing, and never be assigned a profession -- and any villager already employed there kept
    //    the job forever, because ValidateNearbyPoi asks the POI manager rather than the world, so the memory
    //    was never invalidated and LoseJobOnSiteLoss never fired. That is the villager still wearing a
    //    Crewman's coat after its wheel was broken.
    //  - Disassembling registered NO record at the new world position, so a helm that had been part of a ship
    //    was invisible to every villager. Placing a second helm beside it worked instantly, which is the
    //    clearest possible statement that the block was fine and the index was not.
    //
    // Applies to every POI block a ship carries -- beds, bells, lodestones and every workstation -- not just
    // ours. (1.21.1 names this Level.onBlockStateChange; later versions renamed it
    // updatePOIOnBlockStateChange.) isClientSide guards it because the client's implementation is a no-op
    // anyway.
    if (!level.isClientSide) {
        level.onBlockStateChange(from, originalState, AIR)
        level.onBlockStateChange(to, previousToState, state)
    }

    if (doUpdate) {
        updateBlock(level, from, to, state)
    }

    tag?.let {
        val be = level.getBlockEntity(to)!!

        be.loadWithComponents(it, toChunk.level.registryAccess())
    }
}

/**
 * Update block after relocate
 *
 * @param level
 * @param fromPos old position coordinate
 * @param toPos new position coordinate
 * @param toState new blockstate at toPos
 */
fun updateBlock(level: Level, fromPos: BlockPos, toPos: BlockPos, toState: BlockState) {

    // 75 = flag 1 (block update) & flag 2 (send to clients) + flag 8 (force rerenders)
    val flags = 11 or Block.UPDATE_MOVE_BY_PISTON or Block.UPDATE_SUPPRESS_DROPS 

    //updateNeighbourShapes recurses through nearby blocks, recursionLeft is the limit
    val recursionLeft = 511

    level.setBlocksDirty(fromPos, toState, AIR)
    level.sendBlockUpdated(fromPos, toState, AIR, flags)
    level.blockUpdated(fromPos, AIR.block)
    // This handles the update for neighboring blocks in worldspace
    AIR.updateIndirectNeighbourShapes(level, fromPos, flags, recursionLeft - 1)
    AIR.updateNeighbourShapes(level, fromPos, flags, recursionLeft)
    AIR.updateIndirectNeighbourShapes(level, fromPos, flags, recursionLeft)
    //This updates lighting for blocks in worldspace
    level.chunkSource.lightEngine.checkBlock(fromPos)

    level.setBlocksDirty(toPos, AIR, toState)
    level.sendBlockUpdated(toPos, AIR, toState, flags)
    level.blockUpdated(toPos, toState.block)
    if (!level.isClientSide && toState.hasAnalogOutputSignal()) {
        level.updateNeighbourForOutputSignal(toPos, toState.block)
    }
    // Update lighting for blocks in shipspace
    level.chunkSource.lightEngine.checkBlock(toPos)
}

/**
 * Relocate block
 *
 * @param from coordinate (can be local or global coord)
 * @param to coordinate (can be local or global coord)
 * @param doUpdate update blocks after moving
 * @param toShip should be set when you're relocating to a ship
 * @param rotation Rotation.NONE is no change in direction, Rotation.CLOCKWISE_90 is 90 degrees clockwise, etc.
 */
fun Level.relocateBlock(from: BlockPos, to: BlockPos, doUpdate: Boolean, toShip: ServerShip?, rotation: Rotation) =
    relocateBlock(getChunkAt(from), from, getChunkAt(to), to, doUpdate, toShip, rotation)
