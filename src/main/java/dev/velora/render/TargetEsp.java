package dev.velora.render;

import dev.velora.Velora;
import dev.velora.module.Category;
import dev.velora.module.Module;
import dev.velora.render.lightning.LightningBoltGeometry;
import dev.velora.render.lightning.LightningEspColor;
import dev.velora.render.lightning.LightningEspState;
import dev.velora.render.lightning.LightningLinesPipeline;
import dev.velora.setting.BooleanSetting;
import dev.velora.setting.ColorSetting;
import dev.velora.setting.ModeSetting;
import dev.velora.setting.NumberSetting;
import dev.velora.util.TargetUtil;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.render.BufferBuilder;
import net.minecraft.client.render.BuiltBuffer;
import net.minecraft.client.render.Camera;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.entity.LivingEntity;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;

/**
 * TargetESP with four styles known from other clients:
 * <ul>
 *     <li><b>Circle</b> — a neon scan that sweeps up the target with a comet trail.</li>
 *     <li><b>Square</b> — corner brackets framing the target's hitbox.</li>
 *     <li><b>Souls</b> — orbiting particles around the body.</li>
 *     <li><b>Constellation</b> — a tilted ring of stars linked by lines, glowing and
 *         slowly turning around the body like a constellation chart.</li>
 *     <li><b>Lightning</b> — flickering lightning arcs coiling around the body
 *         (drawn in the world 3D pass, see {@link #onRenderWorld}).</li>
 * </ul>
 * Everything except lightning is projected from world space to the 2D HUD, so it
 * follows the target smoothly and looks the same at any GUI scale. Lightning
 * renders real 3D lines with additive blending through its own render pipelines.
 */
public class TargetEsp extends Module {

    private final ModeSetting mode = register(new ModeSetting("Mode", "Стиль отрисовки цели", "Circle",
            "Circle", "Square", "Souls", "Constellation", "Lightning"));
    private final BooleanSetting themeColor = register(new BooleanSetting("Theme color", "Брать цвет из темы клиента", true));
    private final ColorSetting color = register(new ColorSetting("Color", "Свой цвет, если тема отключена", 0xFFFFC64A));
    private final NumberSetting range = register(new NumberSetting("Range", "Дальность поиска цели", 16.0, 4.0, 48.0, 1.0));
    private final NumberSetting size = register(new NumberSetting("Size", "Масштаб эффекта", 1.0, 0.5, 2.0, 0.05));
    private final NumberSetting speed = register(new NumberSetting("Speed", "Скорость анимации", 1.0, 0.0, 3.0, 0.1));
    private final BooleanSetting players = register(new BooleanSetting("Players", "Отмечать игроков", true));
    private final BooleanSetting mobs = register(new BooleanSetting("Mobs", "Отмечать мобов", false));
    private final BooleanSetting throughWalls = register(new BooleanSetting("Through walls", "Видно сквозь блоки", true));
    /** Animation speed for the lightning mode only; 1.0 is the original pace. */
    private final NumberSetting boltSpeed = register(new NumberSetting("Bolt speed", "Скорость молний", 0.7, 0.2, 3.0, 0.05));

    // ----------------------------------------------------------------- souls
    private final NumberSetting soulsCount = register(new NumberSetting("Souls count", "Сколько душ летает вокруг", 12, 4, 24, 1));
    private final NumberSetting soulsOrbit = register(new NumberSetting("Souls orbit", "Радиус орбиты душ", 1.0, 0.5, 2.0, 0.05));
    private final NumberSetting soulsTrail = register(new NumberSetting("Souls trail", "Длина шлейфа за душой", 3, 0, 6, 1));
    private final BooleanSetting soulsFlame = register(new BooleanSetting("Souls flame", "Пламя, поднимающееся от ног", true));
    private final BooleanSetting soulsPedestal = register(new BooleanSetting("Souls pedestal", "Светящееся кольцо у ног", true));
    private final BooleanSetting soulsSpin = register(new BooleanSetting("Souls spin up", "Плавный разгон вращения при включении", true));

