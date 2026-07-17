package org.valkyrienskies.mod.common.config

/**
 * Client-side VS2 settings loaded from `config/valkyrienskies_client.json` by [VSClientConfigLoader].
 *
 * VS2's primary config ([VSGameConfig]) is TOML/Forge-Config-API-Port backed. This small JSON config
 * -- mirroring Eureka's `vs_eureka.json` loader -- is how the influence-border extension becomes
 * editable without recompiling.
 *
 * NOTE (1.20.1 port): the upstream 1.21.x [VSClientConfig] also carries `shipRenderDistance` (ship
 * draw-distance far-clip). That FEATURE is not ported to 1.20.1 yet (no far-clip-plane mixin), so its
 * field is intentionally omitted here rather than added as dead no-op config. Re-add it alongside the
 * feature port. The `shipCameraZoomMin/Max` fields below ARE ported (see [ShipCameraZoom]).
 */
object VSClientConfig {

    @JvmField
    val CLIENT = Client()

    class Client {
        /**
         * Zoom-in limit for the ship-mounted third-person camera, as a multiplier on the camera
         * distance the ship sets from its size. 1.0 = the ship-set baseline; with the default 0.125
         * the scroll wheel can zoom in to one-eighth of that baseline distance.
         */
        var shipCameraZoomMin: Double = 0.125

        /**
         * Zoom-out limit for the ship-mounted third-person camera (scroll wheel while the ship
         * view is active). 2.0 = up to twice the ship-set camera distance.
         */
        var shipCameraZoomMax: Double = 2.0

        // Ship "influence" border extension, in blocks, added OUTWARD to each of the ship's six faces
        // independently. A player who jumps/walks off a moving ship (or creative-flies / elytra-glides off it)
        // keeps being carried with the ship until they leave this border; raising a value lets them drift farther
        // past that face before the ship releases them. 0 on a face = the exact ship dimension there (no lip).
        // Touching non-ship ground or water releases the carry immediately regardless of these values.
        //
        // Front/Back/Left/Right are HELM-oriented, not fixed ship-space axes: each ship's forward is learned
        // from its helm (see ShipInfluenceOrientation) and these four faces are rotated onto that ship's real
        // axes at read time, so "Front" always grows the bow however the ship was assembled. Top/Bottom stay
        // +-Y (gravity-aligned). A ship that has never been sat in this session falls back to +Z = Front until
        // a player sits at its helm once (which seeds the heading).
        //
        // (These live in the client JSON because on a single-player world the integrated server reads these same
        // values live each tick via EntityDragger, so editing them here takes effect immediately, no reassemble
        // or relog.)
        var influenceExtendLeft: Double = 2.0
        var influenceExtendRight: Double = 2.0
        var influenceExtendBottom: Double = 2.0
        var influenceExtendTop: Double = 2.0
        var influenceExtendBack: Double = 2.0
        var influenceExtendFront: Double = 2.0
    }
}
