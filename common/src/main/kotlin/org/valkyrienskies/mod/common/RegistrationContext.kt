package org.valkyrienskies.mod.common

import net.minecraft.world.item.Item
import net.minecraft.world.level.block.state.BlockBehaviour

/**
 * 1.20.1 flavor of the 1.21.11 RegistrationContext helpers.
 *
 * On 1.21.11 (1.21.2+ really) a Block or Item must have its registry id stamped onto its Properties
 * (`setId`) before construction, so `blockProps()`/`itemProps()` there thread the id through a
 * registration context. 1.20.1 has no such requirement -- vanilla derives everything from the
 * registry entry -- so these are plain fresh Properties, kept under the same package and names so
 * downstream registration code (Eureka Armada's EurekaBlocks/EurekaItems) ports verbatim.
 */
fun blockProps(): BlockBehaviour.Properties = BlockBehaviour.Properties.of()

/** See [blockProps]. */
fun itemProps(): Item.Properties = Item.Properties()
