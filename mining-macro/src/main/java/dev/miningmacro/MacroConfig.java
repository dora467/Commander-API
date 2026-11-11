package dev.miningmacro;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.CustomModelDataComponent;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** config/miningmacro.json に保存される設定。 */
public class MacroConfig {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    /** 採掘範囲の2点 {x, y, z}。y は「足元のブロック」の高さ (低い方が使われる)。 */
    public int[] pos1;
    public int[] pos2;
    /** 掘る高さ (足元から何マスか)。 */
    public int height = 2;

    /** 鉱脈として回収するブロックID。 */
    public List<String> ores = defaultOres();
    /** 天井より上、何マスまで鉱脈を追うか。床下は穴になるので掘らない。 */
    public int oreUp = 3;

    /** true: 範囲内に落ちているアイテムは全部拾いに行く。 */
    public boolean pickupAll = true;
    /** 必ず拾うアイテム (拾えないと緊急停止する)。 */
    public List<PickupRule> pickupItems = new ArrayList<>();
    /** 指定アイテムをこの tick 数以内に拾えなければ緊急停止。 */
    public int itemTimeoutTicks = 400;

    /** 残り耐久がこの値以下になったら修繕ルーチンに入る。 */
    public int repairThreshold = 100;
    /** 残り耐久がこの値以下なら何があっても掘らずに緊急停止。 */
    public int hardStopDurability = 30;
    /** 経験値瓶メニューを開くコマンド (先頭の / は不要)。 */
    public String bottleCommand = "mib";
    /** メニュー内でクリックするボタンのアイテムID。 */
    public String menuButtonItem = "minecraft:dispenser";
    /** 取り出す瓶のスタック数 (64 x この数)。 */
    public int bottleStacks = 2;

    /** 視点移動の速さ倍率。小さいほどゆっくり。 */
    public double rotationSpeed = 1.0;
    /** ブロックを壊した後の待ち時間 (tick) の上限。0〜この値でランダム。 */
    public int maxActionDelay = 4;

    public static class PickupRule {
        /** アイテムID。null なら ID を問わない。 */
        public String item;
        /** 表示名に含まれる文字列。null なら名前を問わない。 */
        public String name;
        /** CustomModelData (最初の数値)。null なら問わない。 */
        public Integer customModelData;
        /** item_model コンポーネント (独自の見た目のID)。null なら問わない。 */
        public String itemModel;

        public boolean matches(ItemStack stack) {
            if (stack.isEmpty()) return false;
            if (item != null && !item.equals(Registries.ITEM.getId(stack.getItem()).toString())) return false;
            if (name != null && !stack.getName().getString().contains(name)) return false;
            if (customModelData != null) {
                Integer v = modelData(stack);
                if (v == null || !v.equals(customModelData)) return false;
            }
            if (itemModel != null && !itemModel.equals(model(stack))) return false;
            return item != null || name != null || customModelData != null || itemModel != null;
        }

        public static PickupRule exact(ItemStack stack) {
            PickupRule r = new PickupRule();
            r.item = Registries.ITEM.getId(stack.getItem()).toString();
            r.customModelData = modelData(stack);
            // バニラと違う見た目が設定されている場合だけ記録する
            String m = model(stack);
            if (m != null && !m.equals(r.item)) r.itemModel = m;
            if (stack.get(DataComponentTypes.CUSTOM_NAME) != null) r.name = stack.getName().getString();
            return r;
        }

        private static Integer modelData(ItemStack stack) {
            CustomModelDataComponent cmd = stack.get(DataComponentTypes.CUSTOM_MODEL_DATA);
            if (cmd == null || cmd.floats().isEmpty()) return null;
            return Math.round(cmd.floats().get(0));
        }

        private static String model(ItemStack stack) {
            Identifier id = stack.get(DataComponentTypes.ITEM_MODEL);
            return id == null ? null : id.toString();
        }

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder(item == null ? "(ID不問)" : item);
            if (name != null) sb.append(" 名前~\"").append(name).append('"');
            if (customModelData != null) sb.append(" CMD=").append(customModelData);
            if (itemModel != null) sb.append(" model=").append(itemModel);
            return sb.toString();
        }
    }

    public boolean isPriority(ItemStack stack) {
        for (PickupRule r : pickupItems) {
            if (r.matches(stack)) return true;
        }
        return false;
    }

    public static List<String> defaultOres() {
        List<String> list = new ArrayList<>();
        for (String n : new String[]{"coal", "iron", "copper", "gold", "redstone", "lapis", "diamond", "emerald"}) {
            list.add("minecraft:" + n + "_ore");
            list.add("minecraft:deepslate_" + n + "_ore");
        }
        list.add("minecraft:nether_quartz_ore");
        list.add("minecraft:nether_gold_ore");
        list.add("minecraft:ancient_debris");
        return list;
    }

    private static Path path() {
        return FabricLoader.getInstance().getConfigDir().resolve("miningmacro.json");
    }

    public static MacroConfig load() {
        Path p = path();
        if (Files.exists(p)) {
            try {
                MacroConfig c = GSON.fromJson(Files.readString(p, StandardCharsets.UTF_8), MacroConfig.class);
                if (c != null) {
                    if (c.ores == null) c.ores = defaultOres();
                    if (c.pickupItems == null) c.pickupItems = new ArrayList<>();
                    if (c.height < 2) c.height = 2;
                    return c;
                }
            } catch (Exception e) {
                MiningMacroClient.LOGGER.error("miningmacro.json の読み込みに失敗", e);
            }
        }
        MacroConfig c = new MacroConfig();
        c.save();
        return c;
    }

    public void save() {
        try {
            Files.writeString(path(), GSON.toJson(this), StandardCharsets.UTF_8);
        } catch (IOException e) {
            MiningMacroClient.LOGGER.error("miningmacro.json の保存に失敗", e);
        }
    }
}
