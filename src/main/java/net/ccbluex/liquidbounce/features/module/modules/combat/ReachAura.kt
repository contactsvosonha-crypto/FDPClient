package net.ccbluex.liquidbounce.features.module.modules.combat

import net.ccbluex.liquidbounce.LiquidBounce
import net.ccbluex.liquidbounce.features.module.*
import net.ccbluex.liquidbounce.event.*
import net.ccbluex.liquidbounce.utils.client.MinecraftInstance
import net.ccbluex.liquidbounce.utils.attack.CombatCheck
import net.ccbluex.liquidbounce.utils.timing.MSTimer
import net.ccbluex.liquidbounce.config.*
import net.minecraft.client.entity.EntityOtherPlayerMP
import net.minecraft.entity.EntityLivingBase
import net.minecraft.network.play.client.C02PacketUseEntity
import net.minecraft.world.WorldSettings

object ReachAura : Module("ReachAura", Category.COMBAT) {

    // fixed choices & default to match when-blocks below
    private val modeValue by choices("Mode", arrayOf("AuraIntave", "AuraFakePlayer"), "AuraFakePlayer")
    private val aura by boolean("Aura", false)
    private val pulseDelayValue by int("PulseDelay", 200, 50..500) { modeValue == "AuraFakePlayer" || modeValue == "AuraIntave" }
    private val intaveTestHurtTimeValue by int("Intave-Packets", 5, 0..30) { modeValue == "AuraIntave" }

    private var fakePlayer: EntityOtherPlayerMP? = null
    private var currentTarget: EntityLivingBase? = null
    private var shown = false
    private val pulseTimer = MSTimer()

    override fun onEnable() {
        currentTarget = null
        removeFakePlayer()
        pulseTimer.reset()
    }

    override fun onDisable() {
        removeFakePlayer()
        currentTarget = null
    }

    private fun removeFakePlayer() {
        val world = MinecraftInstance.mc.theWorld ?: run {
            fakePlayer = null
            shown = false
            return
        }

        fakePlayer?.let { fp ->
            try {
                world.removeEntityFromWorld(fp.entityId)
            } catch (_: Exception) {
                try {
                    world.removeEntity(fp)
                } catch (_: Exception) { /* ignore */ }
            }
        }
        fakePlayer = null
        currentTarget = null
        shown = false
    }

    private fun attackEntity(entity: EntityLivingBase) {
        MinecraftInstance.mc.thePlayer?.let { player ->
            player.swingItem()
            MinecraftInstance.mc.netHandler?.addToSendQueue(C02PacketUseEntity(entity, C02PacketUseEntity.Action.ATTACK))
            if (MinecraftInstance.mc.playerController?.currentGameType != WorldSettings.GameType.SPECTATOR) {
                try {
                    player.attackTargetEntityWithCurrentItem(entity)
                } catch (_: Exception) { /* ignore */ }
            }
        }
    }

    private fun createFakePlayer(target: EntityLivingBase) {
        val mc = MinecraftInstance.mc
        val world = mc.theWorld ?: return
        val netHandler = mc.netHandler ?: return
        val playerInfo = try { netHandler.getPlayerInfo(target.uniqueID) } catch (_: Exception) { null } ?: return

        // ensure no duplicate
        removeFakePlayer()

        val faker = EntityOtherPlayerMP(world, playerInfo.gameProfile).apply {
            rotationYawHead = target.rotationYawHead
            renderYawOffset = target.renderYawOffset
            copyLocationAndAnglesFrom(target)
            health = target.health
            (0..4).forEach { index ->
                target.getEquipmentInSlot(index)?.let { setCurrentItemOrArmor(index, it) }
            }
        }

        try {
            world.addEntityToWorld(-1337, faker)
        } catch (_: Exception) {
            try {
                world.spawnEntityInWorld(faker)
            } catch (_: Exception) { /* ignore */ }
        }

        fakePlayer = faker
        shown = true
    }

    val onAttack = handler<AttackEvent> { event ->
        val target = event.targetEntity as? EntityLivingBase ?: return@handler
        CombatCheck.setTarget(target)

        when (modeValue) {
            "AuraIntave", "AuraFakePlayer" -> {
                if (fakePlayer == null) {
                    currentTarget = target
                    createFakePlayer(target)
                } else if (event.targetEntity == fakePlayer) {
                    currentTarget?.let { attackEntity(it) }
                    event.cancelEvent()
                } else {
                    removeFakePlayer()
                    currentTarget = target
                    createFakePlayer(target)
                }
            }
        }
    }

    val onUpdate = handler<UpdateEvent> {
        CombatCheck.updateCombatState()

        if (MinecraftInstance.mc.thePlayer == null || !CombatCheck.inCombat) {
            removeFakePlayer()
            return@handler
        }

        if (aura && !LiquidBounce.moduleManager.getModule(KillAura::class.java)!!.state) {
            removeFakePlayer()
            return@handler
        }

        val target = currentTarget
        if (target == null) {
            removeFakePlayer()
            return@handler
        }

        when (modeValue) {
            "AuraIntave" -> {
                fakePlayer?.let { faker ->
                    if (!faker.isEntityAlive || target.isDead || !target.isEntityAlive) {
                        removeFakePlayer()
                    } else {
                        faker.health = target.health
                        (0..4).forEach { index ->
                            target.getEquipmentInSlot(index)?.let { faker.setCurrentItemOrArmor(index, it) }
                        }
                        if (intaveTestHurtTimeValue > 0 && MinecraftInstance.mc.thePlayer.ticksExisted % intaveTestHurtTimeValue == 0) {
                            faker.rotationYawHead = target.rotationYawHead
                            faker.renderYawOffset = target.renderYawOffset
                            faker.copyLocationAndAnglesFrom(target)
                            pulseTimer.reset()
                        }
                    }
                }

                if (!shown) {
                    createFakePlayer(target)
                }
            }
            "AuraFakePlayer" -> {
                fakePlayer?.let { faker ->
                    if (!faker.isEntityAlive || target.isDead || !target.isEntityAlive) {
                        removeFakePlayer()
                    } else {
                        faker.health = target.health
                        (0..4).forEach { index ->
                            target.getEquipmentInSlot(index)?.let { faker.setCurrentItemOrArmor(index, it) }
                        }
                        if (pulseTimer.hasTimePassed(pulseDelayValue.toLong())) {
                            faker.rotationYawHead = target.rotationYawHead
                            faker.renderYawOffset = target.renderYawOffset
                            faker.copyLocationAndAnglesFrom(target)
                            pulseTimer.reset()
                        }
                    }
                }

                if (!shown) {
                    createFakePlayer(target)
                }
            }
        }
    }
}