    // -------------------------------------------------------- constellation
    private final NumberSetting conStars = register(new NumberSetting("Stars", "Количество звёзд", 12, 5, 28, 1));
    private final NumberSetting conOrbit = register(new NumberSetting("Star orbit", "Радиус созвездия", 1.0, 0.4, 2.2, 0.05));
    private final NumberSetting conPointSize = register(new NumberSetting("Star size", "Размер звёзд", 1.6, 0.6, 4.0, 0.1));
    private final NumberSetting conGlow = register(new NumberSetting("Star glow", "Сила свечения звёзд", 0.55, 0.0, 1.0, 0.05));
    private final BooleanSetting conLines = register(new BooleanSetting("Star lines", "Соединять звёзды линиями", true));

    /** Animation state of the lightning arcs (fade in/out, time, occlusion). */
    private final LightningEspState lightningState = new LightningEspState();

    /** Sticky target and fade of the constellation mode (it lingers briefly on target loss). */
    private LivingEntity constellationTarget;
    private float constellationAlpha;
    private long constellationLastNanos;

    /** Souls: free-running spin angle, so enabling the mode never snaps the ribbons. */
    private float soulsSpinPhase;
    private long soulsLastNanos = System.nanoTime();
    /** Cached hurt strength of the souls target (fades out after a hit). */
    private float soulsHurt;

    public TargetEsp() {
        super("TargetESP",
                "Отмечает цель: сканирующий круг, рамки, души, созвездие или молнии",
                Category.RENDER);
    }

    @Override
    public void onRender2D(DrawContext context, float tickDelta) {
        if (mode.is("Lightning")) {
            // Lightning lives in the world 3D pass; nothing to do on the HUD.
            // Also drops a stale sticky target if the mode was just switched.
            if (lightningState.getLastTarget() != null) {
                lightningState.clearTarget();
            }
            return;
        }

        LivingEntity target = TargetUtil.findBest(range.get(), 360.0, players.get(), mobs.get(), throughWalls.get());
        if (mode.is("Constellation")) {
            // The constellation keeps a sticky target for its fade-out, so it is
            // driven before the "no target" early return below.
            renderConstellation(context, target, tickDelta);
            return;
        }
        if (target == null) {
            soulsHurt = 0f;
            return;
        }

        Box box = Projection.interpolatedBox(target, tickDelta);
        float time = (System.currentTimeMillis() % 1_000_000L) / 1000f;
        float sizeScale = size.getFloat();
        int baseColor = espColor();

        if (mode.is("Souls")) {
            updateSoulsState(target);
            renderSouls(context, box, time, baseColor, sizeScale);
        } else if (mode.is("Square")) {
            renderBrackets(context, box, time, baseColor, sizeScale);
        } else {
            renderScan(context, box, time, baseColor, sizeScale);
        }
    }

    /** Advances the souls animation clock and the hurt flash decay. */
    private void updateSoulsState(LivingEntity target) {
        long now = System.nanoTime();
        float dt = MathHelper.clamp((now - soulsLastNanos) / 1_000_000_000f, 0f, 0.1f);
        soulsLastNanos = now;

        soulsSpinPhase = (soulsSpinPhase + dt * 26f) % 360f;

        // Snap up on a hit, ease back down afterwards so the palette does not pop.
        float wanted = MathHelper.clamp(target.hurtTime / 10f, 0f, 1f);
        soulsHurt = wanted > soulsHurt
                ? wanted
                : MathHelper.clamp(soulsHurt - dt * 2.6f, 0f, 1f);
    }

    // ---------------------------------------------------------- constellation

