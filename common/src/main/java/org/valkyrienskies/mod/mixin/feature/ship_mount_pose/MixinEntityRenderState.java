package org.valkyrienskies.mod.mixin.feature.ship_mount_pose;

import net.minecraft.client.renderer.entity.state.EntityRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.valkyrienskies.mod.mixinducks.client.render.ShipMountPoseRenderState;

/**
 * Carries the "this rider stands at their ship mount" flag on EVERY render state, not just the player's.
 *
 * <p>Targeted at the BASE state on purpose (it began life on {@code AvatarRenderState} alone): the flag's
 * consumers -- {@code MixinModel}'s vanilla pose write and the EMF {@code is_riding} override in
 * {@code MixinEMFStandAtHelm} -- key off the state INTERFACE, so implementing it at the root is what lets a
 * mod pose any mounted creature standing the same way the helmsman is. Eureka's stationed gunners were the
 * first to need it: a villager seated at a cannon renders through {@code VillagerRenderState}, which the
 * old target never touched, so Fresh Animations sat him on the deck and nothing VS2 wrote could stand him
 * back up.
 *
 * <p>Render states are REUSED across entities, so anyone setting this flag must set it UNCONDITIONALLY on
 * every extract -- true or false -- exactly as {@code MixinAvatarRenderer} does, or one standing rider
 * leaks the pose to every entity sharing the renderer.
 */
@Mixin(EntityRenderState.class)
public class MixinEntityRenderState implements ShipMountPoseRenderState {

    @Unique
    private boolean vs$shipMountStanding;

    @Override
    public boolean vs$isShipMountStanding() {
        return this.vs$shipMountStanding;
    }

    @Override
    public void vs$setShipMountStanding(final boolean standing) {
        this.vs$shipMountStanding = standing;
    }
}
