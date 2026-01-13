package net.ccbluex.liquidbounce.features.module.modules.combat

import net.ccbluex.liquidbounce.LiquidBounce
import net.ccbluex.liquidbounce.event.EventState
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

@ModuleInfo(
    name = "HitSelect",
    description = "Chỉ cho phép tấn công khi đủ điều kiện tối ưu (Combo/Crit/W-Tap).",
    category = ModuleCategory.COMBAT
)
class HitSelect : Module() {

    // Cài đặt các chế độ
    private val modeValue = ListValue("Mode", arrayOf("Second", "Criticals", "W-Tap"), "Second")
    private val maxAngleValue = FloatValue("MaxAngle", 60f, 10f, 180f)
    private val minDistanceValue = FloatValue("MinDistance", 2.5f, 0f, 6f)

    // Trạng thái nội bộ
    private var sprintState = false
    private var isModified = false
    private var savedSlowdown = 1.0f

    @EventTarget
    fun onUpdate(event: UpdateEvent) {
        // Reset lại chuyển động ở giai đoạn POST để tránh bị bug movement
        if (isModified) {
            resetMotion()
        }
    }

    @EventTarget
    fun onPacket(event: PacketEvent) {
        val packet = event.packet

        // 1. Theo dõi trạng thái Sprint của Client qua gói tin Action
        if (packet is C0BPacketEntityAction) {
            when (packet.action) {
                C0BPacketEntityAction.Action.START_SPRINTING -> sprintState = true
                C0BPacketEntityAction.Action.STOP_SPRINTING -> sprintState = false
                else -> {}
            }
        }

        // 2. Kiểm tra gói tin Tấn công (C02 UseEntity)
        if (packet is C02PacketUseEntity && packet.action == C02PacketUseEntity.Action.ATTACK) {
            val target = packet.getEntityFromWorld(mc.theWorld) ?: return

            // Bỏ qua nếu mục tiêu không phải sinh vật hoặc là cầu lửa của Ghast
            if (target is EntityLargeFireball || target !is EntityLivingBase) return

            var allowHit = true
            val player = mc.thePlayer ?: return

            // Logic lựa chọn đòn đánh dựa trên chế độ
            when (modeValue.get().lowercase()) {
                "second" -> allowHit = checkSecondHit(player, target)
                "criticals" -> allowHit = checkCriticals(player)
                "w-tap" -> allowHit = checkWTap(player, sprintState)
            }

            // Nếu không đủ điều kiện, hủy gói tin tấn công
            if (!allowHit) {
                event.cancelEvent()
            }
        }
    }

    /**
     * Chế độ SECOND: Tối ưu hóa đòn đánh thứ 2 để tạo combo
     */
    private fun checkSecondHit(player: EntityLivingBase, target: EntityLivingBase): Boolean {
        // Cho phép nếu mục tiêu đang trong thời gian nhận sát thương (đỏ người)
        if (target.hurtTime != 0) return true
        
        // Cho phép nếu người chơi vừa bị tấn công (tăng khả năng trade hit)
        if (player.hurtTime <= player.maxHurtTime - 1 && player.hurtTime > 0) return true

        // Cho phép nếu mục tiêu quá gần (tự vệ)
        if (player.getDistanceToEntity(target) < minDistanceValue.get()) return true

        // Kiểm tra góc di chuyển giữa 2 người (Facing each other)
        if (!isMovingTowards(target, player, maxAngleValue.get().toDouble()) || 
            !isMovingTowards(player, target, maxAngleValue.get().toDouble())) {
            return true
        }

        // Nếu không thỏa mãn, chặn hit này và sửa chuyển động
        applyMotionFix()
        return false
    }

    /**
     * Chế độ CRITICALS: Chỉ đánh khi có khả năng gây sát thương chí mạng
     */
    private fun checkCriticals(player: EntityLivingBase): Boolean {
        // Đánh khi đang rơi (Fall distance > 0)
        val isFalling = !player.onGround && player.fallDistance > 0.0f && !player.isOnLadder && !player.isInWater
        
        if (player.onGround || player.hurtTime != 0 || isFalling) {
            return true
        }

        applyMotionFix()
        return false
    }

    /**
     * Chế độ W-TAP: Chỉ đánh khi bạn vừa thực hiện động tác reset sprint
     */
    private fun checkWTap(player: EntityLivingBase, sprinting: Boolean): Boolean {
        if (player.isCollidedHorizontally || !mc.gameSettings.keyBindForward.isKeyDown) return true
        
        // Chỉ cho phép đánh khi đang ở trạng thái Sprint (đã thực hiện W-Tap xong)
        if (sprinting) return true

        applyMotionFix()
        return false
    }

    /**
     * Đồng bộ với module KeepSprint để không bị giảm tốc độ khi đòn đánh bị chặn
     */
    private fun applyMotionFix() {
        if (isModified) return
        
        val keepSprint = LiquidBounce.moduleManager.getModule(KeepSprint::class.java) as? KeepSprint ?: return

        // Lưu giá trị slowdown hiện tại của KeepSprint
        savedSlowdown = keepSprint.slowdownValue.get()
        // Set về 1.0 (không giảm tốc) để giữ nguyên tốc độ di chuyển
        keepSprint.slowdownValue.set(1.0f)
        isModified = true
    }

    private fun resetMotion() {
        if (!isModified) return
        
        val keepSprint = LiquidBounce.moduleManager.getModule(KeepSprint::class.java) as? KeepSprint ?: return
        keepSprint.slowdownValue.set(savedSlowdown)
        
        isModified = false
    }

    /**
     * Tính toán Vector để biết thực thể có đang di chuyển hướng về phía đối phương không
     */
    private fun isMovingTowards(source: EntityLivingBase, target: EntityLivingBase, maxAngle: Double): Boolean {
        val mx = source.posX - source.lastTickPosX
        val mz = source.posZ - source.lastTickPosZ
        val movementLength = sqrt(mx * mx + mz * mz)

        if (movementLength < 0.01) return false // Coi như đứng yên

        val tx = target.posX - source.posX
        val tz = target.posZ - source.posZ
        val targetLength = sqrt(tx * tx + tz * tz)

        if (targetLength < 0.01) return false

        // Sử dụng tích vô hướng (Dot Product) để tính góc giữa vector di chuyển và vector mục tiêu
        val dotProduct = (mx / movementLength) * (tx / targetLength) + (mz / movementLength) * (tz / targetLength)
        return dotProduct >= cos(Math.toRadians(maxAngle))
    }

    override fun onDisable() {
        resetMotion()
        isModified = false
        sprintState = false
    }

    override val tag: String
        get() = modeValue.get()
}
