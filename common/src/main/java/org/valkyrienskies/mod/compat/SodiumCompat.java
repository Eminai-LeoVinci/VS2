package org.valkyrienskies.mod.compat;

import net.caffeinemc.mods.sodium.api.texture.SpriteUtil;
import net.caffeinemc.mods.sodium.client.render.chunk.map.ChunkStatus;
import net.caffeinemc.mods.sodium.client.render.chunk.map.ChunkTrackerHolder;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import org.valkyrienskies.mod.mixin.ValkyrienCommonMixinConfigPlugin;

public class SodiumCompat {

    /**
     * Whether animated sprites drawn by our own renderer have to be reported to Sodium by hand.
     *
     * <p>Sodium's "animate only visible textures" optimisation advances a sprite's animation only if
     * something marked it active that tick; anything it does not render itself is invisible to it. Ship
     * terrain is baked and drawn by {@code ShipTerrainMeshCache} and never enters Sodium's render lists,
     * so without this its fire, lava, magma and sea lanterns freeze on whatever frame the atlas happened
     * to be on.
     */
    public static boolean tracksSpriteAnimation() {
        return ValkyrienCommonMixinConfigPlugin.getVSRenderer() == VSRenderer.SODIUM;
    }

    /** Only ever true for sprites that actually animate, so callers can skip storing the rest. */
    public static boolean hasAnimation(final TextureAtlasSprite sprite) {
        if (ValkyrienCommonMixinConfigPlugin.getVSRenderer() != VSRenderer.SODIUM) {
            return false;
        }
        return SpriteUtil.INSTANCE.hasAnimation(sprite);
    }

    /** Call once per frame for every sprite a drawn ship section uses. */
    public static void markSpritesActive(final TextureAtlasSprite[] sprites) {
        if (ValkyrienCommonMixinConfigPlugin.getVSRenderer() != VSRenderer.SODIUM) {
            return;
        }
        for (final TextureAtlasSprite sprite : sprites) {
            SpriteUtil.INSTANCE.markSpriteActive(sprite);
        }
    }

    public static void onChunkAdded(final ClientLevel level, final int x, final int z) {
        if (ValkyrienCommonMixinConfigPlugin.getVSRenderer() == VSRenderer.SODIUM) {
            ChunkTrackerHolder.get(level).onChunkStatusAdded(x, z, ChunkStatus.FLAG_HAS_BLOCK_DATA);
        }
    }

    public static void onChunkRemoved(final ClientLevel level, final int x, final int z) {
        if (ValkyrienCommonMixinConfigPlugin.getVSRenderer() == VSRenderer.SODIUM) {
            ChunkTrackerHolder.get(level).onChunkStatusRemoved(x, z, ChunkStatus.FLAG_HAS_BLOCK_DATA);
        }
    }

}
