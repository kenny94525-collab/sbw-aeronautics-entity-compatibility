package com.example.swcacompat

import net.neoforged.bus.api.IEventBus
import net.neoforged.fml.common.Mod
import org.apache.logging.log4j.LogManager

@Mod(SWCACompatMod.MOD_ID)
class SWCACompatMod(modEventBus: IEventBus) {

    init {
        // ContraptionVehicleHandler 已用 @EventBusSubscriber 自動註冊，不要重複註冊
        LOGGER.info("[SW×CA Compat] loaded")
    }

    companion object {
        const val MOD_ID = "swcacompat"
        val LOGGER = LogManager.getLogger(MOD_ID)!!
    }
}
