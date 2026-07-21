package org.valkyrienskies.mod.fabric.mixin.compat.emf;

import java.util.function.Function;
import net.minecraft.client.model.geom.ModelPart;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.valkyrienskies.mod.mixinducks.client.render.ShipMountPoseRenderState;
import traben.entity_model_features.models.animation.EMFAnimationEntityContext;
import traben.entity_model_features.models.animation.state.EMFEntityRenderState;

/**
 * Entity Model Features (EMF) compat for the helm rider's arms-on-the-wheel pose.
 *
 * <p>{@code ship_mount_pose/MixinModel} reaches the helmsman's arms forward by writing
 * {@code HumanoidModel.rightArm/leftArm}. An EMF custom player model (Fresh Animations' "FA+Player")
 * draws its own geometry from its own parts, so those writes never reach the drawn figure -- the
 * helmsman stood at the wheel (see MixinEMFStandAtHelm) but with his arms hanging at his sides.
 *
 * <p>Posing the vanilla model earlier would not help either: FA composes each arm rotation from its
 * own idle/movement/equip terms and only folds the vanilla rotation in while an item action (block,
 * aim, brush) is running, so outside those actions the vanilla value is discarded entirely.
 *
 * <p>So pose EMF's OWN parts, at the last moment before they draw. EMF's render path is
 * {@code EMFModelPartWithState.render -> root.oneTimeRunnable(); root.animate(); super.render(...)},
 * which makes the TAIL of {@code animate()} the exact analogue of the vanilla mixin's
 * {@code renderToBuffer} HEAD: every pack expression has run, nothing has drawn yet.
 *
 * <p>Parts are resolved by CEM name through the whole hierarchy rather than as direct children, so
 * this holds however a pack nests its rig, and each name is optional -- the same injector serves the
 * player model (arms + the skin's sleeve overlay) and any armour model, and simply finds nothing on
 * a cape or elytra. The first-person hand is left alone, matching vanilla: that path renders the arm
 * directly instead of through the model, so it never picked up the helm pose there either.
 *
 * <p>String target + no EMF types in the mixin's own signature on purpose: VS2's EMF compile
 * dependency is a stale 1.20.1 build, and a string target also makes this a soft mixin that no-ops
 * when EMF is not installed.
 */
@Mixin(targets = "traben.entity_model_features.models.parts.EMFModelPartRoot", remap = false)
public abstract class MixinEMFArmsAtHelm {

    /** Matches the vanilla standing-helm pose in {@code ship_mount_pose/MixinModel}. */
    @Unique
    private static final float VS$HELM_ARM_X_ROT = -1.4F;

    /** CEM part names posed onto the wheel: both arms plus the skin's sleeve overlay. */
    @Unique
    private static final String[] VS$HELM_ARM_PARTS =
        {"right_arm", "left_arm", "right_sleeve", "left_sleeve"};

    @Inject(method = "animate", at = @At("TAIL"))
    private void vs$reachForShipHelm(final CallbackInfo ci) {
        if (EMFAnimationEntityContext.isFirstPersonHand) {
            return;
        }
        final EMFEntityRenderState state = EMFAnimationEntityContext.getEmfState();
        // The render state is the vanilla one under the hood, carrying the standing-helm flag that
        // ship_mount_pose/MixinAvatarRenderer already set -- so this asks the same question the
        // vanilla pose does, rather than re-deriving "is at a helm" from the entity.
        if (!(state instanceof ShipMountPoseRenderState pose) || !pose.vs$isShipMountStanding()) {
            return;
        }
        final Function<String, ModelPart> lookup = ((ModelPart) (Object) this).createPartLookup();
        for (final String partName : VS$HELM_ARM_PARTS) {
            final ModelPart part = lookup.apply(partName);
            if (part != null) {
                part.xRot = VS$HELM_ARM_X_ROT;
                part.yRot = 0.0F;
                part.zRot = 0.0F;
            }
        }
    }
}
