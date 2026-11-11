package dev.miningmacro;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.command.CommandSource;
import net.minecraft.entity.ItemEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;

import java.util.List;

import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.argument;
import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.literal;

/** /mm コマンド (クライアント側のみ。サーバーには送られない)。 */
public final class MacroCommands {
    private MacroCommands() {
    }

    private static MacroConfig cfg() {
        return MiningMacroClient.config;
    }

    private static int ok(CommandContext<FabricClientCommandSource> c, String msg) {
        c.getSource().sendFeedback(Text.literal("[採掘マクロ] " + msg).formatted(Formatting.GREEN));
        return 1;
    }

    private static int info(CommandContext<FabricClientCommandSource> c, String msg) {
        c.getSource().sendFeedback(Text.literal(msg));
        return 1;
    }

    private static int err(CommandContext<FabricClientCommandSource> c, String msg) {
        c.getSource().sendError(Text.literal("[採掘マクロ] " + msg));
        return 0;
    }

    public static void register(CommandDispatcher<FabricClientCommandSource> d) {
        d.register(literal("mm")
                .then(literal("start").executes(c -> {
                    String e = MiningMacroClient.controller.start();
                    return e == null ? ok(c, "開始しました") : err(c, e);
                }))
                .then(literal("stop").executes(c -> {
                    MiningMacroClient.controller.stop("コマンド");
                    return 1;
                }))
                .then(literal("status").executes(MacroCommands::status))
                .then(literal("reload").executes(c -> {
                    if (MiningMacroClient.controller.isRunning()) return err(c, "停止してから実行してください");
                    MacroConfig n = MacroConfig.load();
                    MiningMacroClient.config = n;
                    MiningMacroClient.controller = new MacroController(n);
                    return ok(c, "設定を読み込み直しました");
                }))
                .then(literal("pos1").executes(c -> setPos(c, 1, false))
                        .then(argument("x", IntegerArgumentType.integer())
                                .then(argument("y", IntegerArgumentType.integer())
                                        .then(argument("z", IntegerArgumentType.integer())
                                                .executes(c -> setPos(c, 1, true))))))
                .then(literal("pos2").executes(c -> setPos(c, 2, false))
                        .then(argument("x", IntegerArgumentType.integer())
                                .then(argument("y", IntegerArgumentType.integer())
                                        .then(argument("z", IntegerArgumentType.integer())
                                                .executes(c -> setPos(c, 2, true))))))
                .then(literal("ore")
                        .then(literal("list").executes(c -> info(c, "鉱石: " + String.join(", ", cfg().ores))))
                        .then(literal("clear").executes(c -> {
                            cfg().ores.clear();
                            cfg().save();
                            return ok(c, "鉱石リストを空にしました");
                        }))
                        .then(literal("default").executes(c -> {
                            cfg().ores = MacroConfig.defaultOres();
                            cfg().save();
                            return ok(c, "鉱石リストを初期値に戻しました");
                        }))
                        .then(literal("look").executes(c -> {
                            MinecraftClient mc = MinecraftClient.getInstance();
                            if (mc.world == null || !(mc.crosshairTarget instanceof BlockHitResult bh)
                                    || bh.getType() != HitResult.Type.BLOCK) {
                                return err(c, "ブロックを見ていません");
                            }
                            return addOre(c, Registries.BLOCK.getId(mc.world.getBlockState(bh.getBlockPos()).getBlock()).toString());
                        }))
                        .then(literal("add").then(argument("block", StringArgumentType.greedyString())
                                .suggests((c, b) -> CommandSource.suggestIdentifiers(Registries.BLOCK.getIds(), b))
                                .executes(c -> addOre(c, StringArgumentType.getString(c, "block")))))
                        .then(literal("remove").then(argument("block", StringArgumentType.greedyString())
                                .suggests((c, b) -> CommandSource.suggestMatching(cfg().ores, b))
                                .executes(c -> {
                                    String id = normalize(StringArgumentType.getString(c, "block"));
                                    if (!cfg().ores.remove(id)) return err(c, "リストにありません: " + id);
                                    cfg().save();
                                    return ok(c, "鉱石から削除: " + id);
                                }))))
                .then(literal("pickup")
                        .then(literal("list").executes(MacroCommands::listPickup))
                        .then(literal("clear").executes(c -> {
                            cfg().pickupItems.clear();
                            cfg().save();
                            return ok(c, "指定アイテムを空にしました");
                        }))
                        .then(literal("all").then(argument("enabled", BoolArgumentType.bool()).executes(c -> {
                            cfg().pickupAll = BoolArgumentType.getBool(c, "enabled");
                            cfg().save();
                            return ok(c, "落ちているアイテムを全部拾う: " + cfg().pickupAll);
                        })))
                        .then(literal("hand").executes(c -> {
                            ClientPlayerEntity p = MinecraftClient.getInstance().player;
                            if (p == null || p.getMainHandStack().isEmpty()) return err(c, "アイテムを手に持ってください");
                            return addRule(c, MacroConfig.PickupRule.exact(p.getMainHandStack()));
                        }))
                        .then(literal("nearest").executes(c -> {
                            MinecraftClient mc = MinecraftClient.getInstance();
                            if (mc.player == null || mc.world == null) return 0;
                            List<ItemEntity> items = mc.world.getEntitiesByClass(ItemEntity.class,
                                    mc.player.getBoundingBox().expand(12), e -> e.isAlive());
                            ItemEntity best = null;
                            for (ItemEntity e : items) {
                                if (best == null || e.squaredDistanceTo(mc.player) < best.squaredDistanceTo(mc.player)) best = e;
                            }
                            if (best == null) return err(c, "近くに落ちているアイテムがありません");
                            return addRule(c, MacroConfig.PickupRule.exact(best.getStack()));
                        }))
                        .then(literal("name").then(argument("text", StringArgumentType.greedyString()).executes(c -> {
                            MacroConfig.PickupRule r = new MacroConfig.PickupRule();
                            r.name = StringArgumentType.getString(c, "text");
                            return addRule(c, r);
                        })))
                        .then(literal("add").then(argument("item", StringArgumentType.greedyString())
                                .suggests((c, b) -> CommandSource.suggestIdentifiers(Registries.ITEM.getIds(), b))
                                .executes(c -> {
                                    Identifier id = Identifier.tryParse(StringArgumentType.getString(c, "item").trim());
                                    if (id == null || !Registries.ITEM.containsId(id)) return err(c, "そのアイテムIDはありません");
                                    MacroConfig.PickupRule r = new MacroConfig.PickupRule();
                                    r.item = id.toString();
                                    return addRule(c, r);
                                })))
                        .then(literal("remove").then(argument("index", IntegerArgumentType.integer(1)).executes(c -> {
                            int i = IntegerArgumentType.getInteger(c, "index");
                            if (i > cfg().pickupItems.size()) return err(c, "その番号はありません");
                            MacroConfig.PickupRule r = cfg().pickupItems.remove(i - 1);
                            cfg().save();
                            return ok(c, "指定アイテムから削除: " + r);
                        })))));
    }

