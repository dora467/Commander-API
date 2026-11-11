package dev.miningmacro;

import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.FallingBlock;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.option.GameOptions;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.ItemEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.registry.tag.ItemTags;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Hand;
import net.minecraft.util.Identifier;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Random;
import java.util.Set;

/**
 * マクロ本体。毎 tick 呼ばれ、優先度順に
 * 安全確認 → 修繕 → 掘りかけのブロック → 届く範囲の鉱石 → アイテム回収 → 範囲の採掘 を行う。
 */
public class MacroController {
    public enum State { IDLE, RUNNING, REPAIRING }

    private enum Col { WALK, MINE, BLOCKED }

    private static final int[][] DIRS = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};

    private final MinecraftClient mc = MinecraftClient.getInstance();
    public final MacroConfig cfg;
    private final Random rng = new Random();
    final Aim aim = new Aim();
    private final RepairRoutine repair = new RepairRoutine(this);

    private State state = State.IDLE;
    private String status = "停止中";

    // 採掘範囲
    private int minX, maxX, minZ, maxZ, feetY, height;
    private final List<int[]> order = new ArrayList<>();
    private final Set<Block> oreBlocks = new HashSet<>();

    // ワールド判定のキャッシュ (tick ごとに破棄)
    private final Map<Long, Col> colCache = new HashMap<>();
    private final Set<BlockPos> badBlocks = new HashSet<>();
    private final Set<Long> unreachable = new HashSet<>();

    // 採掘中のブロック
    private BlockPos breakTarget;
    private Vec3d aimPoint;
    private int breakTicks, missTicks;
    private boolean breaking;

    // 経路
    private List<Long> path;
    private int pathAge;
    private double offX, offZ;

    // アイテム回収
    private ItemEntity itemTarget;
    private boolean itemPriority;
    private double standX, standZ;
    private long standCol;
    private int itemWait;
    private final Set<Integer> itemBlacklist = new HashSet<>();
    private final Map<Integer, Integer> prioritySeen = new HashMap<>();

    private int tickCount, delay, finishTicks;
    private float lastHealth;
    private float walkPitch = 20f;
    private Vec3d stuckPos;
    private int walkTicks, stuckCount;
    private int minedCols, totalCols, skippedCols;

    public MacroController(MacroConfig cfg) {
        this.cfg = cfg;
    }

    public State state() {
        return state;
    }

    public boolean isRunning() {
        return state != State.IDLE;
    }

    public String hudText() {
        if (state == State.IDLE) return null;
        ClientPlayerEntity p = mc.player;
        String dur = "";
        if (p != null) {
            ItemStack t = isPickaxe(p.getMainHandStack()) ? p.getMainHandStack() : p.getOffHandStack();
            if (isPickaxe(t)) dur = " | 耐久 " + remaining(t);
        }
        return "[採掘マクロ] " + status + " | " + minedCols + "/" + totalCols
                + (skippedCols > 0 ? " (回避 " + skippedCols + ")" : "") + dur;
    }

    // ------------------------------------------------------------------ 開始・停止

    /** 開始できなければ理由を返す。成功なら null。 */
    public String start() {
        ClientPlayerEntity p = mc.player;
        if (p == null || mc.world == null) return "ワールドに入っていません";
        if (cfg.pos1 == null || cfg.pos2 == null) return "範囲が未設定です (/mm pos1, /mm pos2)";
        if (!isPickaxe(p.getMainHandStack())) return "メインハンドにツルハシを持ってください";
        if (remaining(p.getMainHandStack()) <= cfg.hardStopDurability) return "ツルハシの耐久が少なすぎます";

        minX = Math.min(cfg.pos1[0], cfg.pos2[0]);
        maxX = Math.max(cfg.pos1[0], cfg.pos2[0]);
        minZ = Math.min(cfg.pos1[2], cfg.pos2[2]);
        maxZ = Math.max(cfg.pos1[2], cfg.pos2[2]);
        feetY = Math.min(cfg.pos1[1], cfg.pos2[1]);
        height = Math.max(2, cfg.height);

        if (Math.abs(p.getY() - feetY) > 0.6) return "範囲と同じ高さ (足元 y=" + feetY + ") に立ってください";
        int px = MathHelper.floor(p.getX()), pz = MathHelper.floor(p.getZ());
        if (px < minX - 1 || px > maxX + 1 || pz < minZ - 1 || pz > maxZ + 1) {
            return "範囲の中か、範囲に隣接するマスに立ってください";
        }

        oreBlocks.clear();
        for (String id : cfg.ores) {
            Identifier ident = Identifier.tryParse(id);
            if (ident != null) Registries.BLOCK.getOrEmpty(ident).ifPresent(oreBlocks::add);
        }

        buildOrder(px, pz);
        badBlocks.clear();
        unreachable.clear();
        itemBlacklist.clear();
        prioritySeen.clear();
        clearTasks();
        tickCount = 0;
        finishTicks = 0;
        delay = 10 + rng.nextInt(10);
        stuckCount = 0;
        skippedCols = 0;
        minedCols = 0;
        totalCols = order.size();
        lastHealth = p.getHealth();
        state = State.RUNNING;
        status = "開始";
        return null;
    }

    /** プレイヤーに一番近い角から、長辺方向に往復する順番を作る。 */
    private void buildOrder(int px, int pz) {
        order.clear();
        boolean alongX = (maxX - minX) >= (maxZ - minZ);
        boolean xFromMin = Math.abs(px - minX) <= Math.abs(px - maxX);
        boolean zFromMin = Math.abs(pz - minZ) <= Math.abs(pz - maxZ);
        int lenA = alongX ? maxX - minX + 1 : maxZ - minZ + 1;
        int lenB = alongX ? maxZ - minZ + 1 : maxX - minX + 1;
        for (int b = 0; b < lenB; b++) {
            for (int a = 0; a < lenA; a++) {
                int aa = (b % 2 == 0) ? a : lenA - 1 - a;
                int x, z;
                if (alongX) {
                    x = xFromMin ? minX + aa : maxX - aa;
                    z = zFromMin ? minZ + b : maxZ - b;
                } else {
                    z = zFromMin ? minZ + aa : maxZ - aa;
                    x = xFromMin ? minX + b : maxX - b;
                }
                order.add(new int[]{x, z});
            }
        }
    }

    private void clearTasks() {
        breakTarget = null;
        aimPoint = null;
        breaking = false;
        path = null;
        itemTarget = null;
        walkTicks = 0;
        stuckPos = null;
    }

    public void stop(String reason) {
        if (state == State.IDLE) return;
        halt();
        clearTasks();
        state = State.IDLE;
        status = "停止中";
        say(Formatting.YELLOW, "停止: " + reason);
    }

    /** 緊急停止。全キーを離し、音とチャットで知らせる。 */
    public void emergencyStop(String reason) {
        if (state == State.IDLE) return;
        halt();
        clearTasks();
        state = State.IDLE;
        status = "停止中";
        say(Formatting.RED, "緊急停止: " + reason);
        ClientPlayerEntity p = mc.player;
        if (p != null) {
            p.playSound(SoundEvents.ENTITY_ARROW_HIT_PLAYER, 1f, 0.6f);
            p.sendMessage(Text.literal("緊急停止: " + reason).formatted(Formatting.RED), true);
        }
    }

    void say(Formatting color, String msg) {
        if (mc.player != null) {
            mc.player.sendMessage(Text.literal("[採掘マクロ] " + msg).formatted(color), false);
        }
    }

    /** 移動キーを離し、掘るのをやめる。 */
    void halt() {
        GameOptions o = mc.options;
        o.forwardKey.setPressed(false);
        o.backKey.setPressed(false);
        o.leftKey.setPressed(false);
        o.rightKey.setPressed(false);
        o.jumpKey.setPressed(false);
        o.sneakKey.setPressed(false);
        o.sprintKey.setPressed(false);
        if (breaking && mc.interactionManager != null) {
            mc.interactionManager.cancelBlockBreaking();
        }
        breaking = false;
    }

    // ------------------------------------------------------------------ tick

    public void tick() {
        if (state == State.IDLE) return;
        ClientPlayerEntity p = mc.player;
        ClientWorld w = mc.world;
        if (p == null || w == null || mc.interactionManager == null) {
            state = State.IDLE;
            return;
        }
        tickCount++;
        colCache.clear();

        // --- 安全確認 (どの状態でも最優先)
        if (!p.isAlive()) { emergencyStop("死亡しました"); return; }
        if (p.isTouchingWater() || p.isInLava()) { emergencyStop("水または溶岩に触れました"); return; }
        if (Math.abs(p.getY() - feetY) > 0.6) { emergencyStop("高さが変わりました (落下の可能性)"); return; }
        if (p.getHealth() < lastHealth - 0.01f) { emergencyStop("ダメージを受けました"); return; }
        lastHealth = p.getHealth();

        if (state == State.REPAIRING) {
            status = "修繕中: " + repair.stepName();
            RepairRoutine.Result r = repair.tick(p);
            if (r == RepairRoutine.Result.FAILED) {
                emergencyStop("修繕失敗: " + repair.failReason());
            } else if (r == RepairRoutine.Result.DONE) {
                say(Formatting.GREEN, "修繕完了 (残り耐久 " + remaining(p.getMainHandStack()) + ")");
                state = State.RUNNING;
                delay = 10 + rng.nextInt(15);
            }
            return;
        }

        ItemStack tool = p.getMainHandStack();
        if (!isPickaxe(tool)) { emergencyStop("メインハンドがツルハシではありません"); return; }
        int rem = remaining(tool);
        if (rem <= cfg.hardStopDurability) { emergencyStop("ツルハシの耐久が危険域です (" + rem + ")"); return; }
        if (rem <= cfg.repairThreshold) {
            halt();
            clearTasks();
            state = State.REPAIRING;
            repair.begin(p);
            return;
        }

        if (mc.currentScreen != null) {
            halt();
            status = "一時停止 (画面を開いています)";
            return;
        }
        if (delay > 0) {
            delay--;
            halt();
            return;
        }

        if (breakTarget != null) {
            doBreak(p, w);
            return;
        }
        if (tickCount % 3 == 0) {
            BlockPos ore = findOre(p, w);
            if (ore != null) {
                status = "鉱石を採掘";
                setBreak(ore);
                return;
            }
        }
        if (handleItems(p, w)) return;
        mineArea(p, w);
    }

    // ------------------------------------------------------------------ 道具

    static boolean isPickaxe(ItemStack s) {
        return !s.isEmpty() && s.isIn(ItemTags.PICKAXES);
    }

    static int remaining(ItemStack s) {
        return s.getMaxDamage() - s.getDamage();
    }

    // ------------------------------------------------------------------ ワールドの判定

    private static long key(int x, int z) {
        return ((long) x << 32) | (z & 0xffffffffL);
    }

    private static int kx(long k) {
        return (int) (k >> 32);
    }

    private static int kz(long k) {
        return (int) k;
    }

    private boolean inArea(int x, int z) {
        return x >= minX && x <= maxX && z >= minZ && z <= maxZ;
    }

    private boolean inBounds(int x, int z) {
        return x >= minX - 1 && x <= maxX + 1 && z >= minZ - 1 && z <= maxZ + 1;
    }

    private static boolean hasFluid(ClientWorld w, BlockPos pos) {
        return !w.getBlockState(pos).getFluidState().isEmpty();
    }

    private static boolean harmful(BlockState s) {
        return s.isOf(Blocks.FIRE) || s.isOf(Blocks.SOUL_FIRE) || s.isOf(Blocks.COBWEB)
                || s.isOf(Blocks.POWDER_SNOW) || s.isOf(Blocks.SWEET_BERRY_BUSH) || s.isOf(Blocks.WITHER_ROSE);
    }

    private static boolean badFloor(BlockState s) {
        return s.isOf(Blocks.MAGMA_BLOCK) || s.isOf(Blocks.CAMPFIRE) || s.isOf(Blocks.SOUL_CAMPFIRE);
    }

    /**
     * そのブロックを壊しても安全か。
     * 上下左右前後のどこかに水・溶岩があれば、壊した瞬間に流れ込むので不可。
     * 上に砂利などが積まれている場合は、その山のさらに上まで確認する。
     */
    private boolean breakSafe(ClientWorld w, BlockPos pos) {
        if (badBlocks.contains(pos)) return false;
        BlockState s = w.getBlockState(pos);
        if (s.isAir() || !s.getFluidState().isEmpty() || s.hasBlockEntity()) return false;
        if (s.getHardness(w, pos) < 0) return false;
        for (Direction d : Direction.values()) {
            BlockPos n = pos.offset(d);
            if (d == Direction.UP) {
                int k = 0;
                while (k++ < 16 && w.getBlockState(n).getBlock() instanceof FallingBlock) n = n.up();
            }
            if (hasFluid(w, n)) return false;
        }
        return true;
    }

    /** 列 (x,z) の状態。WALK=歩ける / MINE=安全に掘れる / BLOCKED=入らない・触らない。 */
    private Col classify(ClientWorld w, int x, int z) {
        long k = key(x, z);
        Col c = colCache.get(k);
        if (c == null) {
            c = classifyNow(w, x, z);
            colCache.put(k, c);
        }
        return c;
    }

    private Col classifyNow(ClientWorld w, int x, int z) {
        BlockPos floor = new BlockPos(x, feetY - 1, z);
        BlockState fs = w.getBlockState(floor);
        // 床が無い (穴)・床が液体・床が危険 → 入らない
        if (!fs.isSideSolidFullSquare(w, floor, Direction.UP) || !fs.getFluidState().isEmpty() || badFloor(fs)) {
            return Col.BLOCKED;
        }
        boolean needMine = false;
        for (int h = 0; h < height; h++) {
            BlockPos pos = floor.up(1 + h);
            BlockState s = w.getBlockState(pos);
            if (!s.getFluidState().isEmpty() || harmful(s)) return Col.BLOCKED;
            if (s.getCollisionShape(w, pos).isEmpty()) continue;
            if (!inArea(x, z) || !breakSafe(w, pos)) return Col.BLOCKED;
            needMine = true;
        }
        return needMine ? Col.MINE : Col.WALK;
    }

    /** 周囲8マスに穴や液体があるか (あればスニークで歩く)。 */
    private boolean hazardNear(ClientWorld w, int x, int z) {
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (dx == 0 && dz == 0) continue;
                BlockPos floor = new BlockPos(x + dx, feetY - 1, z + dz);
                BlockPos feet = floor.up();
                if (hasFluid(w, floor) || hasFluid(w, feet) || hasFluid(w, feet.up())) return true;
                BlockState fs = w.getBlockState(floor);
                boolean open = w.getBlockState(feet).getCollisionShape(w, feet).isEmpty();
                if (open && !fs.isSideSolidFullSquare(w, floor, Direction.UP)) return true;
            }
        }
        return false;
    }

    /** ダイクストラ法。start の次のマスから goal までの列を返す。行けなければ null。 */
    private List<Long> findPath(ClientWorld w, int sx, int sz, int gx, int gz, boolean allowMine) {
        if (!inBounds(sx, sz) || !inBounds(gx, gz)) return null;
        int bx = minX - 1, bz = minZ - 1;
        int wd = maxX - minX + 3, hd = maxZ - minZ + 3;
        int n = wd * hd;
        int[] dist = new int[n];
        int[] prev = new int[n];
        Arrays.fill(dist, Integer.MAX_VALUE);
        Arrays.fill(prev, -1);
        int s = (sx - bx) + (sz - bz) * wd;
        int g = (gx - bx) + (gz - bz) * wd;
        dist[s] = 0;
        PriorityQueue<int[]> pq = new PriorityQueue<>((a, b) -> Integer.compare(a[0], b[0]));
        pq.add(new int[]{0, s});
        while (!pq.isEmpty()) {
            int[] cur = pq.poll();
            int ci = cur[1];
            if (cur[0] > dist[ci]) continue;
            if (ci == g) break;
            int cx = ci % wd + bx, cz = ci / wd + bz;
            for (int[] d : DIRS) {
                int nx = cx + d[0], nz = cz + d[1];
                if (!inBounds(nx, nz)) continue;
                Col c = classify(w, nx, nz);
                if (c == Col.BLOCKED || (c == Col.MINE && !allowMine)) continue;
                int ni = (nx - bx) + (nz - bz) * wd;
                int nc = cur[0] + (c == Col.MINE ? 4 : 1);
                if (nc < dist[ni]) {
                    dist[ni] = nc;
                    prev[ni] = ci;
                    pq.add(new int[]{nc, ni});
                }
            }
        }
        if (dist[g] == Integer.MAX_VALUE) return null;
        LinkedList<Long> out = new LinkedList<>();
        for (int i = g; i != s && i != -1; i = prev[i]) {
            out.addFirst(key(i % wd + bx, i / wd + bz));
        }
        return out;
    }

    // ------------------------------------------------------------------ 採掘

    private void setBreak(BlockPos pos) {
        breakTarget = pos.toImmutable();
        aimPoint = null;
        breakTicks = 0;
        missTicks = 0;
    }

    private void endBreak(boolean broken) {
        if (breaking) mc.interactionManager.cancelBlockBreaking();
        breaking = false;
        if (!broken && breakTarget != null) badBlocks.add(breakTarget);
        breakTarget = null;
        aimPoint = null;
        path = null;
        if (broken) {
            delay = rng.nextInt(cfg.maxActionDelay + 1);
            if (rng.nextInt(40) == 0) delay += 8 + rng.nextInt(20); // たまに手を止める
        }
    }

    private void doBreak(ClientPlayerEntity p, ClientWorld w) {
        GameOptions o = mc.options;
        o.forwardKey.setPressed(false);
        o.sneakKey.setPressed(hazardNear(w, MathHelper.floor(p.getX()), MathHelper.floor(p.getZ())));

        BlockState s = w.getBlockState(breakTarget);
        if (s.isAir() || s.getCollisionShape(w, breakTarget).isEmpty() && !oreBlocks.contains(s.getBlock())) {
            endBreak(true);
            return;
        }
        // 掘っている最中に水が流れてきた場合などもここで止まる
        if (!breakSafe(w, breakTarget)) {
            endBreak(false);
            return;
        }
        if (++breakTicks > 300) {
            endBreak(false);
            return;
        }
        if (aimPoint == null) {
            aimPoint = aim.findAimPoint(p, w, breakTarget);
            if (aimPoint == null) {
                if (++missTicks > 20) endBreak(false);
                return;
            }
        }
        Vec3d eye = p.getEyePos();
        aim.step(p, Aim.yawTo(eye, aimPoint), Aim.pitchTo(eye, aimPoint), cfg.rotationSpeed);

        HitResult hr = p.raycast(p.getBlockInteractionRange(), 1f, false);
        if (hr instanceof BlockHitResult bh && hr.getType() == HitResult.Type.BLOCK
                && bh.getBlockPos().equals(breakTarget)) {
            missTicks = 0;
            breaking = true;
            if (mc.interactionManager.updateBlockBreakingProgress(breakTarget, bh.getSide())) {
                mc.particleManager.addBlockBreakingParticles(breakTarget, bh.getSide());
                p.swingHand(Hand.MAIN_HAND);
            }
        } else {
            if (breaking) {
                mc.interactionManager.cancelBlockBreaking();
                breaking = false;
            }
            // 視点が合ったのに当たらない → 狙う点を選び直す
            if (++missTicks > 12) {
                aimPoint = null;
                missTicks = 0;
            }
        }
    }

    /** 今の立ち位置から見えて届く、安全に掘れる鉱石を探す。 */
    private BlockPos findOre(ClientPlayerEntity p, ClientWorld w) {
        if (oreBlocks.isEmpty()) return null;
        Vec3d eye = p.getEyePos();
        BlockPos c = BlockPos.ofFloored(eye);
        int top = feetY + height - 1 + cfg.oreUp;
        List<BlockPos> found = new ArrayList<>();
        for (BlockPos b : BlockPos.iterate(c.add(-5, -5, -5), c.add(5, 5, 5))) {
            if (b.getY() < feetY || b.getY() > top) continue; // 床は掘らない
            if (!oreBlocks.contains(w.getBlockState(b).getBlock())) continue;
            if (b.toCenterPos().squaredDistanceTo(eye) > 25.0) continue;
            found.add(b.toImmutable());
        }
        found.sort((a, b) -> Double.compare(a.toCenterPos().squaredDistanceTo(eye), b.toCenterPos().squaredDistanceTo(eye)));
        for (BlockPos b : found) {
            if (breakSafe(w, b) && aim.findAimPoint(p, w, b) != null) return b;
        }
        return null;
    }

    // ------------------------------------------------------------------ 歩行

    /** (tx,tz) へ歩く。着いたら true。 */
    private boolean walkTo(ClientPlayerEntity p, ClientWorld w, double tx, double tz, double radius) {
        GameOptions o = mc.options;
        double dx = tx - p.getX(), dz = tz - p.getZ();
        double dist = Math.hypot(dx, dz);
        boolean hazard = hazardNear(w, MathHelper.floor(p.getX()), MathHelper.floor(p.getZ()));
        o.sneakKey.setPressed(hazard);
        if (dist < radius) {
            o.forwardKey.setPressed(false);
            return true;
        }
        float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        if (rng.nextInt(60) == 0) walkPitch = 12f + rng.nextFloat() * 22f;
        aim.step(p, yaw, walkPitch, cfg.rotationSpeed);
        float err = Math.abs(MathHelper.wrapDegrees(yaw - p.getYaw()));
        o.forwardKey.setPressed(err < (dist > 0.8 ? 30f : 12f));

        // 進めていないなら経路を引き直し、それでも駄目なら止める
        if (++walkTicks % 60 == 0) {
            Vec3d now = p.getPos();
            if (stuckPos != null && now.squaredDistanceTo(stuckPos) < 0.01) {
                path = null;
                if (++stuckCount >= 3) emergencyStop("進めなくなりました");
            } else {
                stuckCount = 0;
            }
            stuckPos = now;
        }
        return false;
    }

    private void newOffset() {
        offX = (rng.nextDouble() - 0.5) * 0.2;
        offZ = (rng.nextDouble() - 0.5) * 0.2;
    }

    // ------------------------------------------------------------------ アイテム回収

    private boolean handleItems(ClientPlayerEntity p, ClientWorld w) {
        if (tickCount % 5 == 0 || itemTarget == null || !itemTarget.isAlive()) {
            chooseItem(p, w);
            if (state == State.IDLE) return true;
        }
        if (itemTarget == null) return false;

        status = itemPriority ? "指定アイテムを回収" : "アイテムを回収";
        int px = MathHelper.floor(p.getX()), pz = MathHelper.floor(p.getZ());
        if (key(px, pz) != standCol) {
            List<Long> ip = findPath(w, px, pz, kx(standCol), kz(standCol), false);
            if (ip == null || ip.isEmpty()) {
                itemTarget = null;
                return false;
            }
            long next = ip.get(0);
            walkTo(p, w, kx(next) + 0.5, kz(next) + 0.5, 0.05);
            return true;
        }
        if (walkTo(p, w, standX, standZ, 0.15)) {
            // 着いたのに拾えない (インベントリ満杯など)
            if (++itemWait > 30) {
                if (itemPriority) {
                    emergencyStop("指定アイテムを拾えません (インベントリ満杯?)");
                } else {
                    itemBlacklist.add(itemTarget.getId());
                    itemTarget = null;
                }
            }
        }
        return true;
    }

    private void chooseItem(ClientPlayerEntity p, ClientWorld w) {
        ItemEntity prev = itemTarget;
        itemTarget = null;
        Box box = new Box(minX - 3, feetY - 2, minZ - 3, maxX + 4, feetY + height + 3, maxZ + 4);
        List<ItemEntity> items = w.getEntitiesByClass(ItemEntity.class, box,
                e -> e.isAlive() && !itemBlacklist.contains(e.getId()));
        int px = MathHelper.floor(p.getX()), pz = MathHelper.floor(p.getZ());

        ItemEntity best = null;
        boolean bestPri = false;
        double bestD = Double.MAX_VALUE, bx = 0, bz = 0;
        long bcol = 0;
        Set<Integer> alive = new HashSet<>();
        for (ItemEntity e : items) {
            boolean pri = cfg.isPriority(e.getStack());
            if (pri) {
                alive.add(e.getId());
                int first = prioritySeen.computeIfAbsent(e.getId(), id -> tickCount);
                if (tickCount - first > cfg.itemTimeoutTicks) {
                    emergencyStop(String.format("指定アイテムを回収できません: %s (%.1f, %.1f, %.1f)",
                            e.getStack().getName().getString(), e.getX(), e.getY(), e.getZ()));
                    return;
                }
            } else if (!cfg.pickupAll || e.age < 60) {
                continue; // 普通のドロップは歩いていれば拾えるので、少し待ってから取りに行く
            }
            if (bestPri && !pri) continue;
            if (e.getY() < feetY - 0.4 || e.getY() > feetY + height) continue;
            double d = e.squaredDistanceTo(p);
            if (best != null && pri == bestPri && d >= bestD) continue;

            // アイテムのマスか、その隣で立てる場所を探す
            int ix = MathHelper.floor(e.getX()), iz = MathHelper.floor(e.getZ());
            double sd = Double.MAX_VALUE, sx = 0, sz = 0;
            long scol = 0;
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    int cx = ix + dx, cz = iz + dz;
                    if (!inBounds(cx, cz)) continue;
                    if (!(cx == px && cz == pz) && classify(w, cx, cz) != Col.WALK) continue;
                    double qx = MathHelper.clamp(e.getX(), cx + 0.32, cx + 0.68);
                    double qz = MathHelper.clamp(e.getZ(), cz + 0.32, cz + 0.68);
                    double q = Math.hypot(qx - e.getX(), qz - e.getZ());
                    if (q > 1.15 || q >= sd) continue;
                    if (!(cx == px && cz == pz) && findPath(w, px, pz, cx, cz, false) == null) continue;
                    sd = q;
                    sx = qx;
                    sz = qz;
                    scol = key(cx, cz);
                }
            }
            if (sd == Double.MAX_VALUE) continue;
            best = e;
            bestPri = pri;
            bestD = d;
            bx = sx;
            bz = sz;
            bcol = scol;
        }
        prioritySeen.keySet().retainAll(alive);
        if (best != null) {
            itemTarget = best;
            itemPriority = bestPri;
            standX = bx;
            standZ = bz;
            standCol = bcol;
            if (best != prev) itemWait = 0;
        }
    }

    // ------------------------------------------------------------------ 範囲の採掘

    private void mineArea(ClientPlayerEntity p, ClientWorld w) {
        int px = MathHelper.floor(p.getX()), pz = MathHelper.floor(p.getZ());
        long cur = key(px, pz);

        if (path == null || path.isEmpty() || ++pathAge > 40) {
            pathAge = 0;
            path = null;
            int[] target = null;
            int mined = 0, skipped = 0;
            for (int[] c : order) {
                Col col = classify(w, c[0], c[1]);
                if (col == Col.WALK) {
                    mined++;
                } else if (col == Col.BLOCKED || unreachable.contains(key(c[0], c[1]))) {
                    skipped++;
                } else if (target == null) {
                    target = c;
                }
            }
            minedCols = mined;
            skippedCols = skipped;
            if (target == null) {
                // 落ちたばかりのアイテムを拾い終えるまで少し待ってから完了にする
                halt();
                status = "仕上げ確認";
                if (++finishTicks >= 80) finish(w);
                return;
            }
            finishTicks = 0;
            path = findPath(w, px, pz, target[0], target[1], true);
            if (path == null) {
                unreachable.add(key(target[0], target[1]));
                return;
            }
            newOffset();
        }

        while (!path.isEmpty() && path.get(0) == cur) {
            path.remove(0);
            newOffset();
        }
        if (path.isEmpty()) {
            path = null;
            return;
        }
        long next = path.get(0);
        int nx = kx(next), nz = kz(next);
        if (Math.abs(nx - px) + Math.abs(nz - pz) != 1) {
            path = null;
            return;
        }
        Col c = classify(w, nx, nz);
        if (c == Col.BLOCKED) {
            path = null;
        } else if (c == Col.MINE) {
            status = "採掘中";
            // 頭の高さから先に掘る (落下ブロックや流体の確認がしやすい)
            for (int h = height - 1; h >= 0; h--) {
                BlockPos pos = new BlockPos(nx, feetY + h, nz);
                if (!w.getBlockState(pos).getCollisionShape(w, pos).isEmpty()) {
                    setBreak(pos);
                    return;
                }
            }
        } else {
            status = "移動中";
            walkTo(p, w, nx + 0.5 + offX, nz + 0.5 + offZ, 0.05);
        }
    }

    private void finish(ClientWorld w) {
        halt();
        // 掘らずに残した鉱石 (床下など) を報告する
        List<String> left = new ArrayList<>();
        int leftCount = 0;
        if (!oreBlocks.isEmpty()) {
            for (BlockPos b : BlockPos.iterate(minX - 1, feetY - 1, minZ - 1, maxX + 1, feetY + height, maxZ + 1)) {
                if (!oreBlocks.contains(w.getBlockState(b).getBlock())) continue;
                boolean exposed = false;
                for (Direction d : Direction.values()) {
                    BlockPos n = b.offset(d);
                    if (w.getBlockState(n).getCollisionShape(w, n).isEmpty()) exposed = true;
                }
                if (!exposed) continue;
                leftCount++;
                if (left.size() < 8) left.add("(" + b.getX() + "," + b.getY() + "," + b.getZ() + ")");
            }
        }
        String msg = "完了: " + minedCols + "/" + totalCols + " マス";
        if (skippedCols > 0) msg += "、危険なため回避 " + skippedCols + " マス";
        state = State.IDLE;
        status = "停止中";
        clearTasks();
        say(Formatting.GREEN, msg);
        if (leftCount > 0) {
            say(Formatting.YELLOW, "安全に掘れず残した鉱石 " + leftCount + " 個: " + String.join(" ", left));
        }
    }
}