    /**
     * Constellation: stars spread over a tilted, slowly spinning orbit around the
     * target, linked by thin lines and each with a soft glow, like a star chart
     * wrapped around the body. The ring breathes, the stars pulse and the whole
     * constellation lingers for a moment with a fade when the target is lost.
     */
    private void renderConstellation(DrawContext context, LivingEntity target, float tickDelta) {
        float dt = constellationDelta();
        if (target != null) {
            constellationTarget = target;
        }
        // Fade in quickly, fade out a little slower (a soft "release" of the target).
        float step = dt * (target != null ? 6.5f : 4.0f);
        constellationAlpha = MathHelper.clamp(constellationAlpha + (target != null ? step : -step), 0f, 1f);
        if (constellationTarget == null || constellationAlpha <= 0.01f) {
            return;
        }

        Box box = Projection.interpolatedBox(constellationTarget, tickDelta);
        float alpha = constellationAlpha * constellationAlpha * (3f - 2f * constellationAlpha);
        alpha *= fadeAlpha();

        int stars = Math.max(5, Math.min(28, conStars.getInt()));
        float orbit = conOrbit.getFloat();
        float sizeScale = size.getFloat();
        float spin = HudStyle.time() * speed.getFloat() * 0.9f;

        int baseColor = espColor();
        // The reference mixes the palette towards red while the target is hurt.
        float hurt = constellationTarget.hurtTime > 0
                ? MathHelper.clamp(constellationTarget.hurtTime / 10f, 0f, 1f) : 0f;
        int from = ColorUtil.lerp(baseColor, 0xFFFF4D4D, hurt * 0.55f);
        int to = ColorUtil.lerp(HudStyle.accentSecondary(), 0xFFFF8080, hurt * 0.55f);

        Vec3d center = box.getCenter();
        // The ring is wider than the hitbox, so it reads as an orbit around the
        // body instead of a halo stuck to it.
        double radius = (Math.max(box.getLengthX(), box.getLengthZ()) * 0.5 + 0.42)
                * orbit * sizeScale;
        double vertical = Math.max(0.3, box.getLengthY() * 0.5) * sizeScale;
        // Pivot on the chest, so the constellation surrounds the body.
        Vec3d pivot = new Vec3d(center.x, box.minY + box.getLengthY() * 0.55, center.z);

        float[] xs = new float[stars];
        float[] ys = new float[stars];
        int[] colors = new int[stars];
        boolean[] visible = new boolean[stars];

        for (int i = 0; i < stars; i++) {
            float frac = i / (float) stars;
            float angle = spin + frac * MathHelper.TAU;
            // A tilted ellipse: the inclination makes the ring read as 3D, not as a halo.
            float inclination = (float) (Math.PI * 0.34 * Math.sin(frac * Math.PI * 1.5 + 0.5));
            double r = radius * (0.86 + 0.14 * Math.sin(frac * 6.28 + spin * 0.6));
            double bob = Math.sin(spin * 2.2 + frac * MathHelper.TAU) * vertical * 0.16;
            Vec3d point = pivot.add(
                    Math.cos(angle) * r,
                    Math.sin(angle) * Math.sin(inclination) * r * 0.72 + bob,
                    Math.sin(angle) * Math.cos(inclination) * r);

            float[] screen = Projection.project(point);
            if (screen == null) {
                continue;
            }
            visible[i] = true;
            xs[i] = screen[0];
            ys[i] = screen[1];
            colors[i] = ColorUtil.lerp(from, to, fadeWeight(frac));
        }

        // Lines first, so the stars sit on top of their own constellation.
        if (conLines.get()) {
            for (int i = 0; i < stars; i++) {
                int next = (i + 1) % stars;
                if (!visible[i] || !visible[next]) {
                    continue;
                }
                int line = ColorUtil.withAlpha(colors[i], Math.round(150f * alpha));
                Render2D.line(context, xs[i], ys[i], xs[next], ys[next], 1.2f, line);
            }
        }

        float pointSize = conPointSize.getFloat() * sizeScale;
        float glow = conGlow.getFloat();
        for (int i = 0; i < stars; i++) {
            if (!visible[i]) {
                continue;
            }
            float frac = i / (float) stars;
            float pulse = 0.78f + 0.22f * (float) Math.sin(spin * 3.4f + frac * MathHelper.TAU * 1.7f);
            float core = Math.max(1.1f, pointSize * pulse * 1.25f);
            int color = colors[i];

            if (glow > 0.01f) {
                // Two soft halos: a wide, very faint one and a tight, brighter one.
                Render2D.circle(context, xs[i], ys[i], core * 7f,
                        ColorUtil.withAlpha(color, Math.round(26f * glow * alpha)));
                Render2D.circle(context, xs[i], ys[i], core * 3.4f,
                        ColorUtil.withAlpha(color, Math.round(70f * glow * alpha)));
            }
            Render2D.circle(context, xs[i], ys[i], core * 1.8f,
                    ColorUtil.withAlpha(color, Math.round(170f * alpha)));
            Render2D.circle(context, xs[i], ys[i], core,
                    ColorUtil.withAlpha(ColorUtil.lerp(color, 0xFFFFFFFF, 0.45f), Math.round(255f * alpha)));
        }
    }

