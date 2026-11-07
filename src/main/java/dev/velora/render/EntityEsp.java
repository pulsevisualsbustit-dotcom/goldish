package dev.velora.render;

import dev.velora.Velora;
import dev.velora.module.Category;
import dev.velora.module.Module;
import dev.velora.setting.BooleanSetting;
import dev.velora.setting.ColorSetting;
import dev.velora.setting.ModeSetting;
import dev.velora.setting.NumberSetting;
import dev.velora.util.MathUtil;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;

/**
 * Entity ESP.
 *
 * <p>Modes:</p>
 * <ul>
 *     <li><b>Box</b> — a true 3D wireframe around the hitbox (all 12 edges).</li>
 *     <li><b>Chams</b> — the visible box faces are filled with a translucent
 *         colour, which reads like a chams overlay while remaining a 2D pass.</li>
 *     <li><b>Corners</b> — corner brackets around the projected hitbox.</li>
 * </ul>
 *
 * <p>Because the drawing happens in the HUD pass, "Through walls" works
 * naturally and the overlay never fights with the depth buffer.</p>
 */
public class EntityEsp extends Module {

    private static final int[][] EDGES = {
            {0, 1}, {0, 2}, {1, 3}, {2, 3},
            {4, 5}, {4, 6}, {5, 7}, {6, 7},
            {0, 4}, {1, 5}, {2, 6}, {3, 7}
    };

    /** Faces: four corner indices + their outward normal. */
    private static final int[][][] FACES = {
            {{0, 1, 3, 2}, {0, 0, -1}},
            {{4, 6, 7, 5}, {0, 0, 1}},
            {{0, 2, 6, 4}, {-1, 0, 0}},
            {{1, 5, 7, 3}, {1, 0, 0}},
            {{0, 4, 5, 1}, {0, -1, 0}},
            {{2, 3, 7, 6}, {0, 1, 0}}
    };

    private final ModeSetting mode = register(new ModeSetting("Mode", "Box", "Box", "Chams", "Corners"));
    private final ColorSetting color = register(new ColorSetting("Color", 0xFF4ADEDE));
    private final NumberSetting range = register(new NumberSetting("Range", 48.0, 8.0, 128.0, 1.0));
    private final NumberSetting lineWidth = register(new NumberSetting("Line Width", 1.6, 0.5, 4.0, 0.1));
    private final NumberSetting fillAlpha = register(new NumberSetting("Fill Alpha", 32.0, 0.0, 140.0, 1.0));
    private final BooleanSetting players = register(new BooleanSetting("Players", true));
    private final BooleanSetting mobs = register(new BooleanSetting("Mobs", false));
    private final BooleanSetting throughWalls = register(new BooleanSetting("Through walls", true));

    private final VisibilityCache visibility = new VisibilityCache();

    public EntityEsp() {
        super("ESP", "3D box, chams or corner frame around entities", Category.RENDER);
    }

    @Override
    public void onRender2D(DrawContext context, float tickDelta) {
        var client = Velora.mc();
        if (client.player == null || client.world == null) {
            return;
        }

        Entity cameraEntity = client.getCameraEntity();
        for (Entity entity : client.world.getEntities()) {
            if (!(entity instanceof LivingEntity living) || living == cameraEntity || living == client.player) {
                continue;
            }
            if (!matches(living)) {
                continue;
            }
            double distance = client.player.getEyePos().distanceTo(living.getBoundingBox().getCenter());
            if (distance > range.get()) {
                continue;
            }
            if (!throughWalls.get() && !visibility.isVisible(living)) {
                continue;
            }

            int entityColor = colorFor(living);
            Box box = Projection.interpolatedBox(living, tickDelta);
            float[][] corners = Projection.projectCorners(box);
            if (countVisible(corners) < 2) {
                continue;
            }

            if (mode.is("Corners")) {
                renderCorners(context, corners, entityColor);
            } else {
                if (mode.is("Chams")) {
                    renderFaces(context, corners, box, entityColor);
                }
                renderBox(context, corners, entityColor);
            }
        }
    }

    private boolean matches(LivingEntity living) {
        // NOTE: invisible players are no longer skipped — the ESP keeps showing
        // their box and info (spectators stay hidden).
        if (!living.isAlive()) {
            return false;
        }
        if (living instanceof PlayerEntity player) {
            return players.get() && !player.isSpectator();
        }
        return living instanceof MobEntity && mobs.get();
    }

    private int colorFor(LivingEntity living) {
        if (living instanceof PlayerEntity player && Velora.get().getFriendManager().isFriend(player)) {
            return 0xFF4ADE80;
        }
        return color.getArgb();
    }

    private static int countVisible(float[][] corners) {
        int count = 0;
        for (float[] corner : corners) {
            if (corner != null) {
                count++;
            }
        }
        return count;
    }

