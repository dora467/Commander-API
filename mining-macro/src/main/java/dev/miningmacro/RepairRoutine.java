package dev.miningmacro;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.network.packet.c2s.play.PlayerActionC2SPacket;
import net.minecraft.registry.Registries;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.Slot;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

import java.util.Random;

/**
 * 経験値瓶による修繕。
 * コマンド → メニューのボタンをクリック → 瓶を shift+右クリックで取り出す → 画面を閉じる
 * → ツルハシをオフハンドへ → 瓶を投げ切る → 修繕されたか確認 → ツルハシをメインハンドへ戻す。
 * どこかで想定外のことが起きたら FAILED を返し、呼び出し側が緊急停止する。
 */
public class RepairRoutine {
    public enum Result { RUNNING, DONE, FAILED }

    private enum Step {
        PRE_DELAY, SEND_COMMAND, WAIT_MENU, CLICK_BUTTON, WAIT_BOTTLE, TAKE, CLOSE,
        SWAP_OFFHAND, WAIT_OFFHAND, SELECT_BOTTLE, THROW, WAIT_ORBS, SELECT_PICK, SWAP_BACK, WAIT_BACK
    }

    private final MinecraftClient mc = MinecraftClient.getInstance();
    private final MacroController ctl;
    private final Random rng = new Random();

    private Step step = Step.PRE_DELAY;
    private int wait, timeout, clicks, pickSlot, startRemaining, thrown;
    private float throwPitch, throwYaw;
    private String failReason = "";

    RepairRoutine(MacroController ctl) {
        this.ctl = ctl;
    }

    String failReason() {
        return failReason;
    }

    String stepName() {
        return switch (step) {
            case PRE_DELAY, SEND_COMMAND, WAIT_MENU -> "メニューを開く";
            case CLICK_BUTTON, WAIT_BOTTLE, TAKE, CLOSE -> "瓶を取り出す";
            case SWAP_OFFHAND, WAIT_OFFHAND, SELECT_BOTTLE -> "持ち替え";
            case THROW -> "瓶を使用 (" + thrown + ")";
            case WAIT_ORBS -> "経験値を回収";
            default -> "ツルハシを戻す";
        };
    }

    void begin(ClientPlayerEntity p) {
        pickSlot = p.getInventory().selectedSlot;
        startRemaining = MacroController.remaining(p.getMainHandStack());
        clicks = 0;
        thrown = 0;
        failReason = "";
        throwPitch = 68f + rng.nextFloat() * 14f;
        throwYaw = p.getYaw() + (rng.nextFloat() - 0.5f) * 30f;
        step = Step.PRE_DELAY;
        wait = 8 + rng.nextInt(12);
        ctl.say(net.minecraft.util.Formatting.AQUA, "耐久 " + startRemaining + " → 修繕を開始します");
    }

    private Result fail(String reason) {
        failReason = reason;
        return Result.FAILED;
    }

    private int pause(int min, int span) {
        return min + rng.nextInt(span + 1);
    }

    private static int countBottles(ClientPlayerEntity p) {
        PlayerInventory inv = p.getInventory();
        int n = 0;
        for (int i = 0; i < inv.size(); i++) {
            ItemStack s = inv.getStack(i);
            if (s.isOf(Items.EXPERIENCE_BOTTLE)) n += s.getCount();
        }
        return n;
    }

    /** 開いているメニュー側 (自分のインベントリ以外) から、指定アイテムのスロットを探す。 */
    private static Slot findMenuSlot(ClientPlayerEntity p, Item item) {
        ScreenHandler h = p.currentScreenHandler;
        if (h == null || h == p.playerScreenHandler) return null;
        for (Slot s : h.slots) {
            if (s.inventory instanceof PlayerInventory) continue;
            if (s.getStack().isOf(item)) return s;
        }
        return null;
    }

    private boolean menuOpen(ClientPlayerEntity p) {
        return mc.currentScreen instanceof HandledScreen<?> && p.currentScreenHandler != p.playerScreenHandler;
    }

