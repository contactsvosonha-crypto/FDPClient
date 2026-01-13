package net.ccbluex.liquidbounce.features.module.modules.combat

import net.ccbluex.liquidbounce.LiquidBounce
import net.ccbluex.liquidbounce.event.*
import net.ccbluex.liquidbounce.features.module.Module
import net.ccbluex.liquidbounce.features.module.ModuleCategory
import net.ccbluex.liquidbounce.features.module.ModuleInfo
import net.ccbluex.liquidbounce.utils.EntityUtils
import net.ccbluex.liquidbounce.utils.render.RenderUtils
import net.ccbluex.liquidbounce.value.*
import net.minecraft.entity.EntityLivingBase
import net.minecraft.network.Packet
import net.minecraft.network.play.client.*
import net.minecraft.util.AxisAlignedBB
import java.awt.Color
import java.util.*
import java.util.concurrent.LinkedBlockingQueue

@ModuleInfo(name = "LagRange", category = ModuleCategory.COMBAT, description = "Backtrack packets.")
class LagRange : Module() {

    private val delayValue = IntegerValue("MaxDelay", 200, 50, 1000)
    private val rangeValue = FloatValue("Range", 10.0f, 3.0f, 30.0f)
    private val renderMode = ListValue("Render", arrayOf("Box", "None"), "Box")

    private val packetQueue: Queue<Packet<*>> = LinkedBlockingQueue()
    private var isLagging = false
    private var timer = System.currentTimeMillis()

    @EventTarget
    fun onUpdate(event: UpdateEvent) {
        val player = mc.thePlayer ?: return
        val target = mc.theWorld.loadedEntityList
            .filterIsInstance<EntityLivingBase>()
            .filter { it != player && EntityUtils.isSelected(it, true) && player.getDistanceToEntity(it) <= rangeValue.get() }
            .minByOrNull { player.getDistanceToEntity(it) }

        if (target != null) {
            isLagging = true
            if (System.currentTimeMillis() - timer > delayValue.get()) {
                releasePackets()
            }
        } else {
            stopLag()
        }
    }

    @EventTarget
    fun onPacket(event: PacketEvent) {
        val packet = event.packet
        if (isLagging && packet is C03PacketPlayer) {
            event.cancelEvent()
            packetQueue.add(packet)
        }
        if (packet is C02PacketUseEntity || packet is C07PacketPlayerDigging) {
            releasePackets()
        }
    }

    private fun releasePackets() {
        while (packetQueue.isNotEmpty()) {
            mc.netHandler.networkManager.sendPacket(packetQueue.poll())
        }
        timer = System.currentTimeMillis()
    }

    private fun stopLag() {
        releasePackets()
        isLagging = false
    }

    @EventTarget
    fun onRender3D(event: Render3DEvent) {
        if (!isLagging || packetQueue.isEmpty() || renderMode.get() == "None") return
        val oldest = packetQueue.peek()
        if (oldest is C03PacketPlayer && oldest.isMoving) {
            val x = oldest.positionX - mc.renderManager.viewerPosX
            val y = oldest.positionY - mc.renderManager.viewerPosY
            val z = oldest.positionZ - mc.renderManager.viewerPosZ
            val w = mc.thePlayer.width / 2.0
            val aabb = AxisAlignedBB(x - w, y, z - w, x + w, y + mc.thePlayer.height, z + w)
            RenderUtils.drawFilledBox(aabb, Color(0, 255, 255, 100).rgb)
        }
    }

    override fun onDisable() {
        stopLag()
    }
}