    // ------------------------------------------------------------------- box

    private void renderBox(DrawContext context, float[][] corners, int baseColor) {
        float width = lineWidth.getFloat();
        int glow = ColorUtil.withAlpha(baseColor, 40);
        int main = ColorUtil.withAlpha(baseColor, 230);

        for (int[] edge : EDGES) {
            float[] a = corners[edge[0]];
            float[] b = corners[edge[1]];
            if (a == null || b == null) {
                continue;
            }
            Render2D.line(context, a[0], a[1], b[0], b[1], width + 2.5f, glow);
            Render2D.line(context, a[0], a[1], b[0], b[1], width, main);
        }
    }

    // ----------------------------------------------------------------- chams

    private void renderFaces(DrawContext context, float[][] corners, Box box, int baseColor) {
        var client = Velora.mc();
        Vec3d camera = client.gameRenderer.getCamera().getCameraPos();
        int alpha = fillAlpha.getInt();
        if (alpha <= 0) {
            return;
        }

        for (int[][] face : FACES) {
            int[] indices = face[0];
            int[] normal = face[1];

            Vec3d faceCenter = new Vec3d(
                    (corner(box, indices[0]).x + corner(box, indices[2]).x) * 0.5,
                    (corner(box, indices[0]).y + corner(box, indices[2]).y) * 0.5,
                    (corner(box, indices[0]).z + corner(box, indices[2]).z) * 0.5
            );
            Vec3d toCamera = camera.subtract(faceCenter);
            double facing = toCamera.x * normal[0] + toCamera.y * normal[1] + toCamera.z * normal[2];
            if (facing <= 0.0) {
                continue;
            }

            float[] a = corners[indices[0]];
            float[] b = corners[indices[1]];
            float[] c = corners[indices[2]];
            float[] d = corners[indices[3]];
            if (a == null || b == null || c == null || d == null) {
                continue;
            }
            Render2D.quad(context, a[0], a[1], b[0], b[1], c[0], c[1], d[0], d[1],
                    ColorUtil.withAlpha(baseColor, alpha));
        }
    }

    private static Vec3d corner(Box box, int index) {
        return new Vec3d(
                (index & 1) == 0 ? box.minX : box.maxX,
                (index & 2) == 0 ? box.minY : box.maxY,
                (index & 4) == 0 ? box.minZ : box.maxZ
        );
    }

    // ---------------------------------------------------------------- corners

    private void renderCorners(DrawContext context, float[][] corners, int baseColor) {
        float minX = Float.MAX_VALUE;
        float minY = Float.MAX_VALUE;
        float maxX = -Float.MAX_VALUE;
        float maxY = -Float.MAX_VALUE;
        int visible = 0;

        for (float[] corner : corners) {
            if (corner == null) {
                continue;
            }
            visible++;
            minX = Math.min(minX, corner[0]);
            maxX = Math.max(maxX, corner[0]);
            minY = Math.min(minY, corner[1]);
            maxY = Math.max(maxY, corner[1]);
        }
        if (visible < 2) {
            return;
        }

        float width = maxX - minX;
        float height = maxY - minY;
        if (width < 4f || height < 4f) {
            return;
        }

        float horizontalArm = Math.min(width * 0.30f, 22f);
        float verticalArm = Math.min(height * 0.30f, 30f);
        float thickness = MathUtil.clamp(lineWidth.getFloat(), 1f, 3f);
        int glow = ColorUtil.withAlpha(baseColor, 45);
        int main = ColorUtil.withAlpha(baseColor, 230);

        drawCorners(context, minX - 1, minY - 1, width + 2, height + 2,
                horizontalArm + 1, verticalArm + 1, thickness + 1.5f, glow);
        drawCorners(context, minX, minY, width, height,
                horizontalArm, verticalArm, thickness, main);
    }

    private static void drawCorners(DrawContext context, float x, float y, float width, float height,
                                    float horizontalArm, float verticalArm, float thickness, int color) {
        Render2D.rect(context, x, y, horizontalArm, thickness, color);
        Render2D.rect(context, x, y, thickness, verticalArm, color);

        Render2D.rect(context, x + width - horizontalArm, y, horizontalArm, thickness, color);
        Render2D.rect(context, x + width - thickness, y, thickness, verticalArm, color);

        Render2D.rect(context, x, y + height - thickness, horizontalArm, thickness, color);
        Render2D.rect(context, x, y + height - verticalArm, thickness, verticalArm, color);

        Render2D.rect(context, x + width - horizontalArm, y + height - thickness, horizontalArm, thickness, color);
        Render2D.rect(context, x + width - thickness, y + height - verticalArm, thickness, verticalArm, color);
    }

    @Override
    public String getSuffix() {
        return mode.getValue();
    }
}