    private void swapHands(ClientPlayerEntity p) {
        p.networkHandler.sendPacket(new PlayerActionC2SPacket(
                PlayerActionC2SPacket.Action.SWAP_ITEM_WITH_OFFHAND, BlockPos.ORIGIN, Direction.DOWN));
    }

    Result tick(ClientPlayerEntity p) {
        ctl.halt();
        if (wait > 0) {
            wait--;
            if (step == Step.THROW || step == Step.SELECT_BOTTLE) {
                ctl.aim.step(p, throwYaw, throwPitch, ctl.cfg.rotationSpeed);
            }
            return Result.RUNNING;
        }
        int need = 64 * Math.max(1, ctl.cfg.bottleStacks);
        PlayerInventory inv = p.getInventory();

        switch (step) {
            case PRE_DELAY -> {
                // 瓶が既に十分あるなら取り出しは省略
                step = countBottles(p) >= need ? Step.SWAP_OFFHAND : Step.SEND_COMMAND;
            }
            case SEND_COMMAND -> {
                if (mc.currentScreen != null) mc.setScreen(null);
                String cmd = ctl.cfg.bottleCommand.startsWith("/") ? ctl.cfg.bottleCommand.substring(1) : ctl.cfg.bottleCommand;
                p.networkHandler.sendChatCommand(cmd);
                timeout = 120;
                step = Step.WAIT_MENU;
            }
            case WAIT_MENU -> {
                if (menuOpen(p)) {
                    if (findMenuSlot(p, Items.EXPERIENCE_BOTTLE) != null) {
                        step = Step.TAKE;
                        wait = pause(8, 8);
                    } else if (findMenuSlot(p, buttonItem()) != null) {
                        step = Step.CLICK_BUTTON;
                        wait = pause(8, 8);
                    }
                }
                if (step == Step.WAIT_MENU && --timeout <= 0) return fail("/" + ctl.cfg.bottleCommand + " のメニューが開きません");
            }
            case CLICK_BUTTON -> {
                Slot s = menuOpen(p) ? findMenuSlot(p, buttonItem()) : null;
                if (s == null) return fail("メニューにボタン (" + ctl.cfg.menuButtonItem + ") がありません");
                mc.interactionManager.clickSlot(p.currentScreenHandler.syncId, s.id, 0, SlotActionType.PICKUP, p);
                timeout = 120;
                step = Step.WAIT_BOTTLE;
            }
            case WAIT_BOTTLE -> {
                if (menuOpen(p) && findMenuSlot(p, Items.EXPERIENCE_BOTTLE) != null) {
                    step = Step.TAKE;
                    wait = pause(7, 8);
                } else if (--timeout <= 0) {
                    return fail("経験値瓶が表示されません");
                }
            }
            case TAKE -> {
                int have = countBottles(p);
                if (have >= need || clicks >= Math.max(1, ctl.cfg.bottleStacks)) {
                    if (have == 0) return fail("経験値瓶を取り出せませんでした");
                    step = Step.CLOSE;
                    wait = pause(5, 6);
                } else {
                    Slot s = menuOpen(p) ? findMenuSlot(p, Items.EXPERIENCE_BOTTLE) : null;
                    if (s == null) {
                        if (have == 0) return fail("経験値瓶のスロットが見つかりません");
                        step = Step.CLOSE;
                    } else {
                        // shift + 右クリック
                        mc.interactionManager.clickSlot(p.currentScreenHandler.syncId, s.id, 1, SlotActionType.QUICK_MOVE, p);
                        clicks++;
                        wait = pause(10, 8);
                    }
                }
            }
            case CLOSE -> {
                if (mc.currentScreen != null) p.closeHandledScreen();
                step = Step.SWAP_OFFHAND;
                wait = pause(6, 8);
            }
            case SWAP_OFFHAND -> {
                if (mc.currentScreen != null) p.closeHandledScreen();
                inv.selectedSlot = pickSlot;
                if (!MacroController.isPickaxe(p.getMainHandStack())) return fail("ツルハシが手元にありません");
                swapHands(p);
                timeout = 60;
                step = Step.WAIT_OFFHAND;
            }
            case WAIT_OFFHAND -> {
                if (MacroController.isPickaxe(p.getOffHandStack())) {
                    step = Step.SELECT_BOTTLE;
                    wait = pause(4, 6);
                } else if (--timeout <= 0) {
                    return fail("ツルハシをオフハンドに持ち替えられません");
                }
            }
            case SELECT_BOTTLE -> {
                if (!MacroController.isPickaxe(p.getOffHandStack())) return fail("オフハンドのツルハシが無くなりました");
                int hot = -1, main = -1;
                for (int i = 0; i < 36; i++) {
                    if (!inv.getStack(i).isOf(Items.EXPERIENCE_BOTTLE)) continue;
                    if (i < 9) { if (hot < 0) hot = i; } else if (main < 0) main = i;
                }
                if (hot >= 0) {
                    inv.selectedSlot = hot;
                    step = Step.THROW;
                    wait = pause(4, 5);
                } else if (main >= 0) {
                    // インベントリ内の瓶をホットバーへ (数字キーでの入れ替えと同じ操作)
                    int dest = -1;
                    for (int i = 0; i < 9; i++) {
                        if (i != pickSlot && inv.getStack(i).isEmpty()) { dest = i; break; }
                    }
                    if (dest < 0) dest = pickSlot == 8 ? 7 : 8;
                    mc.interactionManager.clickSlot(p.playerScreenHandler.syncId, main, dest, SlotActionType.SWAP, p);
                    wait = pause(6, 6);
                } else {
                    step = Step.WAIT_ORBS;
                    wait = pause(50, 20);
                }
            }
            case THROW -> {
                if (!MacroController.isPickaxe(p.getOffHandStack())) return fail("オフハンドのツルハシが無くなりました");
                if (!p.getMainHandStack().isOf(Items.EXPERIENCE_BOTTLE)) {
                    step = Step.SELECT_BOTTLE;
                    wait = pause(3, 4);
                    break;
                }
                // 足元に向けて投げる (経験値がすぐ自分に入るように)
                double err = ctl.aim.step(p, throwYaw, throwPitch, ctl.cfg.rotationSpeed);
                if (err > 6.0) break;
                ActionResult r = mc.interactionManager.interactItem(p, Hand.MAIN_HAND);
                if (r.isAccepted()) {
                    if (r.shouldSwingHand()) p.swingHand(Hand.MAIN_HAND);
                    thrown++;
                }
                wait = 4 + rng.nextInt(3);
                if (rng.nextInt(25) == 0) {
                    wait += 5 + rng.nextInt(10);
                    throwPitch = 68f + rng.nextFloat() * 14f;
                    throwYaw += (rng.nextFloat() - 0.5f) * 12f;
                }
            }
            case WAIT_ORBS -> {
                step = Step.SELECT_PICK;
            }
            case SELECT_PICK -> {
                inv.selectedSlot = pickSlot;
                step = Step.SWAP_BACK;
                wait = pause(4, 4); // スロット変更がサーバーへ届くのを待つ
            }
            case SWAP_BACK -> {
                swapHands(p);
                timeout = 60;
                step = Step.WAIT_BACK;
            }
            case WAIT_BACK -> {
                if (MacroController.isPickaxe(p.getMainHandStack())) {
                    int now = MacroController.remaining(p.getMainHandStack());
                    if (now <= startRemaining || now <= ctl.cfg.repairThreshold) {
                        return fail("ツルハシが修繕されていません (耐久 " + startRemaining + " → " + now + ")");
                    }
                    return Result.DONE;
                } else if (--timeout <= 0) {
                    return fail("ツルハシをメインハンドに戻せません");
                }
            }
        }
        return Result.RUNNING;
    }

    private Item buttonItem() {
        Identifier id = Identifier.tryParse(ctl.cfg.menuButtonItem);
        return id == null ? Items.DISPENSER : Registries.ITEM.getOrEmpty(id).orElse(Items.DISPENSER);
    }
}