    /** Seconds since the previous constellation frame, clamped to a sane step. */
    private float constellationDelta() {
        long now = System.nanoTime();
        if (constellationLastNanos == 0L) {
            constellationLastNanos = now;
            return 1f / 60f;
        }
        float dt = (now - constellationLastNanos) / 1_000_000_000f;
        constellationLastNanos = now;
        return MathHelper.clamp(dt, 0f, 0.1f);
    }

    /** Gradient position of a star along the ring (0…1…0), used for the colour mix. */
    private static float fadeWeight(float frac) {
        return 0.5f - 0.5f * (float) Math.cos(frac * MathHelper.TAU);
    }

    /** Alpha of the whole ESP effect; also used by the constellation fade. */
    private float fadeAlpha() {
        return 1f;
    }

    // ------------------------------------------------------------------- scan

    /**
     * Scan mode: a smooth ring travels from the feet to the head, leaving a short
     * fading trail behind it. The radius follows the projected body size, so it
     * hugs the target instead of being a fixed halo.
     */
    private void renderScan(DrawContext context, Box box, float time, int color, float sizeScale) {
        Vec3d center = box.getCenter();
        double scanY = box.minY + box.getLengthY() * 0.5;
        float radius = Math.max(10f, projectedHalfWidth(box, scanY) * 1.35f) * sizeScale;
        float t = time * speed.getFloat();
        float cycle = 1.55f;
        float phase = (t / cycle) % 1f;
        // Slight easing so the scanner slows down at the ends of its pass.
        float sweep = phase * phase * (3f - 2f * phase);

        int trail = 6;
        for (int i = trail; i >= 0; i--) {
            float p = sweep - i * 0.055f;
            if (p < 0.02f) {
                continue;
            }
            double worldY = box.minY + box.getLengthY() * (0.10 + 0.82 * p);
            float[] point = Projection.project(new Vec3d(center.x, worldY, center.z));
            if (point == null) {
                continue;
            }

            boolean current = i == 0;
            if (current) {
                Render2D.ringGlow(context, point[0], point[1], radius * 1.05f,
                        ColorUtil.withAlpha(color, 48));
            }
            int alpha = current ? 240 : Math.max(0, 105 - i * 17);
            Render2D.ring(context, point[0], point[1], radius, ColorUtil.withAlpha(color, alpha));
        }
    }

