package com.example.swcacompat

import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity
import dev.ryanhcode.sable.companion.SableCompanion
import dev.ryanhcode.sable.companion.SubLevelAccess
import dev.ryanhcode.sable.companion.math.BoundingBox3d
import dev.ryanhcode.sable.companion.math.Pose3d
import dev.ryanhcode.sable.companion.math.Pose3dc
import net.minecraft.network.FriendlyByteBuf
import net.minecraft.network.codec.StreamCodec
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.entity.Entity
import net.minecraft.world.phys.Vec3
import net.neoforged.api.distmarker.Dist
import net.neoforged.bus.api.SubscribeEvent
import net.neoforged.fml.ModList
import net.neoforged.fml.common.EventBusSubscriber
import net.neoforged.neoforge.event.tick.ServerTickEvent
import net.neoforged.neoforge.network.PacketDistributor
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent
import net.neoforged.neoforge.network.registration.PayloadRegistrar
import net.neoforged.neoforge.server.ServerLifecycleHooks
import java.util.concurrent.ConcurrentHashMap

/**
 * SW 載具 / 砲塔 固定在 Sable sub-level（CA 飛行器）甲板上。
 *
 * 錨點（甲板座標）由伺服器計算後用封包送給客戶端。
 * 這樣客戶端完全不需要「用位置反推」—— 反推必然帶入時間差，
 * 而船速越快誤差越大，且 CA 的船即使停著也在微幅浮動，時機判斷不可靠。
 */

/** 伺服器 → 客戶端：某實體在甲板上的本地座標（含所屬 sub-level 的 UUID） */
class DeckAnchorPayload(
    val entityId: Int,
    val shipId: java.util.UUID,
    val x: Double,
    val y: Double,
    val z: Double
) : CustomPacketPayload {

    companion object {
        val TYPE: CustomPacketPayload.Type<DeckAnchorPayload> =
            CustomPacketPayload.Type(ResourceLocation.fromNamespaceAndPath("swcacompat", "deck_anchor"))

        val CODEC: StreamCodec<FriendlyByteBuf, DeckAnchorPayload> =
            object : StreamCodec<FriendlyByteBuf, DeckAnchorPayload> {
                override fun decode(buf: FriendlyByteBuf): DeckAnchorPayload =
                    DeckAnchorPayload(buf.readVarInt(), buf.readUUID(), buf.readDouble(), buf.readDouble(), buf.readDouble())

                override fun encode(buf: FriendlyByteBuf, value: DeckAnchorPayload) {
                    buf.writeVarInt(value.entityId)
                    buf.writeUUID(value.shipId)
                    buf.writeDouble(value.x)
                    buf.writeDouble(value.y)
                    buf.writeDouble(value.z)
                }
            }
    }

    override fun type(): CustomPacketPayload.Type<DeckAnchorPayload> = TYPE
}

/** 客戶端保存的錨點 */
object ClientAnchors {
    class Anchor(val shipId: java.util.UUID, val local: Vec3)
    val map = ConcurrentHashMap<Int, Anchor>()

    fun put(p: DeckAnchorPayload) {
        map[p.entityId] = Anchor(p.shipId, Vec3(p.x, p.y, p.z))
    }
}

@EventBusSubscriber(modid = SWCACompatMod.MOD_ID, bus = EventBusSubscriber.Bus.MOD)
object NetworkHandler {

    @SubscribeEvent
    fun register(event: RegisterPayloadHandlersEvent) {
        val registrar: PayloadRegistrar = event.registrar("1")
        registrar.playToClient(
            DeckAnchorPayload.TYPE,
            DeckAnchorPayload.CODEC
        ) { payload, context ->
            context.enqueueWork { ClientAnchors.put(payload) }
        }
    }
}

@EventBusSubscriber(modid = SWCACompatMod.MOD_ID)
object ContraptionVehicleHandler {

    internal val sableLoaded: Boolean by lazy { ModList.get().isLoaded("sable") }
    private const val SET_PLOT = "sable\$setPlotPosition"
    private const val GET_PLOT = "sable\$getPlotPosition"
    private val TURRET_BASES = listOf("ArtilleryEntity", "AutoAimableEntity")

    private const val ASSERT_EPS_SQ = 0.0025
    private const val DECK_Y_MARGIN = 10.0
    private const val DECK_XZ_MARGIN = 0.5

