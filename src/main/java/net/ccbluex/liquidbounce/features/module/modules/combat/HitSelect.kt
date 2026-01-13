package net.ccbluex.liquidbounce.features.module.modules.combat

import net.ccbluex.liquidbounce.LiquidBounce
import net.ccbluex.liquidbounce.event.EventTarget
import net.ccbluex.liquidbounce.event.PacketEvent
import net.ccbluex.liquidbounce.event.UpdateEvent
import net.ccbluex.liquidbounce.features.module.Module
import net.ccbluex.liquidbounce.features.module.ModuleCategory
import net.ccbluex.liquidbounce.features.module.ModuleInfo
import net.ccbluex.liquidbounce.features.module.modules.movement.KeepSprint
import net.ccbluex.liquidbounce.value.FloatValue
import net.ccbluex.liquidbounce.value.ListValue
import net.minecraft.entity.EntityLivingBase
import net.minecraft.entity.projectile.EntityLargeFireball
import net.minecraft.network.play.client.C02PacketUseEntity
import net.minecraft.network.play.client.C0BPacketEntityAction
import net.minecraft.util.Vec3
import kotlin.math.cos
import kotlin.math.sqrt

// FDPClient yêu cầu khai báo đầy đủ các tham số trong ModuleInfo
@ModuleInfo(name = "HitSelect", category = ModuleCategory.COMBAT, description = "Optimize your hits.")
class HitSelect : Module() {

    private val modeValue = ListValue("Mode", arrayOf("Second", "Criticals", "W-Tap"), "Second")
    private val maxAngleValue = FloatValue("MaxAngle", 60f, 10f, 180f)
    private val minDistanceValue = FloatValue("MinDistance", 2.5f, 0f, 6f)

    private var sprintState = false
    private var isModified = false
    private var savedSlowdown = 1.0f

    @EventTarget
    fun onUpdate(event: UpdateEvent) {
        if (isModified) {
            resetMotion()
        }
    }

    @EventTarget
    fun onPacket(event: PacketEvent) {
        val packet = event.packet

        if (packet is C0BPacketEntityAction) {
            when (packet.action) {
                C0BPacketEntityAction.Action.START_SPRINTING -> sprintState = true
                C0BPacketEntityAction.Action.STOP_SPRINTING -> sprintState = false
                else -> {}
            }
        }

        if (packet is C02PacketUseEntity && packet.action == C02PacketUseEntity.Action.ATTACK) {
            val target = packet.getEntityFromWorld(mc.theWorld) ?: return
            if (target is EntityLargeFireball || target !is EntityLivingBase) return

            var allowHit = true
            val player = mc.thePlayer ?: return

            // Thay lowercase() bằng toLowerCase() để tương thích Java 8/Kotlin cũ
            val mode = modeValue.get().toLowerCase()
            when (mode) {
                "second" -> allowHit = checkSecondHit(player, target)
                "criticals" -> allowHit = checkCriticals(player)
                "w-tap" -> allowHit = checkWTap(player, sprintState)
            }

            if (!allowHit) {
                event.cancelEvent()
            }
        }
    }

    private fun checkSecondHit(player: EntityLivingBase, target: EntityLivingBase): Boolean {
        if (target.hurtTime != 0) return true
        if (player.hurtTime <= player.maxHurtTime - 1 && player.hurtTime > 0) return true
        if (player.getDistanceToEntity(target) < minDistanceValue.get()) return true

        if (!isMovingTowards(target, player, maxAngleValue.get().toDouble()) || 
            !isMovingTowards(player, target, maxAngleValue.get().toDouble())) {
            return true
        }

        applyMotionFix()
        return false
    }

    private fun checkCriticals(player: EntityLivingBase): Boolean {
        val isFalling = !player.onGround && player.fallDistance > 0.0f && !player.isOnLadder && !player.isInWater
        if (player.onGround || player.hurtTime != 0 || isFalling) return true
        applyMotionFix()
        return false
    }

    private fun checkWTap(player: EntityLivingBase, sprinting: Boolean): Boolean {
        if (player.isCollidedHorizontally || !mc.gameSettings.keyBindForward.isKeyDown) return true
        if (sprinting) return true
        applyMotionFix()
        return false
    }

    private fun applyMotionFix() {
        if (isModified) return
        val keepSprint = LiquidBounce.moduleManager.getModule(KeepSprint::class.java) as? KeepSprint ?: return
        savedSlowdown = keepSprint.slowdownValue.get()
        keepSprint.slowdownValue.set(1.0f)
        isModified = true
    }

    private fun resetMotion() {
        if (!isModified) return
        val keepSprint = LiquidBounce.moduleManager.getModule(KeepSprint::class.java) as? KeepSprint ?: return
        keepSprint.slowdownValue.set(savedSlowdown)
        isModified = false
    }

    private fun isMovingTowards(source: EntityLivingBase, target: EntityLivingBase, maxAngle: Double): Boolean {
        val mx = source.posX - source.lastTickPosX
        val mz = source.posZ - source.lastTickPosZ
        val movementLength = sqrt(mx * mx + mz * mz)
        if (movementLength < 0.01) return false
        val tx = target.posX - source.posX
        val tz = target.posZ - source.posZ
        val targetLength = sqrt(tx * tx + tz * tz)
        if (targetLength < 0.01) return false
        val dotProduct = (mx / movementLength) * (tx / targetLength) + (mz / movementLength) * (tz / targetLength)
        return dotProduct >= cos(Math.toRadians(maxAngle))
    }

    override val tag: String
        get() = modeValue.get()
}