    /** Half of the projected body width at the given height, in GUI pixels. */
    private static float projectedHalfWidth(Box box, double worldY) {
        Vec3d center = box.getCenter();
        float half = 0f;
        float[] minX = Projection.project(new Vec3d(box.minX, worldY, center.z));
        float[] maxX = Projection.project(new Vec3d(box.maxX, worldY, center.z));
        if (minX != null && maxX != null) {
            half = Math.max(half, Math.abs(maxX[0] - minX[0]) / 2f);
        }
        float[] minZ = Projection.project(new Vec3d(center.x, worldY, box.minZ));
        float[] maxZ = Projection.project(new Vec3d(center.x, worldY, box.maxZ));
        if (minZ != null && maxZ != null) {
            half = Math.max(half, Math.abs(maxZ[0] - minZ[0]) / 2f);
        }
        return half;
    }

    // ---------------------------------------------------------------- brackets

    /**
     * Square mode: four L-shaped brackets around the projected hitbox, with a soft
     * glow and a slow breathing pulse. Looks like the classic "target frame"
     * overlay instead of a literal rotating square.
     */
    private void renderBrackets(DrawContext context, Box box, float time, int color, float sizeScale) {
        float minX = Float.MAX_VALUE;
        float minY = Float.MAX_VALUE;
        float maxX = -Float.MAX_VALUE;
        float maxY = -Float.MAX_VALUE;
        int visible = 0;

        for (int i = 0; i < 8; i++) {
            double x = (i & 1) == 0 ? box.minX : box.maxX;
            double y = (i & 2) == 0 ? box.minY : box.maxY;
            double z = (i & 4) == 0 ? box.minZ : box.maxZ;
            float[] point = Projection.project(new Vec3d(x, y, z));
            if (point == null) {
                continue;
            }
            visible++;
            minX = Math.min(minX, point[0]);
            maxX = Math.max(maxX, point[0]);
            minY = Math.min(minY, point[1]);
            maxY = Math.max(maxY, point[1]);
        }

        if (visible < 2) {
            Vec3d center = box.getCenter();
            float[] screen = Projection.project(center);
            float[] top = Projection.project(new Vec3d(center.x, box.maxY, center.z));
            float[] bottom = Projection.project(new Vec3d(center.x, box.minY, center.z));
            if (screen == null || top == null || bottom == null) {
                return;
            }
            float height = Math.max(16f, Math.abs(bottom[1] - top[1]));
            float width = height * 0.45f;
            minX = screen[0] - width / 2f;
            maxX = screen[0] + width / 2f;
            minY = Math.min(top[1], bottom[1]);
            maxY = Math.max(top[1], bottom[1]);
        }

        float padding = 3.5f * sizeScale;
        minX -= padding;
        maxX += padding;
        minY -= padding;
        maxY += padding;
        float width = maxX - minX;
        float height = maxY - minY;
        if (width < 6f || height < 6f) {
            return;
        }

        float horizontalArm = Math.min(width * 0.30f, 24f * sizeScale);
        float verticalArm = Math.min(height * 0.34f, 32f * sizeScale);
        float thickness = MathHelper.clamp(1.7f * sizeScale, 1f, 3.2f);
        float pulse = 0.86f + (float) Math.sin(time * 2.1) * 0.14f;

        int glow = ColorUtil.withAlpha(color, Math.round(46 * pulse));
        int main = ColorUtil.withAlpha(color, Math.round(232 * pulse));

        drawBrackets(context, minX - 1f, minY - 1f, width + 2f, height + 2f,
                horizontalArm + 1f, verticalArm + 1f, thickness + 1.6f, glow);
        drawBrackets(context, minX, minY, width, height,
                horizontalArm, verticalArm, thickness, main);
    }

    private void drawBrackets(DrawContext context, float x, float y, float width, float height,
                              float horizontalArm, float verticalArm, float thickness, int color) {
        // Top-left
        Render2D.rect(context, x, y, horizontalArm, thickness, color);
        Render2D.rect(context, x, y, thickness, verticalArm, color);
        // Top-right
        Render2D.rect(context, x + width - horizontalArm, y, horizontalArm, thickness, color);
        Render2D.rect(context, x + width - thickness, y, thickness, verticalArm, color);
        // Bottom-left
        Render2D.rect(context, x, y + height - thickness, horizontalArm, thickness, color);
        Render2D.rect(context, x, y + height - verticalArm, thickness, verticalArm, color);
        // Bottom-right
        Render2D.rect(context, x + width - horizontalArm, y + height - thickness, horizontalArm, thickness, color);
        Render2D.rect(context, x + width - thickness, y + height - verticalArm, thickness, verticalArm, color);
    }