    private const val SETTLE_MAX_TICKS = 60
    private const val SETTLE_MIN_TICKS = 6
    private const val SETTLE_SPEED_SQ = 1.0E-4
    private const val SETTLE_REQUIRED = 3

    /** 每 20 tick 重送一次錨點，容忍封包遺失 */
    private const val RESEND_INTERVAL = 20

    internal val setPlotMethod: java.lang.reflect.Method? by lazy {
        try { Entity::class.java.getMethod(SET_PLOT, Vec3::class.java) }
        catch (t: Throwable) { SWCACompatMod.LOGGER.warn("[SW×CA] 找不到 setPlot：{}", t.message); null }
    }
    internal val getPlotMethod: java.lang.reflect.Method? by lazy {
        try { Entity::class.java.getMethod(GET_PLOT) } catch (t: Throwable) { null }
    }

    internal fun isTurret(entity: Entity): Boolean {
        var c: Class<*>? = entity.javaClass
        var guard = 0
        while (c != null && guard++ < 24) {
            val n = c.name
            for (base in TURRET_BASES) if (n.endsWith(base)) return true
            c = c.superclass
        }
        return false
    }

    private class Binding(val ship: SubLevelAccess, var local: Vec3) {
        var driving = false
        var lastCarried: Vec3 = Vec3.ZERO
        var lastSentTick = Int.MIN_VALUE
    }

    private val bindings = HashMap<Int, Binding>()
    private val waited = HashMap<Int, Int>()
    private val settledStreak = HashMap<Int, Int>()
    private var tickCounter = 0

    @SubscribeEvent
    fun onServerTick(event: ServerTickEvent.Post) {
        if (!sableLoaded) return
        val method = setPlotMethod ?: return
        val server = ServerLifecycleHooks.getCurrentServer() ?: return
        tickCounter++

        try {
            val alive = HashSet<Int>()
            server.allLevels.forEach { level ->
                for (e in level.allEntities) if (e is VehicleEntity && e.isAlive) alive.add(e.id)
                processLevel(level, method)
            }
            bindings.keys.retainAll(alive)
            waited.keys.retainAll(alive)
            settledStreak.keys.retainAll(alive)
        } catch (t: Throwable) {
            SWCACompatMod.LOGGER.error("[SW×CA] tick 例外", t)
        }
    }

    private fun sendAnchor(entity: Entity, b: Binding) {
        try {
            val shipId = b.ship.getUniqueId()
            PacketDistributor.sendToPlayersTrackingEntity(
                entity,
                DeckAnchorPayload(entity.id, shipId, b.local.x, b.local.y, b.local.z)
            )
            b.lastSentTick = tickCounter
        } catch (t: Throwable) {
            SWCACompatMod.LOGGER.warn("[SW×CA] 送出錨點失敗：{}", t.message)
        }
    }

