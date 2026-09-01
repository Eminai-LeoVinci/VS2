package org.valkyrienskies.mod.mixin.feature.structure_template;

import com.google.common.collect.Lists;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import org.jetbrains.annotations.NotNull;
import org.joml.Vector3d;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import java.util.List;
import org.valkyrienskies.core.api.ships.ServerShip;
import org.valkyrienskies.mod.common.assembly.ICopyableBlock;
import org.valkyrienskies.mod.util.StructureTemplateFillFromVoxelSet;

@Mixin(value = StructureTemplate.class)
public abstract class StructureTemplateMixin implements StructureTemplateFillFromVoxelSet {

    @Shadow
    private Vec3i size;

    @Final
    @Shadow
    private List<StructureTemplate.Palette> palettes;

    @Final
    @Shadow
    private List<StructureTemplate.StructureEntityInfo> entityInfoList;

    @Shadow
    private static List<StructureTemplate.StructureBlockInfo> buildInfoList(List<StructureTemplate.StructureBlockInfo> basicBlocks, List<StructureTemplate.StructureBlockInfo> blocksWithEntities, List<StructureTemplate.StructureBlockInfo> specialBlocks) {
        return null;
    }

    @Shadow
    private static void addToLists(StructureTemplate.StructureBlockInfo blockInfo, List<StructureTemplate.StructureBlockInfo> basicBlocks, List<StructureTemplate.StructureBlockInfo> blocksWithEntities, List<StructureTemplate.StructureBlockInfo> specialBlocks) {}

    /**
     * The state a block should have once it is aboard a ship: the same block, minus any water it was holding.
     *
     * A fence, stair or slab standing in the sea is waterlogged, and that flag is part of its blockstate -- so
     * copying the state verbatim carried a cube of ocean into the shipyard with every one of them. The ship
     * then sailed around with water trapped in its rails, and the copies rendered as untextured vanilla water
     * under a shaderpack because the sea they came from was nowhere near them.
     *
     * Stripped HERE because this is the one place a world blockstate becomes a template blockstate, and both
     * paths that matter run through it: assembling a hull, and capturing one to a bottle, a blueprint or a
     * pirate template. Fixing it at the two callers instead would have left whichever one was forgotten
     * quietly shipping water.
     *
     * Only the flag is cleared. The block itself is the player's and comes along exactly as built.
     */
    @Unique
    private static BlockState vs$dry(BlockState state) {
        if (state.hasProperty(BlockStateProperties.WATERLOGGED)
            && state.getValue(BlockStateProperties.WATERLOGGED)) {
            return state.setValue(BlockStateProperties.WATERLOGGED, false);
        }
        return state;
    }

    @Unique
    public void vs$fillFromVoxelSet(@NotNull Level level, @NotNull Iterable<BlockPos> voxels,
        @NotNull List<ServerShip> shipsBeingCopied, @NotNull Map<Long, Vector3d> centerPositions,
        @NotNull BlockPos min, @NotNull BlockPos max) {

        List<StructureTemplate.StructureBlockInfo> basicBlocks = Lists.newArrayList();
        List<StructureTemplate.StructureBlockInfo> blocksWithEntities = Lists.newArrayList();
        List<StructureTemplate.StructureBlockInfo> specialBlocks = Lists.newArrayList();

        for (BlockPos currentWorldPos : voxels) {
            BlockPos relativePos = currentWorldPos.subtract(min);
            BlockState blockState = vs$dry(level.getBlockState(currentWorldPos));

            BlockEntity blockEntity = level.getBlockEntity(currentWorldPos);
            StructureTemplate.StructureBlockInfo blockInfo;

            Block block = blockState.getBlock();
            CompoundTag customTag = null;
            if (block instanceof ICopyableBlock) {
                customTag = ((ICopyableBlock) block).onCopy((ServerLevel) level, currentWorldPos, blockState, blockEntity, shipsBeingCopied, centerPositions);
            }

            if (customTag != null) {
                blockInfo = new StructureTemplate.StructureBlockInfo(relativePos, blockState, customTag);
            } else if (blockEntity != null) {
                blockInfo = new StructureTemplate.StructureBlockInfo(relativePos, blockState, blockEntity.saveWithId());
            } else {
                blockInfo = new StructureTemplate.StructureBlockInfo(relativePos, blockState, null);
            }

            addToLists(blockInfo, basicBlocks, blocksWithEntities, specialBlocks);
        }

        this.size = new Vec3i(
            max.getX() - min.getX() + 1,
            max.getY() - min.getY() + 1,
            max.getZ() - min.getZ() + 1
        );

        List<StructureTemplate.StructureBlockInfo> finalBlockList = buildInfoList(basicBlocks, blocksWithEntities, specialBlocks);
        this.entityInfoList.clear();
        this.palettes.clear();
        this.palettes.add(PaletteInvoker.invokeInit(finalBlockList));
    }
}