    // ------------------------------------------------------------------- souls

    /**
     * Souls mode — the look known from Nursultan / Celestial.
     *
     * <p>Instead of plain dots this draws a coherent "soul" effect:</p>
     * <ul>
     *     <li>two counter-rotating ribbons of glowing orbs around the body,</li>
     *     <li>each orb leaves a short fading tail, so the swarm reads as motion,</li>
     *     <li>a soft teardrop flame that rises from the feet to the chest,</li>
     *     <li>a breathing pedestal ring at the feet that anchors the whole effect,</li>
     *     <li>and a hurt flash that pushes the palette towards red.</li>
     * </ul>
     */
    private void renderSouls(DrawContext context, Box box, float time, int color, float sizeScale) {
        float t = time * speed.getFloat();
        if (soulsSpin.get()) {
            t += soulsSpinPhase;
        }

        Vec3d center = box.getCenter();
        Box entityBox = box;
        Vec3d eyes = Velora.mc().player != null ? Velora.mc().player.getEyePos() : center;

        float hurt = soulsHurt;
        int from = ColorUtil.lerp(color, 0xFFFF4D4D, hurt * 0.65f);
        int to = ColorUtil.lerp(HudStyle.accentSecondary(), 0xFFFF8080, hurt * 0.65f);
        int hot = ColorUtil.lerp(0xFFFFFFFF, 0xFFFFD8D8, hurt);

        double width = Math.max(entityBox.getLengthX(), entityBox.getLengthZ());
        double orbit = Math.max(0.62, width * 0.80) * sizeScale * soulsOrbit.getFloat();
        double vertical = entityBox.getLengthY() * 0.34;
        double bottom = entityBox.minY + 0.06;
        double top = entityBox.maxY - 0.12;

        // ------------------------------------------------- pedestal
        if (soulsPedestal.get()) {
            float[] feet = Projection.project(new Vec3d(center.x, bottom, center.z));
            if (feet != null) {
                float ringRadius = (projectedHalfWidth(entityBox, bottom) * 1.15f + 4f) * sizeScale;
                float breathe = 0.86f + 0.14f * (float) Math.sin(t * 1.7f);
                float rr = ringRadius * breathe;
                Render2D.ringGlow(context, feet[0], feet[1], rr * 1.55f,
                        ColorUtil.withAlpha(from, 26));
                Render2D.ring(context, feet[0], feet[1], rr,
                        ColorUtil.withAlpha(from, Math.round(120 * breathe)));
                Render2D.dottedCircle(context, feet[0], feet[1], rr * 1.22f, 24,
                        Math.max(1.1f, 1.9f * sizeScale),
                        ColorUtil.withAlpha(to, 150));
            }
        }

        // ------------------------------------------------- rising flame
        if (soulsFlame.get()) {
            int tongues = 9;
            for (int i = 0; i < tongues; i++) {
                float frac = i / (float) tongues;
                float phase = frac * MathHelper.TAU;
                float life = (t * 0.42f + frac) % 1f;          // 0 at feet, 1 at chest
                double y = bottom + (top - bottom) * life;
                double wobble = Math.sin(t * 2.6f + phase * 2.1f) * 0.11 * (1f - life);
                double r = orbit * (0.30 + 0.20 * life)
                        + Math.sin(t * 1.9f + phase) * 0.05;
                Vec3d flame = new Vec3d(
                        center.x + Math.cos(phase + t * 0.8f) * r + wobble,
                        y,
                        center.z + Math.sin(phase + t * 0.8f) * r + wobble);
                float[] screen = Projection.project(flame);
                if (screen == null) {
                    continue;
                }
                float dist = (float) Math.max(0.6, eyes.distanceTo(flame));
                float scale = MathHelper.clamp(78f / dist, 1.6f, 6.4f) * sizeScale;
                // The flame narrows as it rises, then vanishes into the chest.
                float taper = 1f - life * 0.75f;
                int magical = ColorUtil.lerp(from, hot, life * 0.5f);
                Render2D.circle(context, screen[0], screen[1], scale * 6.2f * taper,
                        ColorUtil.withAlpha(magical, Math.round(20 * taper)));
                Render2D.circle(context, screen[0], screen[1], scale * 2.9f * taper,
                        ColorUtil.withAlpha(magical, Math.round(74 * taper)));
                Render2D.circle(context, screen[0], screen[1], scale * 1.15f * taper,
                        ColorUtil.withAlpha(hot, Math.round(200 * taper)));
            }
        }

        // ------------------------------------------------- orbiting souls
        int souls = Math.max(4, Math.min(24, soulsCount.getInt()));
        int trail = Math.max(0, Math.min(6, soulsTrail.getInt()));
        double baseY = entityBox.minY + entityBox.getLengthY() * 0.52;

        for (int i = 0; i < souls; i++) {
            boolean upperRing = (i & 1) == 0;
            int ringIndex = i / 2;
            int perRing = Math.max(1, (souls + 1) / 2);
            double dir = upperRing ? 1.0 : -1.0;
            double baseAngle = ringIndex * MathHelper.TAU / perRing;
            double ringRadius = upperRing ? orbit : orbit * 0.72;
            double ringY = upperRing ? baseY + vertical * 0.55 : baseY - vertical * 0.35;

            for (int s = trail; s >= 0; s--) {
                float lag = s * 0.055f;
                double angle = baseAngle + dir * (t * (upperRing ? 1.55 : 1.15)) - dir * lag;
                double breathe = 1.0 + 0.14 * Math.sin(t * 1.6 + ringIndex);
                double r = ringRadius * breathe * (0.88 + 0.12 * Math.sin(t * 1.2 + i));
                double bob = Math.sin(t * 2.1 + i * 1.31) * vertical * 0.30;
                Vec3d point = new Vec3d(
                        center.x + Math.cos(angle) * r,
                        ringY + bob,
                        center.z + Math.sin(angle) * r);

                float[] screen = Projection.project(point);
                if (screen == null) {
                    continue;
                }

                float dist = (float) Math.max(0.6, eyes.distanceTo(point));
                float core = MathHelper.clamp(92f / dist, 1.5f, 6.8f) * sizeScale;
                if (s <= 1) {
                    core *= 1.35f;
                }
                float fade = 1f - s / (float) (trail + 1);
                float pulse = 0.78f + 0.22f * (float) Math.sin(t * 3.1f + i * 1.7f);
                int tint = ColorUtil.lerp(from, to,
                        0.5f + 0.5f * (float) Math.sin(baseAngle + t * 0.7));

                int glowAlpha = Math.round(30 * fade * pulse);
                if (glowAlpha > 3) {
                    Render2D.circle(context, screen[0], screen[1], core * 7.0f * fade,
                            ColorUtil.withAlpha(tint, glowAlpha));
                    Render2D.circle(context, screen[0], screen[1], core * 3.2f * fade,
                            ColorUtil.withAlpha(tint, Math.round(86 * fade * pulse)));
                }
                Render2D.circle(context, screen[0], screen[1], core * 1.6f * fade,
                        ColorUtil.withAlpha(tint, Math.round(180 * fade)));
                Render2D.circle(context, screen[0], screen[1], core * fade,
                        ColorUtil.withAlpha(ColorUtil.lerp(tint, hot, 0.55f), Math.round(255 * fade)));
            }
        }
    }