    private fun processLevel(level: ServerLevel, method: java.lang.reflect.Method) {
        val companion = SableCompanion.INSTANCE

        for (entity: Entity in level.allEntities) {
            if (entity !is VehicleEntity || !entity.isAlive) continue

            val turret = isTurret(entity)
            val occupied = entity.passengers.isNotEmpty()
            val existing = bindings[entity.id]

            if (existing != null) {
                val ship = existing.ship
                val pose = ship.logicalPose()

                if (!turret && occupied) {
                    if (!existing.driving) {
                        existing.driving = true
                        existing.lastCarried = entity.position()
                        invoke(method, entity, null)
                    }

                    val carriedNow = pose.transformPosition(existing.local)
                    val shipDelta = carriedNow.subtract(existing.lastCarried)
                    var pos = entity.position()
                    if (shipDelta.lengthSqr() > 1.0E-10) {
                        pos = pos.add(shipDelta)
                        move(entity, pos)
                    }

                    if (!onDeck(ship, pos)) {
                        invoke(method, entity, null)
                        bindings.remove(entity.id)
                        SWCACompatMod.LOGGER.info("[SW×CA] 解除 {}（已離開甲板）", entity.id)
                        continue
                    }

                    existing.local = pose.transformPositionInverse(pos)
                    existing.lastCarried = pos
                    entity.fallDistance = 0f
                    sendAnchor(entity, existing)
                    continue
                }

                existing.driving = false

                val target = pose.transformPosition(existing.local)
                if (entity.position().distanceToSqr(target) > ASSERT_EPS_SQ) {
                    move(entity, target)
                }
                if (entity.deltaMovement.lengthSqr() > 1.0E-8) {
                    entity.setDeltaMovement(Vec3.ZERO)
                }
                entity.fallDistance = 0f

                if (getPlotMethod == null || plotIsCleared(entity)) {
                    invoke(method, entity, existing.local)
                }
                if (tickCounter - existing.lastSentTick >= RESEND_INTERVAL) {
                    sendAnchor(entity, existing)
                }
                continue
            }

            if (occupied) continue

            val t = waited.getOrDefault(entity.id, 0)
            waited[entity.id] = t + 1
            val still = entity.onGround() || entity.deltaMovement.lengthSqr() < SETTLE_SPEED_SQ
            val streak = if (still) settledStreak.getOrDefault(entity.id, 0) + 1 else 0
            settledStreak[entity.id] = streak

            val ready = t + 1 >= SETTLE_MIN_TICKS && streak >= SETTLE_REQUIRED
            val timeout = t + 1 >= SETTLE_MAX_TICKS
            if (!ready && !timeout) continue

            val ship = findShip(companion, level, entity) ?: continue
            val local = ship.logicalPose().transformPositionInverse(entity.position())

            val b = Binding(ship, local)
            b.lastCarried = entity.position()
            bindings[entity.id] = b
            waited.remove(entity.id)
            settledStreak.remove(entity.id)
            invoke(method, entity, local)
            sendAnchor(entity, b)

            SWCACompatMod.LOGGER.info(
                "[SW×CA] 登記 {} ({}) turret={} 等待={}tick 落地={} 連續穩定={}",
                entity.id, entity.javaClass.simpleName, turret, t + 1, entity.onGround(), streak
            )
        }
    }

    internal fun move(entity: Entity, pos: Vec3) {
        entity.setPos(pos.x, pos.y, pos.z)
        entity.setOldPosAndRot()
    }

    private fun onDeck(ship: SubLevelAccess, pos: Vec3): Boolean {
        return try {
            val b = ship.boundingBox()
            pos.x >= b.minX() - DECK_XZ_MARGIN && pos.x <= b.maxX() + DECK_XZ_MARGIN &&
            pos.z >= b.minZ() - DECK_XZ_MARGIN && pos.z <= b.maxZ() + DECK_XZ_MARGIN &&
            pos.y >= b.minY() - DECK_Y_MARGIN && pos.y <= b.maxY() + DECK_Y_MARGIN
        } catch (t: Throwable) { true }
    }

    private fun plotIsCleared(entity: Entity): Boolean {
        val m = getPlotMethod ?: return false
        return try { m.invoke(entity) == null } catch (t: Throwable) { false }
    }

    internal fun findShip(
        companion: SableCompanion,
        level: net.minecraft.world.level.Level,
        entity: Entity
    ): SubLevelAccess? {
        val feet = entity.boundingBox.minY
        val x = entity.x
        val z = entity.z
        for (inflate in doubleArrayOf(1.0, 4.0, 8.0)) {
            val probe = BoundingBox3d(
                x - inflate, feet - inflate, z - inflate,
                x + inflate, feet + inflate, z + inflate
            )
            val ships = try { companion.getAllIntersecting(level, probe).toList() }
            catch (t: Throwable) { emptyList<SubLevelAccess>() }
            val hit = ships.firstOrNull { s ->
                val b = s.boundingBox()
                x >= b.minX() - 2 && x <= b.maxX() + 2 &&
                z >= b.minZ() - 2 && z <= b.maxZ() + 2 &&
                b.maxY() >= feet - 8.0 && b.maxY() <= feet + 8.0
            }
            if (hit != null) return hit
        }
        return null
    }

    private fun invoke(method: java.lang.reflect.Method, entity: Entity, local: Vec3?) {
        if (entity.level().isClientSide) return
        try { method.invoke(entity, local) } catch (t: Throwable) {
            SWCACompatMod.LOGGER.warn("[SW×CA] setPlotPosition 失敗：{}", t.message)
        }
    }
}

/**
 * 客戶端：錨點直接使用伺服器送來的甲板座標，不做任何換算。
 * 繪製時用插值姿態（與 Sable 繪製實體位置時相同做法）。
 */
@EventBusSubscriber(modid = SWCACompatMod.MOD_ID, value = [Dist.CLIENT])
object ClientDeckPinHandler {

