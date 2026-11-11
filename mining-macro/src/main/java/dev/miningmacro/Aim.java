package dev.miningmacro;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;
import net.minecraft.world.World;

import java.util.Random;

/**
 * 視点操作。瞬間的に向きを変えず、毎 tick 少しずつ (速度上限・減速・ゆらぎつきで) 目標へ寄せる。
 * 回転量はマウス感度の最小単位に丸めるので、実際のマウス入力と同じ刻みになる。
 */
public final class Aim {
    private final Random rng = new Random();

    /** マウス1カウントあたりの回転角 (バニラの感度計算と同じ)。 */
    private static double mouseStep() {
        double s = MinecraftClient.getInstance().options.getMouseSensitivity().getValue() * 0.6 + 0.2;
        return s * s * s * 8.0 * 0.15;
    }

    public static float yawTo(Vec3d from, Vec3d to) {
        return (float) Math.toDegrees(Math.atan2(-(to.x - from.x), to.z - from.z));
    }

    public static float pitchTo(Vec3d from, Vec3d to) {
        double h = Math.hypot(to.x - from.x, to.z - from.z);
        return (float) -Math.toDegrees(Math.atan2(to.y - from.y, h));
    }

    /** 目標角へ 1 tick 分だけ寄せる。残りの角度差 (度) を返す。 */
    public double step(ClientPlayerEntity p, float targetYaw, float targetPitch, double speedMul) {
        double dy = MathHelper.wrapDegrees(targetYaw - p.getYaw());
        double dp = targetPitch - p.getPitch();
        double dist = Math.hypot(dy, dp);
        if (dist < 0.4) return dist;

        double gain = 0.22 + rng.nextDouble() * 0.2;
        double max = (16 + rng.nextDouble() * 12) * speedMul;
        double move = Math.min(Math.min(dist * gain + 0.7, max), dist);
        double f = move / dist;
        double wobble = Math.min(1.0, dist / 12.0) * 0.25;
        double my = dy * f + rng.nextGaussian() * wobble;
        double mp = dp * f + rng.nextGaussian() * wobble * 0.6;

        double g = mouseStep();
        my = Math.round(my / g) * g;
        mp = Math.round(mp / g) * g;

        p.setYaw(p.getYaw() + (float) my);
        p.setPitch(MathHelper.clamp(p.getPitch() + (float) mp, -90f, 90f));
        return Math.hypot(MathHelper.wrapDegrees(targetYaw - p.getYaw()), targetPitch - p.getPitch());
    }

    /** そのブロックの「今の目の位置から実際に見えて手が届く点」を探す。無ければ null。 */
    public Vec3d findAimPoint(ClientPlayerEntity p, World world, BlockPos pos) {
        Vec3d eye = p.getEyePos();
        double range = p.getBlockInteractionRange() - 0.3;
        Vec3d best = null;
        double bestD = Double.MAX_VALUE;
        for (Direction d : Direction.values()) {
            for (int i = 0; i < 3; i++) {
                double u = rng.nextDouble() * 0.5 - 0.25;
                double v = rng.nextDouble() * 0.5 - 0.25;
                double x = pos.getX() + 0.5, y = pos.getY() + 0.5, z = pos.getZ() + 0.5;
                switch (d.getAxis()) {
                    case X -> { x += d.getOffsetX() * 0.49; y += u; z += v; }
                    case Y -> { y += d.getOffsetY() * 0.49; x += u; z += v; }
                    default -> { z += d.getOffsetZ() * 0.49; x += u; y += v; }
                }
                Vec3d pt = new Vec3d(x, y, z);
                double dsq = eye.squaredDistanceTo(pt);
                if (dsq > range * range || dsq >= bestD) continue;
                if (hits(p, world, eye, pt, pos)) {
                    best = pt;
                    bestD = dsq;
                }
            }
        }
        return best;
    }

    private static boolean hits(ClientPlayerEntity p, World world, Vec3d eye, Vec3d pt, BlockPos pos) {
        Vec3d end = pt.add(pt.subtract(eye).normalize().multiply(0.3));
        BlockHitResult hit = world.raycast(new RaycastContext(eye, end,
                RaycastContext.ShapeType.OUTLINE, RaycastContext.FluidHandling.NONE, p));
        return hit.getType() == HitResult.Type.BLOCK && hit.getBlockPos().equals(pos);
    }
}