    /** @return the packed colour of the current theme, saturating 0xFF alpha. */
    private static int themeAccent() {
        return Velora.get().getConfigManager().getAccentColor() | 0xFF000000;
    }

    /** The ESP colour: the theme accent when "Theme color" is on, else the custom colour. */
    private int espColor() {
        return themeColor.get() ? themeAccent() : color.getArgb();
    }

    private LightningEspColor lightningColor() {
        return LightningEspColor.fromArgb(espColor());
    }

    // --------------------------------------------------------------- lightning

    /**
     * Lightning mode: three flickering jagged arcs coil around the target,
     * drawn as real additive 3D lines with a two-pass depth trick — the normal
     * pass shows only the visible parts of the bolts, and a GREATER-depth
     * "x-ray" pass reveals the hidden parts while the target is behind a wall
     * (only when "Through walls" is on).
     *
     * <p>The geometry and timing are a 1:1 port of the lightning Target ESP:
     * a 350ms ease-in-out fade with a sticky target, hurt-flash colour mix and
     * hashed midpoint-displacement jitter that re-crawls 6 times per second.</p>
     */
    @Override
    public void onRenderWorld(MatrixStack matrices, Camera camera) {
        if (!mode.is("Lightning")) {
            return;
        }
        if (mc.player == null || mc.world == null || mc.gameRenderer == null) {
            return;
        }

        // Same target selection as the 2D modes so visuals always agree.
        LivingEntity target = TargetUtil.findBest(range.get(), 360.0, players.get(), mobs.get(), throughWalls.get());
        lightningState.updateTarget(target);
        lightningState.tick(target != null, boltSpeed.getFloat());

        LivingEntity drawTarget = lightningState.getLastTarget();
        float progress = lightningState.getProgress();
        if (drawTarget == null || progress == 0.0F) {
            return;
        }

        LightningEspColor base = lightningColor();
        float time = lightningState.getTime();

        // Pass 1 — LEQUAL depth test: the visible parts of the bolts.
        drawLightningPass(matrices, drawTarget, base, progress, time, 1.0F, false);

        // Pass 2 — GREATER depth test: the parts hidden behind geometry. Only
        // runs while the target is occluded, so the bolts glow through walls
        // without permanently drawing the hidden silhouette.
        if (throughWalls.get()) {
            lightningState.setOccluded(isOccluded(drawTarget, camera));
            float xray = lightningState.getOcclusion();
            if (xray > 0.01F) {
                drawLightningPass(matrices, drawTarget, base, progress, time, xray, true);
            }
        } else {
            lightningState.setOccluded(false);
        }
    }

