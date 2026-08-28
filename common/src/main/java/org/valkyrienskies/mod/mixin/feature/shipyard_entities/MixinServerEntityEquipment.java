package org.valkyrienskies.mod.mixin.feature.shipyard_entities;

import net.minecraft.server.level.ServerEntity;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.valkyrienskies.mod.common.VSGameUtilsKt;
import org.valkyrienskies.mod.mixin.accessors.world.entity.LivingEntityAccessor;

/**
 * Notice equipment changes on a living entity that lives in a shipyard.
 *
 * <p>Vanilla only ever spots a changed armour or held item from {@code LivingEntity.tick}, which calls the
 * private {@code detectEquipmentUpdates} and broadcasts the difference. Entities inside a shipyard do not
 * tick at all -- ship chunks are held at BLOCK_TICKING and never ENTITY_TICKING (see MixinChunkHolder) --
 * so that comparison never runs and the equipment packet is never sent.
 *
 * <p>In game: armour placed on an armour stand aboard a ship goes on server-side and stays invisible until
 * the player rejoins, because rejoining re-sends the stand's equipment as part of its pairing data. Sibling
 * of the ChunkMap fix that lets these entities broadcast their DATA at all; this covers the equipment
 * channel, which travels separately and is driven by ticking rather than by dirty-flag tracking.
 *
 * <p>Runs from the point where the entity's changes are already being broadcast, so it costs a six-slot
 * comparison for shipyard entities only, and nothing at all for the ordinary ticking ones vanilla handles.
 */
@Mixin(ServerEntity.class)
public abstract class MixinServerEntityEquipment {

    @Shadow
    @Final
    private Entity entity;

    @Inject(method = "sendChanges", at = @At("HEAD"))
    private void valkyrienskies$detectShipyardEquipment(final CallbackInfo ci) {
        if (!(this.entity instanceof LivingEntity)) {
            return;
        }
        final int chunkX = this.entity.blockPosition().getX() >> 4;
        final int chunkZ = this.entity.blockPosition().getZ() >> 4;
        if (!VSGameUtilsKt.isChunkInShipyard(this.entity.level(), chunkX, chunkZ)) {
            return;
        }
        ((LivingEntityAccessor) this.entity).valkyrienskies$detectEquipmentUpdates();
    }
}
