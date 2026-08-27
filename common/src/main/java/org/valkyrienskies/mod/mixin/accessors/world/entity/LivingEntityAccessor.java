package org.valkyrienskies.mod.mixin.accessors.world.entity;

import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(LivingEntity.class)
public interface LivingEntityAccessor {
    @Invoker("detectEquipmentUpdates")
    void valkyrienskies$detectEquipmentUpdates();
}