    /** One geometry build + submit. All pipeline state lives in {@link LightningLinesPipeline}. */
    private void drawLightningPass(MatrixStack matrices, LivingEntity entity, LightningEspColor base,
                                   float progress, float time, float alphaScale, boolean xray) {
        BufferBuilder buffer = LightningLinesPipeline.beginLines();
        LightningBoltGeometry.build(matrices, buffer, entity, base, progress, time, alphaScale);

        BuiltBuffer built = buffer.endNullable();
        if (built == null) {
            return;
        }
        if (xray) {
            LightningLinesPipeline.drawXrayLines(built);
        } else {
            LightningLinesPipeline.drawLines(built);
        }
    }

    /** {@code true} when the bolt target is behind walls or in water. */
    private boolean isOccluded(LivingEntity entity, Camera camera) {
        if (entity.isTouchingWater()) {
            return true;
        }
        return mc.world.raycast(new RaycastContext(
            camera.getCameraPos(),
            entity.getEyePos(),
            RaycastContext.ShapeType.COLLIDER,
            RaycastContext.FluidHandling.NONE,
            mc.player
        )).getType() != HitResult.Type.MISS;
    }

    @Override
    public void onDisable() {
        lightningState.clearTarget();
    }

    // -------------------------------------------------------------- projection

    @Override
    public String getSuffix() {
        return mode.getValue();
    }
}
