package org.valkyrienskies.mod.fabric.common

import com.mojang.brigadier.arguments.BoolArgumentType
import com.mojang.brigadier.arguments.DoubleArgumentType
import com.mojang.brigadier.arguments.StringArgumentType
import net.fabricmc.api.EnvType
import net.fabricmc.api.ModInitializer
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper
import net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry
import net.fabricmc.fabric.api.command.v2.ArgumentTypeRegistry
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback
import net.fabricmc.fabric.api.event.lifecycle.v1.CommonLifecycleEvents
import net.fabricmc.fabric.api.resource.IdentifiableResourceReloadListener
import net.fabricmc.fabric.api.resource.ResourceManagerHelper
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.client.CameraType
import net.minecraft.client.gui.screens.ChatScreen
import net.minecraft.client.renderer.entity.EntityRendererProvider.Context
import net.minecraft.commands.synchronization.SingletonArgumentInfo
import net.minecraft.network.chat.Component
import net.minecraft.core.Registry
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.core.registries.Registries
import net.minecraft.resources.Identifier
import net.minecraft.resources.ResourceKey
import net.minecraft.server.packs.PackType.SERVER_DATA
import net.minecraft.server.packs.resources.PreparableReloadListener.PreparationBarrier
import net.minecraft.server.packs.resources.PreparableReloadListener.SharedState
import net.minecraft.server.packs.resources.ResourceManager
import net.minecraft.util.profiling.ProfilerFiller
import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.MobCategory
import net.minecraft.world.item.BlockItem
import net.minecraft.world.item.Item
import net.minecraft.world.item.Item.Properties
import net.minecraft.world.level.block.Block
import org.valkyrienskies.mod.client.EmptyRenderer
import org.valkyrienskies.mod.client.ShipCameraZoom
import org.valkyrienskies.mod.client.ShipDebugRender
import org.valkyrienskies.mod.client.ShipGamepad
import org.valkyrienskies.mod.client.ShipMountPerspective
import fuzs.forgeconfigapiport.fabric.api.v5.ConfigRegistry
import fuzs.forgeconfigapiport.fabric.api.v5.ModConfigEvents
import net.neoforged.fml.config.ModConfig
import org.valkyrienskies.mod.common.ValkyrienSkiesMod
import org.valkyrienskies.mod.common.itemKey
import org.valkyrienskies.mod.common.itemProps
import org.valkyrienskies.mod.common.withRegistryId
import org.valkyrienskies.mod.common.config.VSConfigUpdater
import org.valkyrienskies.mod.common.command.VSCommands
import org.valkyrienskies.mod.common.command.arguments.RelativeVector3Argument
import org.valkyrienskies.mod.common.command.arguments.ShipArgument
import org.valkyrienskies.mod.common.command.arguments.ShipArgumentInfo
import org.valkyrienskies.mod.common.config.MassDatapackResolver
import org.valkyrienskies.mod.common.config.VSEntityHandlerDataLoader
import org.valkyrienskies.mod.common.config.VSClientConfig
import org.valkyrienskies.mod.common.config.VSClientConfigLoader
import org.valkyrienskies.mod.common.config.VSGameConfig
import org.valkyrienskies.mod.common.config.VSKeyBindings
import org.valkyrienskies.mod.common.entity.ShipMountingEntity
import org.valkyrienskies.mod.common.hooks.VSGameEvents
import org.valkyrienskies.mod.common.item.ShipAssemblerItem
import org.valkyrienskies.mod.common.item.ShipCreatorItem
import org.valkyrienskies.mod.common.networking.PacketRequestPassengerSeat
import org.valkyrienskies.mod.common.vsCore
import org.valkyrienskies.mod.common.world.VSTicketType
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean

class ValkyrienSkiesModFabric : ModInitializer {

    companion object {
        private val hasInitialized = AtomicBoolean(false)
    }

