package net.ccbluex.liquidbounce.features.module.modules.combat

import net.ccbluex.liquidbounce.LiquidBounce
import net.ccbluex.liquidbounce.event.*
import net.ccbluex.liquidbounce.features.module.Module
import net.ccbluex.liquidbounce.features.module.ModuleCategory
import net.ccbluex.liquidbounce.features.module.ModuleInfo
import net.ccbluex.liquidbounce.features.module.modules.combat.KillAura
import net.ccbluex.liquidbounce.utils.EntityUtils
import net.ccbluex.liquidbounce.utils.render.ColorUtils
import net.ccbluex.liquidbounce.utils.render.RenderUtils
import net.ccbluex.liquidbounce.utils.timer.MSTimer
import net.ccbluex.liquidbounce.value.*
import net.minecraft.client.entity.EntityOtherPlayerMP
import net.minecraft.client.renderer.GlStateManager
import net.minecraft.entity.EntityLivingBase
import net.minecraft.entity.player.EntityPlayer
import net.minecraft.item.ItemSword
import net.minecraft.network.Packet
import net.minecraft.network.play.client.*
import net.minecraft.util.AxisAlignedBB
import net.minecraft.util.Vec3
import org.lwjgl.opengl.GL11
import java.awt.Color
import java.util.*
import java.util.concurrent.LinkedBlockingQueue

@ModuleInfo(
    name = "LagRange",
    description = "Advanced Backtrack & Packet Delay for Combat Optimization.",
    category = ModuleCategory.COMBAT
)
class LagRange : Module() {

    /**
     * SETTINGS (CÀI ĐẶT CHI TIẾT)
     */
    private val modeValue = ListValue("Mode", arrayOf("Normal", "Smart", "Passive"), "Smart")
    private val maxDelayValue = IntegerValue("MaxDelay", 200, 50, 1000)
    private val rangeValue = FloatValue("ActivationRange", 10.0f, 3.0f, 30.0f)
    
    // Điều kiện lọc mục tiêu
    private val weaponsOnly = BoolValue("WeaponsOnly", true)
    private val teamCheck = BoolValue("TeamCheck", true)
    private val wallCheck = BoolValue("ThroughWalls", false)
    
    // Logic xả gói tin (Bypass)
    private val autoRelease = BoolValue("AutoRelease", true)
    private val releaseDistance = FloatValue("ReleaseDistance", 3.0f, 1.0f, 6.0f)
    private val packetLimit = IntegerValue("PacketLimit", 20, 5, 100)

    // Visuals (Hiển thị)
    private val renderMode = ListValue("RenderMode", arrayOf("Box", "Ghost", "Breadcrumbs", "None"), "Ghost")
    private val colorMode = ListValue("Color", arrayOf("Custom", "Rainbow", "Team"), "Custom")
    private val redValue = IntegerValue("Red", 0, 0, 255)
    private val greenValue = IntegerValue("Green", 255, 0, 255)
    private val blueValue = IntegerValue("Blue", 255, 0, 255)
    private val alphaValue = IntegerValue("Alpha", 80, 0, 255)

    /**
     * INTERNAL VARIABLES
     */
    private val packetQueue: Queue<Packet<C03PacketPlayer>> = LinkedBlockingQueue()
    private val historyPositions = LinkedList<Vec3>()
    private var ghostPlayer: EntityOtherPlayerMP? = null
    private var target: EntityLivingBase? = null
    private var isLagging = false
    private val timer = MSTimer()

    @EventTarget
    fun onUpdate(event: UpdateEvent) {
        val player = mc.thePlayer ?: return
        
        // 1. Kiểm tra vũ khí
        if (weaponsOnly.get() && (player.heldItem?.item !is ItemSword)) {
            if (isLagging) stopLag()
            return
        }

        // 2. Tìm kiếm mục tiêu thông minh
        target = findTarget()

        if (target != null) {
            val dist = player.getDistanceToEntity(target)
            
            // Logic bắt đầu lag
            if (!isLagging && dist <= rangeValue.get()) {
                startLag()
            }

            // 3. Logic Smart Release (Xả gói tin khi quá gần để đòn đánh không bị hủy)
            if (isLagging && autoRelease.get()) {
                if (dist <= releaseDistance.get() || timer.hasTimePassed(maxDelayValue.get().toLong()) || packetQueue.size > packetLimit.get()) {
                    releasePackets()
                }
            }
        } else {
            // Không có mục tiêu -> xả lag để tránh bị flag
            if (isLagging) stopLag()
        }
    }