    private static String normalize(String raw) {
        Identifier id = Identifier.tryParse(raw.trim());
        return id == null ? raw.trim() : id.toString();
    }

    private static int addOre(CommandContext<FabricClientCommandSource> c, String raw) {
        Identifier id = Identifier.tryParse(raw.trim());
        if (id == null || !Registries.BLOCK.containsId(id)) return err(c, "そのブロックIDはありません: " + raw);
        if (cfg().ores.contains(id.toString())) return err(c, "既に登録済みです: " + id);
        cfg().ores.add(id.toString());
        cfg().save();
        return ok(c, "鉱石に追加: " + id + " (実行中の場合は次の /mm start から反映)");
    }

    private static int addRule(CommandContext<FabricClientCommandSource> c, MacroConfig.PickupRule r) {
        cfg().pickupItems.add(r);
        cfg().save();
        return ok(c, "指定アイテムに追加: " + r);
    }

    private static int listPickup(CommandContext<FabricClientCommandSource> c) {
        StringBuilder sb = new StringBuilder("全部拾う: " + cfg().pickupAll + "\n指定アイテム:");
        if (cfg().pickupItems.isEmpty()) sb.append(" (なし)");
        for (int i = 0; i < cfg().pickupItems.size(); i++) {
            sb.append("\n ").append(i + 1).append(". ").append(cfg().pickupItems.get(i));
        }
        return info(c, sb.toString());
    }

    private static int setPos(CommandContext<FabricClientCommandSource> c, int which, boolean args) {
        int[] v;
        if (args) {
            v = new int[]{IntegerArgumentType.getInteger(c, "x"), IntegerArgumentType.getInteger(c, "y"),
                    IntegerArgumentType.getInteger(c, "z")};
        } else {
            BlockPos b = c.getSource().getPlayer().getBlockPos();
            v = new int[]{b.getX(), b.getY(), b.getZ()};
        }
        if (which == 1) cfg().pos1 = v; else cfg().pos2 = v;
        cfg().save();
        return ok(c, "pos" + which + " = " + v[0] + ", " + v[1] + ", " + v[2] + " (y は足元の高さ)");
    }

    private static int status(CommandContext<FabricClientCommandSource> c) {
        MacroConfig g = cfg();
        String hud = MiningMacroClient.controller.hudText();
        ItemStack tool = c.getSource().getPlayer().getMainHandStack();
        return info(c, "状態: " + (hud == null ? "停止中" : hud)
                + "\npos1: " + fmt(g.pos1) + " / pos2: " + fmt(g.pos2) + " / 高さ " + g.height
                + "\n鉱石 " + g.ores.size() + " 種 / 指定アイテム " + g.pickupItems.size() + " 件 / 全部拾う " + g.pickupAll
                + "\n修繕開始 耐久<=" + g.repairThreshold + " / 強制停止 耐久<=" + g.hardStopDurability
                + (MacroController.isPickaxe(tool) ? " / 現在 " + MacroController.remaining(tool) : ""));
    }

    private static String fmt(int[] p) {
        return p == null ? "未設定" : p[0] + "," + p[1] + "," + p[2];
    }
}