    override fun onInitialize() {
        if (hasInitialized.getAndSet(true)) return

        ValkyrienSkiesMod.CONNECTION_CHECKER_ITEM =
            withRegistryId(itemKey(ValkyrienSkiesMod.MOD_ID, "connection_checker")) { Item(itemProps()) }
        ValkyrienSkiesMod.SHIP_CREATOR_ITEM =
            withRegistryId(itemKey(ValkyrienSkiesMod.MOD_ID, "ship_creator")) {
                ShipCreatorItem(
                    itemProps(),
                    { 1.0 },
                    { VSGameConfig.SERVER.minScaling }
                )
            }
        ValkyrienSkiesMod.SHIP_ASSEMBLER_ITEM =
            withRegistryId(itemKey(ValkyrienSkiesMod.MOD_ID, "ship_assembler")) { ShipAssemblerItem(itemProps()) }
        ValkyrienSkiesMod.AREA_ASSEMBLER_ITEM =
            withRegistryId(itemKey(ValkyrienSkiesMod.MOD_ID, "area_assembler")) { Item(itemProps()) }
        ValkyrienSkiesMod.SHIP_CREATOR_ITEM_SMALLER =
            withRegistryId(itemKey(ValkyrienSkiesMod.MOD_ID, "ship_creator_smaller")) {
                ShipCreatorItem(
                    itemProps(),
                    { VSGameConfig.SERVER.miniShipSize },
                    { VSGameConfig.SERVER.minScaling }
                )
            }
        ValkyrienSkiesMod.PHYSICS_ENTITY_CREATOR_ITEM =
            withRegistryId(itemKey(ValkyrienSkiesMod.MOD_ID, "physics_entity_creator")) { Item(itemProps()) }

        ValkyrienSkiesMod.SHIP_MOUNTING_ENTITY_TYPE = EntityType.Builder.of(
            ::ShipMountingEntity,
            MobCategory.MISC
        ).sized(.3f, .3f)
            .build(ResourceKey.create(Registries.ENTITY_TYPE,
                Identifier.fromNamespaceAndPath(ValkyrienSkiesMod.MOD_ID, "ship_mounting_entity")))

        // Register VS2's custom ship-chunk TicketType while BuiltInRegistries.TICKET_TYPE is
        // still writable. If left to lazy init, the first ship chunk ticketed mid-tick crashes
        // the server with "Registry is already frozen".
        VSTicketType.init()

        val isClient = FabricLoader.getInstance().environmentType == EnvType.CLIENT
        if (isClient) {
            // Load client render settings (config/valkyrienskies_client.json) -- e.g. ship render distance.
            VSClientConfigLoader.loadOrCreate()
            onInitializeClient()
        }

        ValkyrienSkiesMod.init()

        // 1.21.11 port: register VS2's custom command argument types via Fabric API.
        // The cross-loader MixinArgumentTypeInfos (@Inject into ArgumentTypeInfos.bootstrap)
        // does not weave on 1.21.11 -- ArgumentTypeInfos is class-loaded too early (during
        // BuiltInRegistries static init) for VS2's mixin config to apply, so ShipArgument
        // never landed in ArgumentTypeInfos.BY_CLASS and the server failed to serialize the
        // command tree on player join ("Couldn't place player in world / Invalid player
        // data"). ArgumentTypeRegistry runs during mod init and populates BY_CLASS + the
        // COMMAND_ARGUMENT_TYPE registry. The mixin is removed from the common mixins.json.
        ArgumentTypeRegistry.registerArgumentType(
            Identifier.fromNamespaceAndPath(ValkyrienSkiesMod.MOD_ID, "ship_argument"),
            ShipArgument::class.java,
            ShipArgumentInfo()
        )
        ArgumentTypeRegistry.registerArgumentType(
            Identifier.fromNamespaceAndPath(ValkyrienSkiesMod.MOD_ID, "relative_vector3_argument"),
            RelativeVector3Argument::class.java,
            SingletonArgumentInfo.contextFree(::RelativeVector3Argument)
        )

        // Register our four ModConfigSpecs with fcap-fabric's NeoForgeConfigRegistry
        // (the Fabric-side equivalent of NeoForge's ModContainer.registerConfig). Without
        // this, every ConfigValue has no backing config file and operations like /vs
        // backend ... NPE with "Cannot set config value without assigned Config object
        // present". Mirrors the Forge-side registration in ValkyrienSkiesModForge.
        //
        // fcap RENAMED this API between the 1.21.1 and 1.21.11 lines: v4's
        // neoforge.v4.NeoForgeConfigRegistry and NeoForgeModConfigEvents became v5's
        // v5.ConfigRegistry and v5.ModConfigEvents. The port kept importing v4, which the
        // shipped 21.11.x jar does not carry, so this threw NoClassDefFoundError on every
        // launch and the catch below quietly dropped EVERY core setting to its compiled-in
        // default -- physics threading included. The catch stays as a real safety net, for a
        // user genuinely running without fcap installed at all.
        val modId = ValkyrienSkiesMod.MOD_ID
        try {
            ConfigRegistry.INSTANCE.register(modId, ModConfig.Type.STARTUP, VSConfigUpdater.CORE_SERVER_SPEC, "valkyrienskies-core-server.toml")
            ConfigRegistry.INSTANCE.register(modId, ModConfig.Type.SERVER, VSConfigUpdater.SERVER_SPEC, "valkyrienskies-server.toml")
            ConfigRegistry.INSTANCE.register(modId, ModConfig.Type.COMMON, VSConfigUpdater.COMMON_SPEC, "valkyrienskies-common.toml")
            ConfigRegistry.INSTANCE.register(modId, ModConfig.Type.CLIENT, VSConfigUpdater.CLIENT_SPEC, "valkyrienskies-client.toml")

            // Propagate TOML changes (first-load + reload on external edits) back into the
            // in-memory VsiConfigModel so things that read the Kotlin vars pick up changes.
            // The Fabric path uses fcap's NeoForgeModConfigEvents instead of NeoForge's mod
            // event bus.
            val applyConfig = fun(config: ModConfig) {
                val spec = config.spec as? net.neoforged.neoforge.common.ModConfigSpec ?: return
                val loaded = config.loadedConfig?.config() ?: return
                VSConfigUpdater.applyFromConfigLoad(spec) { key -> loaded.get<Any?>(key) }
            }
            ModConfigEvents.loading(modId).register { applyConfig(it) }
            ModConfigEvents.reloading(modId).register { applyConfig(it) }
        } catch (t: Throwable) {
            org.apache.logging.log4j.LogManager.getLogger("ValkyrienSkies").warn(
                "Forge Config API Port unavailable; VS2 config TOMLs disabled, using defaults.", t
            )
        }
        // VSEntityManager.registerContraptionHandler(ContraptionShipyardEntityHandlerFabric)

        Registry.register(
            BuiltInRegistries.ITEM, Identifier.fromNamespaceAndPath(ValkyrienSkiesMod.MOD_ID, "connection_checker"),
            ValkyrienSkiesMod.CONNECTION_CHECKER_ITEM
        )
        Registry.register(
            BuiltInRegistries.ITEM, Identifier.fromNamespaceAndPath(ValkyrienSkiesMod.MOD_ID, "area_assembler"),
            ValkyrienSkiesMod.AREA_ASSEMBLER_ITEM
        )
        Registry.register(
            BuiltInRegistries.ITEM, Identifier.fromNamespaceAndPath(ValkyrienSkiesMod.MOD_ID, "ship_assembler"),
            ValkyrienSkiesMod.SHIP_ASSEMBLER_ITEM
        )
        Registry.register(
            BuiltInRegistries.ITEM, Identifier.fromNamespaceAndPath(ValkyrienSkiesMod.MOD_ID, "ship_creator"),
            ValkyrienSkiesMod.SHIP_CREATOR_ITEM
        )
        Registry.register(
            BuiltInRegistries.ITEM, Identifier.fromNamespaceAndPath(ValkyrienSkiesMod.MOD_ID, "ship_creator_smaller"),
            ValkyrienSkiesMod.SHIP_CREATOR_ITEM_SMALLER
        )
        Registry.register(
            BuiltInRegistries.ITEM, Identifier.fromNamespaceAndPath(ValkyrienSkiesMod.MOD_ID, "physics_entity_creator"),
            ValkyrienSkiesMod.PHYSICS_ENTITY_CREATOR_ITEM
        )
        Registry.register(
            BuiltInRegistries.ENTITY_TYPE, Identifier.fromNamespaceAndPath(ValkyrienSkiesMod.MOD_ID, "ship_mounting_entity"),
            ValkyrienSkiesMod.SHIP_MOUNTING_ENTITY_TYPE
        )
        Registry.register(
            BuiltInRegistries.CREATIVE_MODE_TAB,
            ValkyrienSkiesMod.VS_CREATIVE_TAB,
            ValkyrienSkiesMod.createCreativeTab()
        )

        CommandRegistrationCallback.EVENT.register { dispatcher ,d, _ ->
            VSCommands.registerServerCommands(dispatcher)
        }

        // registering data loaders
        val loader1 = MassDatapackResolver.loader // the get makes a new instance so get it only once
        val loader2 = VSEntityHandlerDataLoader // the get makes a new instance so get it only once
        ResourceManagerHelper.get(SERVER_DATA)
            .registerReloadListener(object : IdentifiableResourceReloadListener {
                override fun getFabricId(): Identifier {
                    return Identifier.fromNamespaceAndPath(ValkyrienSkiesMod.MOD_ID, "vs_mass")
                }

                override fun reload(
                    sharedState: SharedState,
                    backgroundExecutor: Executor,
                    barrier: PreparationBarrier,
                    gameExecutor: Executor
                ): CompletableFuture<Void> {
                    return loader1.reload(sharedState, backgroundExecutor, barrier, gameExecutor)
                        .thenAcceptBoth(
                            loader2.reload(sharedState, backgroundExecutor, barrier, gameExecutor)
                        ) { _, _ -> }
                }
            })
        CommonLifecycleEvents.TAGS_LOADED.register { _, _ ->
            VSGameEvents.tagsAreLoaded.emit(Unit)
        }

        VSDataComponents.registerDataComponents()
    }

