package org.valkyrienskies.mod.fabric.common

import com.mojang.brigadier.arguments.BoolArgumentType
import com.mojang.brigadier.arguments.DoubleArgumentType
import com.mojang.brigadier.arguments.StringArgumentType
import dev.engine_room.flywheel.api.event.ReloadLevelRendererCallback
import fuzs.forgeconfigapiport.api.config.v2.ForgeConfigRegistry
import fuzs.forgeconfigapiport.api.config.v2.ModConfigEvents
import net.fabricmc.api.EnvType
import net.fabricmc.api.ModInitializer
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper
import net.fabricmc.fabric.api.client.rendering.v1.EntityModelLayerRegistry
import net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback
import net.fabricmc.fabric.api.event.lifecycle.v1.CommonLifecycleEvents
import net.fabricmc.fabric.api.itemgroup.v1.ItemGroupEvents
import net.fabricmc.fabric.api.`object`.builder.v1.block.entity.FabricBlockEntityTypeBuilder
import net.fabricmc.fabric.api.`object`.builder.v1.entity.FabricDefaultAttributeRegistry
import net.fabricmc.fabric.api.resource.IdentifiableResourceReloadListener
import net.fabricmc.fabric.api.resource.ResourceManagerHelper
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.client.CameraType
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.client.renderer.entity.EntityRendererProvider.Context
import net.minecraft.network.chat.Component
import net.minecraft.core.Registry
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.packs.PackType.SERVER_DATA
import net.minecraft.server.packs.resources.PreparableReloadListener.PreparationBarrier
import net.minecraft.server.packs.resources.ResourceManager
import net.minecraft.util.profiling.ProfilerFiller
import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.MobCategory
import net.minecraft.world.item.BlockItem
import net.minecraft.world.item.CreativeModeTabs
import net.minecraft.world.item.Item
import net.minecraft.world.item.Item.Properties
import net.minecraft.world.level.block.Block
import net.minecraftforge.fml.config.ModConfig
import org.valkyrienskies.mod.client.EmptyRenderer
import org.valkyrienskies.mod.client.ShipCameraZoom
import org.valkyrienskies.mod.client.ShipDebugRender
import org.valkyrienskies.mod.client.ShipMountPerspective
import org.valkyrienskies.mod.client.VSPhysicsEntityModel
import org.valkyrienskies.mod.client.VSPhysicsEntityRenderer
import org.valkyrienskies.mod.common.ValkyrienSkiesMod
import org.valkyrienskies.mod.common.ValkyrienSkiesMod.AREA_ASSEMBLER_ITEM
import org.valkyrienskies.mod.common.ValkyrienSkiesMod.CLASSIC_AREA_ASSEMBLER_ITEM
import org.valkyrienskies.mod.common.ValkyrienSkiesMod.CONNECTION_CHECKER_ITEM
import org.valkyrienskies.mod.common.ValkyrienSkiesMod.PHYSICS_ENTITY_CREATOR_ITEM
import org.valkyrienskies.mod.common.ValkyrienSkiesMod.SHIP_ASSEMBLER_ITEM
import org.valkyrienskies.mod.common.ValkyrienSkiesMod.SHIP_CREATOR_ITEM
import org.valkyrienskies.mod.common.ValkyrienSkiesMod.SHIP_CREATOR_ITEM_SMALLER
import org.valkyrienskies.mod.common.ValkyrienSkiesMod.SHIP_REMOVER_ITEM
import org.valkyrienskies.mod.common.ValkyrienSkiesMod.TEST_ANTIGRAV
import org.valkyrienskies.mod.common.ValkyrienSkiesMod.TEST_CHAIR
import org.valkyrienskies.mod.common.ValkyrienSkiesMod.TEST_FLAP
import org.valkyrienskies.mod.common.ValkyrienSkiesMod.TEST_HINGE
import org.valkyrienskies.mod.common.ValkyrienSkiesMod.TEST_THRUSTER
import org.valkyrienskies.mod.common.ValkyrienSkiesMod.TEST_WING
import org.valkyrienskies.mod.common.block.TestAntigravBlock
import org.valkyrienskies.mod.common.block.TestChairBlock
import org.valkyrienskies.mod.common.block.TestFlapBlock
import org.valkyrienskies.mod.common.block.TestHingeBlock
import org.valkyrienskies.mod.common.block.TestThrusterBlock
import org.valkyrienskies.mod.common.block.TestWingBlock
import org.valkyrienskies.mod.common.blockentity.TestAntigravBlockEntity
import org.valkyrienskies.mod.common.blockentity.TestHingeBlockEntity
import org.valkyrienskies.mod.common.blockentity.TestThrusterBlockEntity
import org.valkyrienskies.mod.common.command.VSCommands
import org.valkyrienskies.mod.common.config.DimensionParametersResolver
import org.valkyrienskies.mod.common.config.MassDatapackResolver
import org.valkyrienskies.mod.common.config.VSClientConfig
import org.valkyrienskies.mod.common.config.VSClientConfigLoader
import org.valkyrienskies.mod.common.config.VSConfigUpdater
import org.valkyrienskies.mod.common.config.VSEntityHandlerDataLoader
import org.valkyrienskies.mod.common.config.VSGameConfig
import org.valkyrienskies.mod.common.config.VSKeyBindings
import org.valkyrienskies.mod.common.entity.ShipMountingEntity
import org.valkyrienskies.mod.common.entity.VSPhysicsEntity
import org.valkyrienskies.mod.common.entity.handling.VSEntityManager
import org.valkyrienskies.mod.common.hooks.VSGameEvents
import org.valkyrienskies.mod.common.item.AreaAssemblerItem
import org.valkyrienskies.mod.common.item.ConnectionCheckerItem
import org.valkyrienskies.mod.common.item.PhysicsEntityCreatorItem
import org.valkyrienskies.mod.common.item.ShipAssemblerItem
import org.valkyrienskies.mod.common.item.ShipCreatorItem
import org.valkyrienskies.mod.common.item.ShipRemoverItem
import org.valkyrienskies.mod.common.networking.PacketRequestPassengerSeat
import org.valkyrienskies.mod.common.vsCore
import org.valkyrienskies.mod.compat.LoadedMods
import org.valkyrienskies.mod.compat.flywheel.FlywheelCompat
import org.valkyrienskies.mod.compat.flywheel.ShipEmbeddingManager
import org.valkyrienskies.mod.compat.hexcasting.HexcastingCompat
import org.valkyrienskies.mod.fabric.compat.dynmap.FabricDynmapHandler
import org.valkyrienskies.mod.fabric.compat.hexcasting.FabricShipAmbit
import org.valkyrienskies.mod.util.ClientConnectivityUpdateQueue
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean

class ValkyrienSkiesModFabric : ModInitializer {

    companion object {
        private val hasInitialized = AtomicBoolean(false)
    }

    override fun onInitialize() {
        if (hasInitialized.getAndSet(true)) return

        ForgeConfigRegistry.INSTANCE.apply {
            register(ValkyrienSkiesMod.MOD_ID, ModConfig.Type.SERVER, VSConfigUpdater.CORE_SERVER_SPEC, "valkyrienskies/vs-core-server.toml")
            register(ValkyrienSkiesMod.MOD_ID, ModConfig.Type.SERVER, VSConfigUpdater.SERVER_SPEC, "valkyrienskies/valkyrienskies-server.toml")
            register(ValkyrienSkiesMod.MOD_ID, ModConfig.Type.COMMON, VSConfigUpdater.COMMON_SPEC, "valkyrienskies/valkyrienskies-common.toml")
            register(ValkyrienSkiesMod.MOD_ID, ModConfig.Type.CLIENT, VSConfigUpdater.CLIENT_SPEC, "valkyrienskies/valkyrienskies-client.toml")
        }

        ModConfigEvents.reloading(ValkyrienSkiesMod.MOD_ID).register (VSConfigUpdater::update)
        ModConfigEvents.loading(ValkyrienSkiesMod.MOD_ID).register (VSConfigUpdater::update)

        ValkyrienSkiesMod.TEST_CHAIR = TestChairBlock
        ValkyrienSkiesMod.TEST_HINGE = TestHingeBlock
        ValkyrienSkiesMod.TEST_FLAP = TestFlapBlock
        ValkyrienSkiesMod.TEST_WING = TestWingBlock
        ValkyrienSkiesMod.TEST_THRUSTER = TestThrusterBlock
        ValkyrienSkiesMod.TEST_ANTIGRAV = TestAntigravBlock
        ValkyrienSkiesMod.CONNECTION_CHECKER_ITEM = ConnectionCheckerItem(
            Properties(),
            { 1.0 },
            { VSGameConfig.SERVER.minScaling }
        )
        ValkyrienSkiesMod.SHIP_CREATOR_ITEM = ShipCreatorItem(
            Properties(),
            { 1.0 },
            { VSGameConfig.SERVER.minScaling }
        )
        ValkyrienSkiesMod.SHIP_REMOVER_ITEM = ShipRemoverItem(
            Properties()
        )
        ValkyrienSkiesMod.SHIP_ASSEMBLER_ITEM = ShipAssemblerItem(Properties())
        ValkyrienSkiesMod.AREA_ASSEMBLER_ITEM = AreaAssemblerItem(
            Properties(),
            { 1.0 },
            { VSGameConfig.SERVER.minScaling }
        )
        ValkyrienSkiesMod.CLASSIC_AREA_ASSEMBLER_ITEM = AreaAssemblerItem(
            Properties(),
            { 1.0 },
            { VSGameConfig.SERVER.minScaling },
            true
        )
        ValkyrienSkiesMod.SHIP_CREATOR_ITEM_SMALLER = ShipCreatorItem(
            Properties(),
            { VSGameConfig.SERVER.miniShipSize },
            { VSGameConfig.SERVER.minScaling }
        )
        ValkyrienSkiesMod.PHYSICS_ENTITY_CREATOR_ITEM = PhysicsEntityCreatorItem(Properties())

        ValkyrienSkiesMod.SHIP_MOUNTING_ENTITY_TYPE = EntityType.Builder.of(
            ::ShipMountingEntity,
            MobCategory.MISC
        ).sized(.3f, .3f)
            .build(ResourceLocation(ValkyrienSkiesMod.MOD_ID, "ship_mounting_entity").toString())

        ValkyrienSkiesMod.PHYSICS_ENTITY_TYPE = EntityType.Builder.of(
            ::VSPhysicsEntity,
            MobCategory.MISC
        ).sized(1f, 1f)
            .updateInterval(1)
            .clientTrackingRange(10)
            .build(ResourceLocation(ValkyrienSkiesMod.MOD_ID, "vs_physics_entity").toString())

        ValkyrienSkiesMod.TEST_HINGE_BLOCK_ENTITY_TYPE =
            FabricBlockEntityTypeBuilder.create(::TestHingeBlockEntity, ValkyrienSkiesMod.TEST_HINGE).build()

        ValkyrienSkiesMod.TEST_THRUSTER_BLOCK_ENTITY_TYPE =
            FabricBlockEntityTypeBuilder.create(::TestThrusterBlockEntity, ValkyrienSkiesMod.TEST_THRUSTER).build()

        ValkyrienSkiesMod.TEST_ANTIGRAV_BLOCK_ENTITY_TYPE =
            FabricBlockEntityTypeBuilder.create(::TestAntigravBlockEntity, ValkyrienSkiesMod.TEST_ANTIGRAV).build()

        val isClient = FabricLoader.getInstance().environmentType == EnvType.CLIENT

        ValkyrienSkiesMod.init()

        if (isClient) {
            // Generate / load config/valkyrienskies_client.json (ship influence-border extension) before
            // client setup, so EntityDragger reads the persisted per-face values.
            VSClientConfigLoader.loadOrCreate()
            onInitializeClient()
        }

        VSEntityManager.registerContraptionHandler(ContraptionShipyardEntityHandlerFabric)

        registerBlockAndItem("test_chair", ValkyrienSkiesMod.TEST_CHAIR)
        registerBlockAndItem("test_hinge", ValkyrienSkiesMod.TEST_HINGE)
        registerBlockAndItem("test_flap", ValkyrienSkiesMod.TEST_FLAP)
        registerBlockAndItem("test_wing", ValkyrienSkiesMod.TEST_WING)
        registerBlockAndItem("test_thruster", ValkyrienSkiesMod.TEST_THRUSTER)
        registerBlockAndItem("test_antigrav", ValkyrienSkiesMod.TEST_ANTIGRAV)
        Registry.register(
            BuiltInRegistries.ITEM, ResourceLocation(ValkyrienSkiesMod.MOD_ID, "connection_checker"),
            ValkyrienSkiesMod.CONNECTION_CHECKER_ITEM
        )
        Registry.register(
            BuiltInRegistries.ITEM, ResourceLocation(ValkyrienSkiesMod.MOD_ID, "area_assembler"),
            ValkyrienSkiesMod.AREA_ASSEMBLER_ITEM
        )
        Registry.register(
            BuiltInRegistries.ITEM, ResourceLocation(ValkyrienSkiesMod.MOD_ID, "classic_area_assembler"),
            ValkyrienSkiesMod.CLASSIC_AREA_ASSEMBLER_ITEM
        )
        Registry.register(
            BuiltInRegistries.ITEM, ResourceLocation(ValkyrienSkiesMod.MOD_ID, "ship_assembler"),
            ValkyrienSkiesMod.SHIP_ASSEMBLER_ITEM
        )
        Registry.register(
            BuiltInRegistries.ITEM, ResourceLocation(ValkyrienSkiesMod.MOD_ID, "ship_creator"),
            ValkyrienSkiesMod.SHIP_CREATOR_ITEM
        )
        Registry.register(
            BuiltInRegistries.ITEM, ResourceLocation(ValkyrienSkiesMod.MOD_ID, "ship_creator_smaller"),
            ValkyrienSkiesMod.SHIP_CREATOR_ITEM_SMALLER
        )
        Registry.register(
            BuiltInRegistries.ITEM, ResourceLocation(ValkyrienSkiesMod.MOD_ID, "ship_remover"),
            ValkyrienSkiesMod.SHIP_REMOVER_ITEM
        )
        Registry.register(
            BuiltInRegistries.ITEM, ResourceLocation(ValkyrienSkiesMod.MOD_ID, "physics_entity_creator"),
            ValkyrienSkiesMod.PHYSICS_ENTITY_CREATOR_ITEM
        )
        Registry.register(
            BuiltInRegistries.ENTITY_TYPE, ResourceLocation(ValkyrienSkiesMod.MOD_ID, "ship_mounting_entity"),
            ValkyrienSkiesMod.SHIP_MOUNTING_ENTITY_TYPE
        )
        Registry.register(
            BuiltInRegistries.ENTITY_TYPE, ResourceLocation(ValkyrienSkiesMod.MOD_ID, "vs_physics_entity"),
            ValkyrienSkiesMod.PHYSICS_ENTITY_TYPE
        )
        FabricDefaultAttributeRegistry.register(ValkyrienSkiesMod.PHYSICS_ENTITY_TYPE, VSPhysicsEntity.Companion.createAttributes())



        Registry.register(
            BuiltInRegistries.BLOCK_ENTITY_TYPE, ResourceLocation(ValkyrienSkiesMod.MOD_ID, "test_hinge_block_entity"),
            ValkyrienSkiesMod.TEST_HINGE_BLOCK_ENTITY_TYPE
        )
        Registry.register(
            BuiltInRegistries.BLOCK_ENTITY_TYPE, ResourceLocation(ValkyrienSkiesMod.MOD_ID, "test_thruster_block_entity"),
            ValkyrienSkiesMod.TEST_THRUSTER_BLOCK_ENTITY_TYPE
        )
        Registry.register(
            BuiltInRegistries.BLOCK_ENTITY_TYPE, ResourceLocation(ValkyrienSkiesMod.MOD_ID, "test_antigrav_block_entity"),
            ValkyrienSkiesMod.TEST_ANTIGRAV_BLOCK_ENTITY_TYPE
        )

        // Registry.register(
        //     BuiltInRegistries.CREATIVE_MODE_TAB,
        //     ValkyrienSkiesMod.VS_CREATIVE_TAB,
        //     ValkyrienSkiesMod.createCreativeTab()
        // )

        ItemGroupEvents.modifyEntriesEvent(CreativeModeTabs.OP_BLOCKS).register { event ->
            event.accept(TEST_CHAIR.asItem())
            event.accept(TEST_HINGE.asItem())
            event.accept(TEST_FLAP.asItem())
            event.accept(TEST_WING.asItem())
            event.accept(TEST_THRUSTER.asItem())
            event.accept(TEST_ANTIGRAV.asItem())
            event.accept(CONNECTION_CHECKER_ITEM)
            event.accept(SHIP_CREATOR_ITEM)
            event.accept(SHIP_REMOVER_ITEM)
            event.accept(SHIP_ASSEMBLER_ITEM)
            event.accept(SHIP_CREATOR_ITEM_SMALLER)
            event.accept(AREA_ASSEMBLER_ITEM)
            event.accept(CLASSIC_AREA_ASSEMBLER_ITEM)
            event.accept(PHYSICS_ENTITY_CREATOR_ITEM)
        }

        CommandRegistrationCallback.EVENT.register { dispatcher ,d, _ ->
            VSCommands.registerServerCommands(dispatcher)
        }

        // registering data loaders
        val loader1 = MassDatapackResolver.loader // the get makes a new instance so get it only once
        val loader2 = VSEntityHandlerDataLoader // the get makes a new instance so get it only once
        val loader3 = DimensionParametersResolver
        ResourceManagerHelper.get(SERVER_DATA)
            .registerReloadListener(object : IdentifiableResourceReloadListener {
                override fun getFabricId(): ResourceLocation {
                    return ResourceLocation(ValkyrienSkiesMod.MOD_ID, "vs_mass")
                }

                override fun reload(
                    stage: PreparationBarrier,
                    resourceManager: ResourceManager,
                    preparationsProfiler: ProfilerFiller,
                    reloadProfiler: ProfilerFiller,
                    backgroundExecutor: Executor,
                    gameExecutor: Executor
                ): CompletableFuture<Void> {
                    return loader1.reload(
                        stage, resourceManager, preparationsProfiler, reloadProfiler,
                        backgroundExecutor, gameExecutor
                    ).thenAcceptBoth(
                        loader2.reload(
                            stage, resourceManager, preparationsProfiler, reloadProfiler,
                            backgroundExecutor, gameExecutor
                        )
                    ) { _, _ -> }
                }
            })
        ResourceManagerHelper.get(SERVER_DATA)
            .registerReloadListener(object : IdentifiableResourceReloadListener {
                override fun getFabricId(): ResourceLocation {
                    return ResourceLocation(ValkyrienSkiesMod.MOD_ID, "vs_dimension_parameters")
                }

                override fun reload(
                    stage: PreparationBarrier,
                    resourceManager: ResourceManager,
                    preparationsProfiler: ProfilerFiller,
                    reloadProfiler: ProfilerFiller,
                    backgroundExecutor: Executor,
                    gameExecutor: Executor
                ): CompletableFuture<Void> {
                    return loader3.reload(
                        stage, resourceManager, preparationsProfiler, reloadProfiler,
                        backgroundExecutor, gameExecutor
                    )
                }
            })
        CommonLifecycleEvents.TAGS_LOADED.register { _, _ ->
            VSGameEvents.tagsAreLoaded.emit(Unit)
        }

        if (FabricLoader.getInstance().isModLoaded("dynmap"))
            FabricDynmapHandler().register()

        if (FabricLoader.getInstance().isModLoaded("hexcasting"))
            HexcastingCompat.register(FabricShipAmbit::class.java)
    }