    private class Pin(val ship: SubLevelAccess, var local: Vec3)

    private val pins = HashMap<Int, Pin>()
    private var loggedReceive = false
    private var loggedPin = false

    private const val GIVE_UP_SQ = 400.0

    private val renderPoseTMethod: java.lang.reflect.Method? by lazy {
        try {
            val cls = Class.forName("dev.ryanhcode.sable.sublevel.ClientSubLevel")
            cls.methods.firstOrNull { it.name == "renderPose" && it.parameterCount == 1 }
        } catch (t: Throwable) { null }
    }

    private val renderPoseMethod: java.lang.reflect.Method? by lazy {
        try {
            val cls = Class.forName("dev.ryanhcode.sable.sublevel.ClientSubLevel")
            cls.methods.firstOrNull { it.name == "renderPose" && it.parameterCount == 0 }
        } catch (t: Throwable) { null }
    }

    private fun partialTicks(mc: Any): Float {
        return try {
            val timer = mc.javaClass.getMethod("getTimer").invoke(mc) ?: return 1.0f
            val m = timer.javaClass.methods.firstOrNull {
                it.name == "getGameTimeDeltaPartialTick" && it.parameterCount == 1
            } ?: return 1.0f
            (m.invoke(timer, true) as? Float) ?: 1.0f
        } catch (t: Throwable) { 1.0f }
    }

    private fun renderPose(ship: SubLevelAccess, partial: Float): Pose3dc {
        renderPoseTMethod?.let { m ->
            try {
                val v = m.invoke(ship, partial)
                if (v is Pose3dc) return v
            } catch (t: Throwable) { }
        }
        renderPoseMethod?.let { m ->
            try {
                val v = m.invoke(ship)
                if (v is Pose3dc) return v
            } catch (t: Throwable) { }
        }
        return try {
            ship.lastPose().lerp(ship.logicalPose(), partial.toDouble(), Pose3d())
        } catch (t: Throwable) {
            ship.logicalPose()
        }
    }

    @SubscribeEvent
    fun onRenderFrame(event: net.neoforged.neoforge.client.event.RenderFrameEvent.Pre) {
        if (!ContraptionVehicleHandler.sableLoaded) return

        val mc = net.minecraft.client.Minecraft.getInstance()
        val level = mc.level ?: return
        val companion = SableCompanion.INSTANCE
        val partial = partialTicks(mc)

        val seen = HashSet<Int>()
        for (entity: Entity in level.entitiesForRendering()) {
            if (entity !is VehicleEntity || !entity.isAlive) continue

            val turret = ContraptionVehicleHandler.isTurret(entity)
            val driven = entity.getFirstPassenger() != null

            // 被駕駛的有輪子載具 → 完全不插手
            if (!turret && driven) {
                pins.remove(entity.id)
                continue
            }

            // 錨點一律以伺服器送來的為準
            val anchor = ClientAnchors.map[entity.id]
            if (anchor != null) {
                val ship = ContraptionVehicleHandler.findShip(companion, level, entity)
                if (ship != null) {
                    val existing = pins[entity.id]
                    if (existing == null || existing.ship !== ship) {
                        pins[entity.id] = Pin(ship, anchor.local)
                    } else {
                        existing.local = anchor.local
                    }
                    if (!loggedReceive) {
                        loggedReceive = true
                        SWCACompatMod.LOGGER.info(
                            "[SW×CA] 收到錨點 id={} local=({}, {}, {})",
                            entity.id, anchor.local.x, anchor.local.y, anchor.local.z
                        )
                    }
                }
            }

            val pin = pins[entity.id] ?: continue
            seen.add(entity.id)

            val target = renderPose(pin.ship, partial).transformPosition(pin.local)

            if (entity.position().distanceToSqr(target) > GIVE_UP_SQ) {
                pins.remove(entity.id)
                continue
            }

            if (!loggedPin) {
                loggedPin = true
                SWCACompatMod.LOGGER.info("[SW×CA] 客戶端釘住 id={} turret={}", entity.id, turret)
            }

            pinWorld(entity, target)
        }

        pins.keys.retainAll(seen)
    }

    private fun pinWorld(entity: Entity, pos: Vec3) {
        entity.setPos(pos.x, pos.y, pos.z)
        entity.setOldPosAndRot()
        entity.xo = pos.x
        entity.yo = pos.y
        entity.zo = pos.z
    }
}
