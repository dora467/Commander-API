package dev.miningmacro;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class MiningMacroClient implements ClientModInitializer {
    public static final Logger LOGGER = LoggerFactory.getLogger("miningmacro");

    public static MacroConfig config;
    public static MacroController controller;

    @Override
    public void onInitializeClient() {
        config = MacroConfig.load();
        controller = new MacroController(config);

        KeyBinding toggle = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "採掘マクロ 開始/停止", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_F8, "採掘マクロ"));

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (toggle.wasPressed()) {
                if (controller.isRunning()) {
                    controller.stop("キー操作");
                } else {
                    String err = controller.start();
                    if (client.player != null) {
                        client.player.sendMessage(err == null
                                ? Text.literal("[採掘マクロ] 開始しました").formatted(Formatting.GREEN)
                                : Text.literal("[採掘マクロ] " + err).formatted(Formatting.RED), false);
                    }
                }
            }
            controller.tick();
        });

        HudRenderCallback.EVENT.register((ctx, tickCounter) -> {
            MinecraftClient mc = MinecraftClient.getInstance();
            String text = controller.hudText();
            if (text != null && !mc.options.hudHidden) {
                ctx.drawTextWithShadow(mc.textRenderer, text, 4, 4, 0xFFFFFFFF);
            }
        });

        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> MacroCommands.register(dispatcher));
    }
}