    /**
     * Only run on client
     */
    private fun onInitializeClient() {
        // Client-side /vs subcommands (influence-border tuning + wireframe toggle). All set CLIENT state
        // directly, so they MUST stay client-side; they coexist with the server-side /vs tree (Fabric runs
        // client commands first, the vs_command_passthrough mixin forwards unmatched server subcommands to
        // the server, and the command_suggestion_merge mixin makes these show up in `/vs ` tab-completion
        // despite the colliding `vs` root):
        //  - expand-influence / contract-influence: adjust the per-face ship influence-border extension
        //    (VSClientConfig, config/valkyrienskies_client.json) in-game instead of editing the JSON.
        //  - influence-border <bool>: toggle the blue wireframe debug render of every ship's influence
        //    border (ShipDebugRender.influenceBorder; drawn by ShipInfluenceBorderRenderer). Defaults OFF.
        ClientCommandRegistrationCallback.EVENT.register { dispatcher, _ ->
            dispatcher.register(
                ClientCommandManager.literal("vs")
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

        EntityRendererRegistry.register(
            ValkyrienSkiesMod.PHYSICS_ENTITY_TYPE
        ) { context: Context ->
            VSPhysicsEntityRenderer(
                context
            )
        }

        EntityModelLayerRegistry.registerModelLayer(
            VSPhysicsEntityModel.LAYER_LOCATION,
            VSPhysicsEntityModel.Companion::createBodyLayer
        )

        VSKeyBindings.clientSetup {
            KeyBindingHelper.registerKeyBinding(it)
        }

        // Mounted perspective cycle: while riding a ship mount (helm) VS2 owns the F5 key. Drain it at
        // START of the client tick -- before vanilla handleKeybinds (mid-tick) and Shoulder Surfing's
        // tail-of-tick handler ever see the clicks -- and advance the custom cycle in ShipMountPerspective:
        // first person -> vanilla back -> vanilla front -> ship view (zoomable) -> Shoulder Surfing (if
        // installed) -> first person. On foot the key is left alone, so vanilla and other camera mods
        // behave exactly as without VS2. The screen/overlay gate mirrors vanilla's handleKeybinds call site
        // so a GUI never eats perspective presses.
        ClientTickEvents.START_CLIENT_TICK.register { client ->
            if (client.player?.vehicle is ShipMountingEntity) {
                ShipMountPerspective.tickMounted()
                if (client.screen == null && client.overlay == null) {
                    while (client.options.keyTogglePerspective.consumeClick()) {
                        ShipMountPerspective.cycleMounted(client)
                    }
                }
            }
        }

        // Always drop back to first person when the player dismounts a ship mount (helm seat), whatever
        // camera the ship view was in, and reset the scroll-zoom + perspective for the next mount.
        // Dismounts are server-driven (sneak poll, helm broken), so the client detects them as a falling
        // edge on the vehicle field.
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
        // "/vs influence-border <bool>" toggle above (ShipDebugRender.influenceBorder).
        ShipInfluenceBorderRenderer.register()

        VSGameEvents.registriesCompleted.on {
            ClientConnectivityUpdateQueue.onRegistriesCompleted()
        }

        if (LoadedMods.flywheel == LoadedMods.FlywheelVersion.V1) {
            FlywheelCompat.initClient()
        }
        if(FabricLoader.getInstance().isModLoaded("flywheel")) ReloadLevelRendererCallback.EVENT.register(
            ReloadLevelRendererCallback { event: ClientLevel? -> ShipEmbeddingManager.INSTANCE.unloadAllShip() })
    }

    private val INFLUENCE_FACE_NAMES = listOf("Top", "Bottom", "Left", "Right", "Front", "Back")

    /**
     * Apply a signed change to one face of the global ship influence-border extension
     * ([VSClientConfig.CLIENT]). Positive [delta] expands the border outward, negative contracts it; the
     * per-face value is clamped at 0 -- the exact ship dimension, never smaller -- so e.g. contracting 10
     * from a value of 2 lands at 0, not -8. EntityDragger (the carry) reads these values live each tick, so
     * the change takes effect immediately (no reassemble, no relog), and it is persisted to the client JSON
     * so it survives a restart.
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
            BuiltInRegistries.BLOCK, ResourceLocation(ValkyrienSkiesMod.MOD_ID, registryName),
            block
        )
        val item = BlockItem(block, Properties())
        Registry.register(BuiltInRegistries.ITEM, ResourceLocation(ValkyrienSkiesMod.MOD_ID, registryName), item)
        return item
    }
}
