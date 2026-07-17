package org.valkyrienskies.mod.common.command.commands

import com.mojang.brigadier.arguments.BoolArgumentType
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import com.mojang.brigadier.context.CommandContext
import net.minecraft.commands.CommandSourceStack
import net.minecraft.commands.Commands.argument
import net.minecraft.commands.Commands.literal
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.entity.Entity
import net.minecraft.world.phys.BlockHitResult
import org.valkyrienskies.core.api.ships.LoadedServerShip
import org.valkyrienskies.core.api.ships.ServerShip
import org.valkyrienskies.mod.common.command.arguments.ShipArgument
import org.valkyrienskies.mod.common.config.VSGameConfig
import org.valkyrienskies.mod.common.getShipManagingPos
import org.valkyrienskies.mod.common.util.settings

/**
 * `/vs set-keep-active <true|false>`          -> the ship the player is looking at (ship-aware raytrace)
 * `/vs set-keep-active <ships> <true|false>`  -> explicit ship selector (e.g. @v[id=123])
 *
 * Toggles [org.valkyrienskies.mod.common.util.ShipSettings.keepActive]. When true, the targeted ships
 * keep simulating (physics / cruise / machinery) even when no player is within the vanilla simulation
 * distance; see [org.valkyrienskies.mod.common.world.ShipActivationManager]. Reuses the `/vs set-static`
 * permission.
 *
 * The bare (no-selector) form exists so the command is usable from chat without the arcane `@v[id=..]`
 * selector: the client-side command passthrough only forwards a command that parses to completion, so an
 * incomplete `/vs set-keep-active true` would fall through to the client dispatcher and error. The bare form
 * makes `/vs set-keep-active true` a complete, forwardable command targeting the current ship.
 */
object KeepActiveCommand {
    fun register(vs: LiteralArgumentBuilder<CommandSourceStack>) {
        vs.then(
            literal("set-keep-active")
                .requires { it.hasPermission(VSGameConfig.SERVER.Commands.setStaticShipCommandPerms) }
                // bare form: no selector -> the ship the player is looking at
                .then(
                    argument("keep-active", BoolArgumentType.bool()).executes { ctx ->
                        val value = BoolArgumentType.getBool(ctx, "keep-active")
                        val ship = lookedAtShip(ctx.source.entity)
                        if (ship == null) {
                            ctx.source.sendFailure(
                                Component.literal(
                                    "No ship found. Look at a block on the ship, " +
                                        "or use /vs set-keep-active <ships> $value"
                                )
                            )
                            0
                        } else {
                            apply(ctx, listOf(ship), value)
                        }
                    }
                )
                // explicit form: /vs set-keep-active <ships> <true|false>
                .then(
                    argument("ships", ShipArgument.ships()).then(
                        argument("keep-active", BoolArgumentType.bool()).executes { ctx ->
                            @Suppress("UNCHECKED_CAST")
                            val ships = ShipArgument.getShips(ctx, "ships").toList() as List<ServerShip>
                            val value = BoolArgumentType.getBool(ctx, "keep-active")
                            apply(ctx, ships, value)
                        }
                    )
                )
        )
    }

    /** Ship-aware raytrace: VS2 patches [Entity.pick], so this resolves ship blocks even on a moving ship. */
    private fun lookedAtShip(sourceEntity: Entity?): ServerShip? {
        if (sourceEntity == null) return null
        val level = sourceEntity.level() as? ServerLevel ?: return null
        val rayTrace = sourceEntity.pick(10.0, 1.0f, false)
        if (rayTrace is BlockHitResult) {
            return level.getShipManagingPos(rayTrace.blockPos)
        }
        return null
    }

    private fun apply(ctx: CommandContext<CommandSourceStack>, ships: List<ServerShip>, value: Boolean): Int {
        var changed = 0
        ships.forEach { ship ->
            (ship as? LoadedServerShip)?.let { loaded ->
                loaded.settings.keepActive = value
                changed++
            }
        }
        ctx.source.sendSuccess(
            { Component.literal("Set keepActive=$value on $changed ship(s)") }, true
        )
        return changed
    }
}