    @EventTarget
    fun onPacket(event: PacketEvent) {
        val packet = event.packet

        // Chỉ trì hoãn gói tin di chuyển khi đang trong trạng thái Lag
        if (isLagging && packet is C03PacketPlayer) {
            event.cancelEvent()
            packetQueue.add(packet)
            
            // Lưu lịch sử vị trí để vẽ breadcrumbs
            if (historyPositions.size > 20) historyPositions.removeFirst()
            historyPositions.add(Vec3(packet.positionX, packet.positionY, packet.positionZ))
        }

        // Các gói tin hành động quan trọng phải ép xả lag để server nhận diện đúng sequence
        if (packet is C02PacketUseEntity || packet is C07PacketPlayerDigging || packet is C08PacketPlayerBlockPlacement) {
            if (isLagging) releasePackets()
        }
    }

    private fun startLag() {
        isLagging = true
        timer.reset()
        
        // Khởi tạo Ghost nếu cần
        if (renderMode.get().equals("Ghost", true)) {
            ghostPlayer = EntityOtherPlayerMP(mc.theWorld, mc.thePlayer.gameProfile).apply {
                copyLocationAndAnglesFrom(mc.thePlayer)
                rotationYawHead = mc.thePlayer.rotationYawHead
                renderYawOffset = mc.thePlayer.renderYawOffset
            }
        }
    }

    private fun releasePackets() {
        if (packetQueue.isNotEmpty()) {
            while (packetQueue.isNotEmpty()) {
                val p = packetQueue.poll()
                mc.netHandler.networkManager.sendPacket(p)
            }
        }
        timer.reset()
    }

    private fun stopLag() {
        releasePackets()
        isLagging = false
        ghostPlayer = null
        historyPositions.clear()
    }

    private fun findTarget(): EntityLivingBase? {
        val aura = LiquidBounce.moduleManager.getModule(KillAura::class.java) as KillAura
        
        // Ưu tiên mục tiêu của KillAura nếu có
        if (aura.state && aura.target != null) return aura.target

        return mc.theWorld.loadedEntityList
            .filterIsInstance<EntityLivingBase>()
            .filter { 
                it != mc.thePlayer && 
                EntityUtils.isSelected(it, true) && 
                mc.thePlayer.getDistanceToEntity(it) <= rangeValue.get() &&
                (wallCheck.get() || mc.thePlayer.canEntityBeSeen(it))
            }
            .minByOrNull { mc.thePlayer.getDistanceToEntity(it) }
    }

    @EventTarget
    fun onRender3D(event: Render3DEvent) {
        if (!isLagging || packetQueue.isEmpty()) return

        val oldestPacket = packetQueue.peek() ?: return
        val color = getCustomColor()

        when (renderMode.get().lowercase()) {
            "box" -> {
                val x = oldestPacket.positionX - mc.renderManager.viewerPosX
                val y = oldestPacket.positionY - mc.renderManager.viewerPosY
                val z = oldestPacket.positionZ - mc.renderManager.viewerPosZ
                val w = mc.thePlayer.width / 2.0
                val aabb = AxisAlignedBB(x - w, y, z - w, x + w, y + mc.thePlayer.height, z + w)
                
                RenderUtils.drawFilledBox(aabb, color.rgb)
                RenderUtils.drawNode(aabb, color.rgb) // Vẽ viền
            }
            
            "ghost" -> {
                ghostPlayer?.let {
                    // Vẽ thực thể mờ ảo
                    GlStateManager.pushMatrix()
                    GlStateManager.enableBlend()
                    GlStateManager.blendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA)
                    GlStateManager.color(color.red / 255f, color.green / 255f, color.blue / 255f, alphaValue.get() / 255f)
                    mc.renderManager.renderEntitySimple(it, event.partialTicks)
                    GlStateManager.disableBlend()
                    GlStateManager.popMatrix()
                }
            }

            "breadcrumbs" -> {
                if (historyPositions.size > 1) {
                    RenderUtils.drawBreadcrumbs(historyPositions, color.rgb)
                }
            }
        }
    }

    private fun getCustomColor(): Color {
        return when (colorMode.get().lowercase()) {
            "rainbow" -> ColorUtils.rainbow()
            "team" -> if (target is EntityPlayer) Color(EntityUtils.getCategoryColor(target)) else Color.WHITE
            else -> Color(redValue.get(), greenValue.get(), blueValue.get(), alphaValue.get())
        }
    }

    override fun onDisable() {
        stopLag()
    }

    override val tag: String
        get() = "${packetQueue.size} pkts"
}