    /**
     * Only run on client
     */
    private fun onInitializeClient() {
        // Client-side /vs subcommands. Two groups share this registration:
        //  - DEBUG/TEST TOGGLES (ship-shadows, ship-emissive, influence-border): flip client render features live
        //    for A/B testing; all default ON. Candidate for removal at the final project cleanup.
        //  - INFLUENCE TUNING (expand-influence, contract-influence): a permanent feature -- adjust the per-face
        //    ship influence-border extension in-game instead of editing the client JSON by hand.
        // All set CLIENT state directly (render config / VSClientConfig), so they MUST stay client-side. They
        // coexist with the server-side /vs tree (Fabric runs client commands first, falling through to the server
        // for unmatched subcommands). MixinClientPacketListener (command_suggestion_merge) repairs Fabric's
        // colliding-root merge so all of these show up in `/vs ` tab-completion.
        ClientCommandRegistrationCallback.EVENT.register { dispatcher, _ ->
            dispatcher.register(
                ClientCommandManager.literal("vs")
                    .then(
                        ClientCommandManager.literal("ship-shadows").then(
                            ClientCommandManager.argument("enabled", BoolArgumentType.bool()).executes { ctx ->
                                val enabled = BoolArgumentType.getBool(ctx, "enabled")
                                VSGameConfig.CLIENT.renderShipShadows = enabled
                                ctx.source.sendFeedback(
                                    Component.literal("VS ship shadows " + if (enabled) "ENABLED" else "DISABLED")
                                )
                                1
                            }
                        )
                    )
                    .then(
                        ClientCommandManager.literal("ship-emissive").then(
                            ClientCommandManager.argument("enabled", BoolArgumentType.bool()).executes { ctx ->
                                val enabled = BoolArgumentType.getBool(ctx, "enabled")
                                VSGameConfig.CLIENT.renderShipBlockIds = enabled
                                ctx.source.sendFeedback(
                                    Component.literal("VS ship emissive " + if (enabled) "ENABLED" else "DISABLED")
                                )
                                1
                            }
                        )
                    )
                    .then(
                        ClientCommandManager.literal("influence-border").then(
                            ClientCommandManager.argument("enabled", BoolArgumentType.bool()).executes { ctx ->
                                val enabled = BoolArgumentType.getBool(ctx, "enabled")
                                ShipDebugRender.influenceBorder = enabled
                                ctx.source.sendFeedback(
                                    Component.literal("VS influence border " + if (enabled) "ENABLED" else "DISABLED")
                                )
                                1
                            }
                        )
                    )
                    .then(
                        ClientCommandManager.literal("expand-influence").then(
                            ClientCommandManager.argument("amount", DoubleArgumentType.doubleArg(0.0)).then(
                                ClientCommandManager.argument("direction", StringArgumentType.word())
                                    .suggests { _, builder ->
                                        INFLUENCE_FACE_NAMES.forEach { builder.suggest(it) }
                                        builder.buildFuture()
                                    }
                                    .executes { ctx ->
                                        adjustInfluenceExtend(
                                            ctx.source,
                                            StringArgumentType.getString(ctx, "direction"),
                                            DoubleArgumentType.getDouble(ctx, "amount")
                                        )
                                    }
                            )
                        )
                    )
                    .then(
                        ClientCommandManager.literal("contract-influence").then(
                            ClientCommandManager.argument("amount", DoubleArgumentType.doubleArg(0.0)).then(
                                ClientCommandManager.argument("direction", StringArgumentType.word())
                                    .suggests { _, builder ->
                                        INFLUENCE_FACE_NAMES.forEach { builder.suggest(it) }
                                        builder.buildFuture()
                                    }
                                    .executes { ctx ->
                                        adjustInfluenceExtend(
                                            ctx.source,
                                            StringArgumentType.getString(ctx, "direction"),
                                            -DoubleArgumentType.getDouble(ctx, "amount")
                                        )
                                    }
                            )
                        )
                    )
            )
        }

        // Register the ship mounting entity renderer
        EntityRendererRegistry.register(
            ValkyrienSkiesMod.SHIP_MOUNTING_ENTITY_TYPE
        ) { context: Context ->
            EmptyRenderer(
                context
            )
        }
        VSKeyBindings.clientSetup {
            KeyBindingHelper.registerKeyBinding(it)
        }

        // Mounted perspective cycle: while riding a ship mount (helm, reconnect seat, sit-down
        // hotkey seat) VS2 owns the F5 key. Drain it at START of the client tick -- before
        // vanilla handleKeybinds (mid-tick) and Shoulder Surfing's tail-of-tick handler ever
        // see the clicks -- and advance the custom cycle in ShipMountPerspective: first person
        // -> vanilla back -> vanilla front -> ship view (zoomable) -> Shoulder Surfing (if
        // installed) -> first person. On foot the key is left alone, so vanilla and other
        // camera mods behave exactly as without VS2. The screen/overlay gate mirrors vanilla's
        // handleKeybinds call site so a GUI never eats perspective presses.
        var chatGuard = 0
        var lastCam: CameraType? = null
        var lastShipView = false
        ClientTickEvents.START_CLIENT_TICK.register { client ->
            // One gamepad poll per tick, ahead of every reader: the mounted D-pad handling below, the
            // driving packet's ascend/descend, and any downstream mod's own pad layer (Eureka's crouch
            // combos) all read the frame of state this establishes.
            ShipGamepad.poll()
            if (client.player?.vehicle is ShipMountingEntity) {
                // A controller mod's perspective button is NATIVE -- it sets the camera type directly and
                // never presses F5, so the click-drain cycle below never sees it and the virtual ship
                // view was unreachable from a pad. Detect its wrap instead: a FRONT -> FIRST transition
                // that did not leave the ship view is the cycle passing our slot, and is re-read as
                // entering it.
                if (lastCam == CameraType.THIRD_PERSON_FRONT &&
                    client.options.cameraType == CameraType.FIRST_PERSON && !lastShipView
                ) {
                    ShipMountPerspective.externalWrapToFirst(client)
                }
                ShipMountPerspective.tickMounted()
                // A controller mod's D-pad actions are NATIVE -- chat on up, a radial menu on right --
                // not keybinds, so no click drain can reach them, and each one opens a SCREEN over the
                // press. While the wheel owns the D-pad, any such screen popping up under a held or
                // just-released direction is the pad's own double-fire: close it, so a climb climbs and
                // a zoom keeps zooming instead of dying under a radial. The few-tick grace catches taps,
                // whose screens arrive after the button is already back up.
                if (chatGuard > 0) chatGuard--
                if (ShipGamepad.dpadUp() || ShipGamepad.dpadDown() ||
                    ShipGamepad.dpadLeft() || ShipGamepad.dpadRight()
                ) {
                    chatGuard = 5
                }
                val screen = client.screen
                if (chatGuard > 0 && screen != null &&
                    (screen is ChatScreen || screen.javaClass.name.startsWith("dev.isxander"))
                ) {
                    client.setScreen(null)
                }
                if (client.screen == null && client.overlay == null) {
                    while (client.options.keyTogglePerspective.consumeClick()) {
                        ShipMountPerspective.cycleMounted(client)
                    }
                    // D-pad zoom while the ship camera is on screen: LEFT eases in toward the ship-set
                    // baseline, RIGHT pulls out, half a notch per held tick -- the full sweep in under
                    // a second. The gate is read BOTH ways because the flag is written by the render
                    // pass and this runs at tick time: the virtual-slot state is the tick-side truth
                    // for the standing helm, the flag covers the seated third-person views.
                    if (ShipCameraZoom.isShipCameraActive() || ShipMountPerspective.isShipViewEngaged()) {
                        if (ShipGamepad.dpadLeft()) ShipCameraZoom.scroll(0.5)
                        if (ShipGamepad.dpadRight()) ShipCameraZoom.scroll(-0.5)
                    }
                    // A pad button usually carries a meaning of its own in a controller mod (D-pad Left
                    // commonly ships as Pick Block), delivered as a vanilla keybind click on the same
                    // physical press. While the wheel owns the D-pad, a fresh D-pad press claims the
                    // whole button: swallow every click queued this tick before handleKeybinds -- which
                    // runs mid-tick, after this hook -- can fire the other half.
                    if (ShipGamepad.anyDpadPressed()) {
                        for (mapping in client.options.keyMappings) {
                            while (mapping.consumeClick()) {
                                // drained
                            }
                        }
                    }
                }
            }
            // The wrap detector's memory of last tick, taken after everything above has had its say.
            lastCam = client.options.cameraType
            lastShipView = ShipMountPerspective.isShipViewEngaged()
        }

        // Always drop back to first person when the player dismounts a ship mount (helm seat),
        // whatever camera the ship view was in, and reset the scroll-zoom for the next mount.
        // Dismounts are server-driven (sneak poll, helm broken, seat killed), so the client
        // detects them as a falling edge on the vehicle field.
        var wasRidingShipMount = false
        ClientTickEvents.END_CLIENT_TICK.register { client ->
            val player = client.player

            // Sit-down hotkey (X): ask the server for a passenger seat on the ship the player is
            // standing on. Only the cheap gates live here (in-world, not already riding anything);
            // the on-a-ship check is the server's (PacketRequestPassengerSeat handler), which
            // silently ignores presses off-ship. consumeClick drains all queued presses.
            while (VSKeyBindings.shipSeat.get().consumeClick()) {
                if (player != null && !player.isPassenger) {
                    with(vsCore.simplePacketNetworking) { PacketRequestPassengerSeat().sendToServer() }
                }
            }

            val riding = player?.vehicle is ShipMountingEntity
            if (wasRidingShipMount && !riding) {
                ShipCameraZoom.reset()
                ShipMountPerspective.reset()
                if (player != null && !client.options.cameraType.isFirstPerson) {
                    client.options.cameraType = CameraType.FIRST_PERSON
                }
            }
            wasRidingShipMount = riding
        }

        // Per-frame thin blue oriented wireframe of each ship's influence border, gated on the
        // "/vs influence-border <bool>" toggle above (ShipDebugRender.influenceBorder). Drawn via a
        // WorldRenderEvents.AFTER_ENTITIES listener -- the 1.21.5+ pipeline-safe replacement for the old
        // DebugRenderer line-box mixin (removed). See ShipInfluenceBorderRenderer for the rationale.
        ShipInfluenceBorderRenderer.register()
    }

