package dev.velora.render;

import dev.velora.Velora;
import net.minecraft.client.render.Camera;
import net.minecraft.entity.Entity;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;

/**
 * World-to-screen projection for the 2D overlay stack.
 *
 * <p>All ESP-like modules (NameTags, ESP, TargetESP) use this one projector, so
 * their boxes and labels always agree with each other and with the camera.</p>
 */
public final class Projection {

    private Projection() {
    }

    /**
     * Projects a world position into scaled GUI coordinates.
     *
     * @return {@code {x, y}} or {@code null} when the point is behind the camera
     */
    public static float[] project(Vec3d position) {
        var client = Velora.mc();
        if (client == null || client.gameRenderer == null || client.getWindow() == null) {
            return null;
        }
        Camera camera = client.gameRenderer.getCamera();
        Vec3d cameraPos = camera.getCameraPos();
        double x = position.x - cameraPos.x;
        double y = position.y - cameraPos.y;
        double z = position.z - cameraPos.z;

        double yaw = Math.toRadians(camera.getYaw());
        double pitch = Math.toRadians(camera.getPitch());

        // Camera basis (Minecraft: yaw 0 looks towards +Z, positive pitch looks down).
        double rightX = -Math.cos(yaw);
        double rightZ = -Math.sin(yaw);
        double forwardX = -Math.sin(yaw) * Math.cos(pitch);
        double forwardY = -Math.sin(pitch);
        double forwardZ = Math.cos(yaw) * Math.cos(pitch);
        double upX = -rightZ * forwardY;
        double upY = rightZ * forwardX - rightX * forwardZ;
        double upZ = rightX * forwardY;

        double cameraX = x * rightX + z * rightZ;
        double cameraY = x * upX + y * upY + z * upZ;
        double cameraZ = x * forwardX + y * forwardY + z * forwardZ;
        if (cameraZ <= 0.05) {
            return null;
        }

        double fov = Math.toRadians(client.options.getFov().getValue());
        double scale = (client.getWindow().getScaledHeight() / 2.0) / Math.tan(fov / 2.0);
        return new float[]{
                (float) (client.getWindow().getScaledWidth() / 2.0 + cameraX * scale / cameraZ),
                (float) (client.getWindow().getScaledHeight() / 2.0 - cameraY * scale / cameraZ)
        };
    }

    /**
     * Entity hitbox at the interpolated render position. Prevents the 20 Hz
     * entity position from making boxes and name tags visibly stutter at high
     * frame rates.
     */
    public static Box interpolatedBox(Entity entity, float tickDelta) {
        Box box = entity.getBoundingBox();
        try {
            Vec3d lerped = entity.getLerpedPos(tickDelta);
            Vec3d delta = lerped.subtract(entity.getEntityPos());
            if (delta.lengthSquared() > 0.000001) {
                return box.offset(delta);
            }
        } catch (Throwable ignored) {
            // Fall back to the current bounding box.
        }
        return box;
    }

    /** Projects all eight corners of a box. Missing corners are {@code null}. */
    public static float[][] projectCorners(Box box) {
        float[][] corners = new float[8][];
        for (int i = 0; i < 8; i++) {
            double x = (i & 1) == 0 ? box.minX : box.maxX;
            double y = (i & 2) == 0 ? box.minY : box.maxY;
            double z = (i & 4) == 0 ? box.minZ : box.maxZ;
            corners[i] = project(new Vec3d(x, y, z));
        }
        return corners;
    }

    public static boolean isOnScreen(float[] point, float margin) {
        var client = Velora.mc();
        if (point == null || client == null || client.getWindow() == null) {
            return false;
        }
        return point[0] >= -margin
                && point[0] <= client.getWindow().getScaledWidth() + margin
                && point[1] >= -margin
                && point[1] <= client.getWindow().getScaledHeight() + margin;
    }

    /** Distance from the camera to a world point. */
    public static double distanceToCamera(Vec3d position) {
        var client = Velora.mc();
        if (client == null || client.gameRenderer == null) {
            return Double.MAX_VALUE;
        }
        return client.gameRenderer.getCamera().getCameraPos().distanceTo(position);
    }
}