    private val INFLUENCE_FACE_NAMES = listOf("Top", "Bottom", "Left", "Right", "Front", "Back")

    /**
     * Apply a signed change to one face of the global ship influence-border extension
     * ([VSClientConfig.CLIENT]). Positive [delta] expands the border outward, negative contracts it; the
     * per-face value is clamped at 0 -- the exact ship dimension, never smaller -- so e.g. contracting 10
     * from a value of 2 lands at 0, not -8. EntityDragger (the carry) and the wireframe renderer both read
     * these values live each tick/frame, so the change takes effect immediately (no reassemble, no relog),
     * and it is persisted to the client JSON so it survives a restart.
     */
    private fun adjustInfluenceExtend(source: FabricClientCommandSource, direction: String, delta: Double): Int {
        val c = VSClientConfig.CLIENT
        val current = when (direction.lowercase()) {
            "top" -> c.influenceExtendTop
            "bottom" -> c.influenceExtendBottom
            "left" -> c.influenceExtendLeft
            "right" -> c.influenceExtendRight
            "front" -> c.influenceExtendFront
            "back" -> c.influenceExtendBack
            else -> {
                source.sendError(
                    Component.literal("Unknown direction '$direction' -- use Top, Bottom, Left, Right, Front, or Back.")
                )
                return 0
            }
        }
        val updated = (current + delta).coerceAtLeast(0.0)
        when (direction.lowercase()) {
            "top" -> c.influenceExtendTop = updated
            "bottom" -> c.influenceExtendBottom = updated
            "left" -> c.influenceExtendLeft = updated
            "right" -> c.influenceExtendRight = updated
            "front" -> c.influenceExtendFront = updated
            "back" -> c.influenceExtendBack = updated
        }
        VSClientConfigLoader.save()
        val face = direction.lowercase().replaceFirstChar { it.uppercase() }
        source.sendFeedback(
            Component.literal("Influence border: $face %.1f -> %.1f blocks".format(current, updated))
        )
        return 1
    }

    private fun registerBlockAndItem(registryName: String, block: Block): Item {
        Registry.register(
            BuiltInRegistries.BLOCK, Identifier.fromNamespaceAndPath(ValkyrienSkiesMod.MOD_ID, registryName),
            block
        )
        val item = withRegistryId(itemKey(ValkyrienSkiesMod.MOD_ID, registryName)) {
            BlockItem(block, itemProps())
        }
        Registry.register(BuiltInRegistries.ITEM, Identifier.fromNamespaceAndPath(ValkyrienSkiesMod.MOD_ID, registryName), item)
        return item
    }
}
