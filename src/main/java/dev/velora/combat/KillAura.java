package dev.velora.combat;

import dev.velora.combat.trigger.AttackCooldown;
import dev.velora.combat.trigger.TargetType;
import dev.velora.module.Category;
import dev.velora.module.Module;
import dev.velora.setting.BooleanSetting;
import dev.velora.setting.ModeSetting;
import dev.velora.setting.MultiBooleanSetting;
import dev.velora.setting.NumberSetting;
import dev.velora.util.HumanizedRotator;
import dev.velora.util.MathUtil;
import dev.velora.util.PlayerUtil;
import dev.velora.util.RotationUtil;
import dev.velora.util.RotationUtil.Rotation;
import dev.velora.util.ServerSprint;
import dev.velora.util.TargetUtil;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.Hand;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

/**
 * KillAura tuned for the Shard2 and Sloth anticheats.
 *
 * <p>360 degrees and hits through blocks: the target search ignores the view
 * direction (FOV 360) and line of sight, the aim is steered by the profile
 * picked in the Rotations setting, and the hit is only sent after the server
 * has seen the crosshair on the hitbox for an unbroken run of packets.</p>
 *
 * <p>Rotations: Sloth - the strict profile named after the Sloth anticheat.
 * Humanized rotations live only in the movement packets while the camera
 * stays untouched: every step is GCD snapped and flick capped, the aim
 * respects the movement cone (a moving player cannot look wherever - the
 * packet yaw stays within a strafe-like angle of the velocity direction,
 * which is exactly what movement-consistency checks measure), the rotation
 * settles for three on-target packets before the hit, micro-pauses imitate a
 * hand re-checking the target, and after the fight the rotation parks for a
 * moment before walking home. Silent - the same virtual server rotation
 * without the strict extras: the cone clamp is shared, the longer settle,
 * the micro-pauses and the park are not. Legit - the humanized rotator
 * visibly turns the camera, GCD snapped, flick capped, overshoot and
 * settle.</p>
 *
 * <p>Packet order matches vanilla: the attack leaves from the head of
 * ClientPlayerEntity.sendMovementPackets - the place the vanilla client sends
 * its input actions from - so the server validates the hit against the
 * rotation of the previous tick's movement packet. In the Silent and Sloth
 * modes the server rotation is virtual: it is written into the player's yaw/pitch right before
 * the movement packet goes out and restored right after it (see
 * {@link #onPostMovement()}), so only the packet ever sees it. In Legit mode
 * the rotation is the real client rotation, visible on the screen and in the
 * packets.</p>
 *
 * <p>Multi-point hits: the aim point is re-rolled inside the target hitbox
 * after every hit and periodically while tracking, so consecutive hits land
 * on different points of the box instead of converging on one fixed spot.</p>
 *
 * <p>Crit control (the Crits setting): wait for the vanilla falling window,
 * release the sprint through the input so the hit crits, and jump for the
 * window on flat ground - everything through vanilla-consistent input, no
 * packet forgeries.</p>
 */
public class KillAura extends Module {

    /** Safety margin against server-side reach checks (eye to hitbox). */
    private static final double REACH_MARGIN = 0.1;
    /** I-frame floor: the target is invulnerable for 10 ticks after a hit. */
    private static final int MIN_HIT_INTERVAL_MS = 500;
    private static final int MAX_HIT_INTERVAL_MS = 2000;
    /** Give up on the sprint stop and hit without the crit after this many ticks. */
    private static final int MAX_SPRINT_RELEASE_TICKS = 5;
    /**
     * Finishing-hit margin: the raw attack attribute overestimates the real
     * damage (armor, resistance), so a hit only counts as finishing when the
     * target sits below this share of it.
     */
    private static final float FINISH_DAMAGE_MARGIN = 0.75F;

    /**
     * Sloth profile: chance per on-target tick that the rotation holds for one
     * packet - a hand re-checking the target stops moving the mouse for a
     * moment, and the server sees no rotation packet at all on that tick.
     */
    private static final float SLOTH_MICRO_PAUSE_CHANCE = 0.05F;

    /**
     * Horizontal speed in blocks per tick below which the movement-cone clamp
     * releases: standing still, sneaking around or moving straight up (crit
     * jumps) leaves the full 360-degree aim freedom.
     */
    private static final double MOVEMENT_EPSILON = 0.05;

    private final MultiBooleanSetting targets = register(new MultiBooleanSetting("Targets",
            "Типы сущностей, которых киллкаура считает врагами")
            .add(TargetType.PLAYER.getTargetName(), true)
            .add(TargetType.MOB.getTargetName(), false)
            .add(TargetType.ANIMAL.getTargetName(), false)
            .add(TargetType.STAND.getTargetName(), false));

    private final NumberSetting range = register(new NumberSetting("Range",
            "Дистанция удара от глаз до хитбокса (10 см вычитается как запас)",
            3.0, 2.0, 4.0, 0.05));

    private final NumberSetting fov = register(new NumberSetting("FOV",
            "Сектор поиска цели в градусах, 360 - во все стороны, включая спину",
            360, 30, 360, 15));

    private final BooleanSetting throughWalls = register(new BooleanSetting("Through walls",
            "Бить цели за блоками, без проверки прямой видимости", true));

    private final ModeSetting priority = register(new ModeSetting("Priority",
            "Какую цель выбирать первой", "Angle", "Angle", "Distance", "Health"));

    private final ModeSetting rotations = register(new ModeSetting("Rotations",
            "Режим наведения: Sloth - под Sloth AC, наводит только в пакетах движения и не шире конуса бега; "
                    + "Silent - наводит только в пакетах, камеру не трогает; "
                    + "Legit - камера видимо поворачивается на цель, как рука игрока",
            "Sloth", "Silent", "Legit"));

    private final NumberSetting rotationSpeed = register(new NumberSetting("Rotation speed",
            "Базовая скорость наведения (Silent - за тик, Legit - за кадр)", 10, 1, 15, 0.5));

    private final NumberSetting rotationRandomization = register(new NumberSetting("Rotation randomness",
            "Человеческий разброс скорости и микро-траектории наведения", 0.4, 0, 1, 0.05));

    private final NumberSetting maxFlick = register(new NumberSetting("Max flick",
            "Максимальный доворот за один тик в градусах", 35, 10, 90, 1));

    private final NumberSetting movementCone = register(new NumberSetting("Movement cone",
            "Пока ты бежишь, поворот в пакете не дальше этого угла от направления движения: живой игрок не "
                    + "смотрит себе в спину на бегу, и античит сверяет поворот с вектором движения. "
                    + "0 - ограничение выключено. Стоя на месте и в вертикальном прыжке ограничения нет",
            45, 0, 90, 5)
            .visibleWhen(() -> rotations.is("Silent") || rotations.is("Sloth")));

    private final NumberSetting minCharge = register(new NumberSetting("Min charge",
            "Минимальный заряд оружия для удара (1.0 - полный)", 0.95, 0.5, 1.0, 0.05));

    private final NumberSetting hitDelay = register(new NumberSetting("Hit delay",
            "Дополнительная пауза между ударами в мс", 50, 0, 500, 25));

    private final ModeSetting crits = register(new ModeSetting("Crits",
            "Криты: Off - бить всегда; Smart - крит в падении, прыжок ради крита и мгновенное добивание; Only - только критические удары",
            "Smart", "Off", "Smart", "Only"));

    private final HumanizedRotator rotator = new HumanizedRotator();

    private LivingEntity target;
    /**
     * Unbroken run of on-target movement packets ending with the one the
     * server last saw. The attack gate requires a streak - two packets, three
     * in the Sloth profile - so a hit never rides on a rotation the server
     * has not confirmed yet.
     */
    private int aimStreak;
    /** Until when the Sloth profile parks the rotation after the fight, ms. */
    private long holdHomeUntilMs;
    private int sprintReleaseTicks;
    /** Set while the synthetic one-tick jump press is active (see PlayerUtil#pressJump). */
    private boolean jumpQueued;
    private long lastAttackMs;
    private long attackIntervalMs = MIN_HIT_INTERVAL_MS;
    /** Aim-point re-roll cadence while tracking, milliseconds. */
    private static final long AIM_REROLL_MIN_MS = 350L;
    private static final long AIM_REROLL_SPREAD_MS = 350L;
    /**
     * Offset of the current aim point inside the target hitbox, as fractions
     * of the box dimensions. Re-rolled after every hit and periodically while
     * tracking, so consecutive hits land on different points of the box.
     */
    private float aimOffsetX = 0.5F;
    private float aimOffsetY = 0.6F;
    private float aimOffsetZ = 0.5F;
    private long nextAimRerollMs;
    /**
     * The rotation the server currently believes in: what the last movement
     * packet carried. Silent mode only; the camera never sees it.
     */
    private Rotation serverRotation;
    /**
     * Real camera rotation saved while the silent rotation is written into
     * the player for the packet window. {@link #onPostMovement()} restores it.
     */
    private float realYaw;
    private float realPitch;
    private boolean silentPending;

    public KillAura() {
        super("KillAura",
                "Автоматическая атака: 360, через блоки, ротации под Shard2 и Sloth AC",
                Category.COMBAT);
    }

    @Override
    public boolean ticksBeforeMovement() {
        return true;
    }

    @Override
    public void onEnable() {
        resetState();
    }

    @Override
    public void onDisable() {
        resetState();
    }

    /**
     * Runs from the head of ClientPlayerEntity.sendMovementPackets, before the
     * movement packet of this tick. The server validates the hit against the
     * rotation of the PREVIOUS tick, which is why the aim gate requires an
     * unbroken run of on-target packets before the attack may leave.
     */
    @Override
    public void onTick() {
        ClientPlayerEntity player = mc.player;
        if (player == null || mc.world == null || !player.isAlive()
                || player.isSpectator()) {
            resetState();
            return;
        }
        boolean packetAim = serverRotations();
        if (packetAim) {
            // Remember the real camera rotation: whatever happens below, the
            // tail hook (onPostMovement) puts it back once the packet is out.
            realYaw = player.getYaw();
            realPitch = player.getPitch();
            silentPending = true;
            if (serverRotation == null) {
                serverRotation = new Rotation(realYaw, realPitch);
            }
        }
        if (mc.currentScreen != null || AutoSwap.isFreezing()) {
            // No combat through screens or while AutoSwap holds the input.
            // The packet-aim modes keep walking the server rotation to the
            // camera so the packets never snap back.
            if (!packetAim) {
                resetState();
                return;
            }
            releaseQueuedJump();
            target = null;
            sprintReleaseTicks = 0;
            aimStreak = 0;
            rotator.reset();
            stepServerRotationHome(player);
            return;
        }
        // The synthetic jump press from the previous tick has been consumed by
        // this tick's KeyboardInput.tick: restore the real keyboard states.
        releaseQueuedJump();

        LivingEntity found = findTarget(player);
        if (found != target && isCurrentTargetStillGood(player)) {
            // Sticky target: two equally scored players would otherwise make the
            // selection flap every tick, resetting the rotator before it ever
            // converges. Keep the current one while it is still a legal choice;
            // switching happens when it dies or leaves the reach.
            found = target;
        }
        if (found != target) {
            // New target: reset the rotator so the trajectory shaping
            // (overshoot, settle) starts fresh instead of reusing stale state.
            // The silent server rotation itself never snaps: it keeps stepping
            // from wherever it is towards the new aim.
            rotator.reset();
            target = found;
            rerollAimOffset();
            holdHomeUntilMs = 0L;
        }
        if (target == null) {
            sprintReleaseTicks = 0;
            aimStreak = 0;
            if (packetAim) {
                if (slothProfile() && holdHomeUntilMs == 0L) {
                    // Fight over: a human keeps the crosshair parked on the
                    // last aim for a moment before flicking it back.
                    holdHomeUntilMs = System.currentTimeMillis()
                            + (long) MathUtil.random(250.0, 650.0);
                }
                if (slothProfile() && System.currentTimeMillis() < holdHomeUntilMs) {
                    // Park: the rotation goes out unchanged, so this tick
                    // carries no rotation packet at all.
                    applyServerRotation(player);
                } else {
                    // No target: walk the server rotation back onto the camera.
                    stepServerRotationHome(player);
                }
            }
            return;
        }

        long now = System.currentTimeMillis();
        if (now >= nextAimRerollMs) {
            // While tracking: wander the aim point around the box so the
            // rotation never freezes on one spot.
            rerollAimOffset();
            nextAimRerollMs = now + AIM_REROLL_MIN_MS
                    + (long) MathUtil.random(0.0, AIM_REROLL_SPREAD_MS);
        }
        boolean aimed;
        if (packetAim) {
            // The pre-step server rotation is exactly what the previous packet
            // carried, so this answers "was the last packet on target" - what
            // the server validates the attack of this tick against.
            aimed = isAimed(player, target, serverRotation);
            Rotation aim = clampAimToMovement(player,
                    RotationUtil.look(player.getEyePos(), aimPoint(target)));
            if (slothProfile() && aimed && MathUtil.chance(SLOTH_MICRO_PAUSE_CHANCE)) {
                // Micro-pause: a hand re-checking the target stops moving the
                // mouse for one packet, so no rotation packet leaves at all;
                // the trajectory shaping restarts right after, like a real
                // settle would.
                rotator.reset();
            } else {
                serverRotation = rotator.stepRotation(serverRotation, aim,
                        (float) rotationSpeed.get(),
                        (float) rotationRandomization.get(),
                        1.0F, true,
                        (float) maxFlick.get());
            }
            // Hand the stepped rotation to the movement packet of this tick.
            applyServerRotation(player);
        } else {
            aimed = isAimed(player, target);
        }
        if (aimed) {
            aimStreak++;
        } else {
            aimStreak = 0;
        }
        // The hit may only leave once the server has confirmed the aim: an
        // unbroken run of on-target packets, one packet longer in the Sloth
        // profile so the rotation visibly settles first.
        boolean serverHasAim = aimStreak >= (slothProfile() ? 3 : 2);
        if (!aimed || !serverHasAim || player.isUsingItem()
                || player.getAttackCooldownProgress(0.5F) < minCharge.get()
                || now - lastAttackMs < attackIntervalMs
                || !critGate(player, target)) {
            // Not on target, eating, not charged or on cooldown: keep aiming,
            // the gates retry next tick.
            return;
        }
        attack(player, now);
    }

    /**
     * Per-frame camera rotation, Legit mode only: the wrist-flick profile of
     * the {@link HumanizedRotator} (ease-out, overshoot and settle, tremor,
     * GCD grid, flick cap) visibly drags the crosshair onto the current aim
     * point inside the target's hitbox. The rotation it leaves behind is
     * picked up by the next movement packet; the camera itself stays smooth
     * through the vanilla renderYaw/renderPitch interpolation.
     *
     * <p>The packet-aim modes (Silent, Sloth) never touch the camera: their
     * rotation steps happen once per tick in {@link #onTick()}, directly on
     * the server rotation.</p>
     */
    @Override
    public void onFrame(float deltaTicks) {
        if (serverRotations()) {
            return;
        }
        ClientPlayerEntity player = mc.player;
        if (player == null || mc.currentScreen != null || AutoSwap.isFreezing()) {
            return;
        }
        LivingEntity current = target;
        if (current == null || !current.isAlive()) {
            return;
        }
        Rotation aim = RotationUtil.look(player.getEyePos(), aimPoint(current));
        rotator.step(player, aim,
                (float) rotationSpeed.get(),
                (float) rotationRandomization.get(),
                deltaTicks, true,
                (float) maxFlick.get());
    }

    /**
     * Tail of ClientPlayerEntity.sendMovementPackets: the movement packet of
     * this tick is out. While the silent rotation is active the camera
     * rotation was swapped for the server one at the head; the real one goes
     * back on here, before the physics of the next tick or any frame can
     * observe it.
     */
    @Override
    public void onPostMovement() {
        if (!silentPending) {
            return;
        }
        silentPending = false;
        ClientPlayerEntity player = mc.player;
        if (player != null) {
            player.setYaw(realYaw);
            player.setPitch(realPitch);
        }
    }

    /**
     * Whether the current target is still a legal choice (alive, valid type,
     * in reach, inside the FOV cone). Used to keep the aim locked instead of
     * flapping between two similar targets.
     */
    private boolean isCurrentTargetStillGood(ClientPlayerEntity player) {
        LivingEntity current = target;
        if (current == null || !current.isAlive() || !isValidTarget(current)) {
            return false;
        }
        Vec3d eye = player.getEyePos();
        if (eye.distanceTo(closestPoint(eye, current.getBoundingBox())) > effectiveRange()) {
            return false;
        }
        double halfFov = fov.get() >= 360 ? 180.0 : fov.get() * 0.5;
        if (halfFov < 180.0
                && TargetUtil.angleBetween(player, RotationUtil.look(eye, TargetUtil.aimPoint(current))) > halfFov) {
            return false;
        }
        return true;
    }

    private void attack(ClientPlayerEntity player, long now) {
        lastAttackMs = now;
        attackIntervalMs = nextAttackInterval(player);
        sprintReleaseTicks = 0;
        mc.interactionManager.attackEntity(player, target);
        player.swingHand(Hand.MAIN_HAND);
        // The next hit goes to a different point of the hitbox.
        rerollAimOffset();
    }

    /**
     * Interval for the next hit: dynamic full-charge time of the current weapon
     * plus the configured extra delay, with a little human jitter on top.
     */
    private long nextAttackInterval(ClientPlayerEntity player) {
        long base = AttackCooldown.interval(player, 1.05,
                (int) hitDelay.get(), MIN_HIT_INTERVAL_MS, MAX_HIT_INTERVAL_MS);
        return Math.max(MIN_HIT_INTERVAL_MS / 2,
                Math.round(base * (1.0 + MathUtil.gaussian(0.0, 0.08))));
    }

    /**
     * Crit gate for the selected Crits mode. Returns true when the hit may
     * leave right now; false means "wait" - either a jump has just been
     * queued or the sprint release is still in progress.
     *
     * <ul>
     *   <li>Off - no crit logic at all.</li>
     *   <li>Smart - a finishing hit (kills without a crit) is sent immediately;
     *       in the falling window the sprint is released for the crit; on flat
     *       ground the aura jumps for the crit; where crits are impossible
     *       (water, ladder, blindness, vehicle) the tempo wins.</li>
     *   <li>Only - the hit waits until it is critical: jump on flat ground,
     *       sprint release in the window, no attack without the crit.</li>
     * </ul>
     */
    private boolean critGate(ClientPlayerEntity player, LivingEntity hitTarget) {
        if (crits.is("Off")) {
            sprintReleaseTicks = 0;
            return true;
        }
        if (crits.is("Smart") && isFinishingHit(player, hitTarget)) {
            // The next hit kills even without a crit: no reason to lose tempo.
            sprintReleaseTicks = 0;
            return true;
        }
        if (PlayerUtil.isFallingForCrit(hitTarget)) {
            return releaseSprintForHit(player);
        }
        sprintReleaseTicks = 0;
        if (!player.isOnGround()) {
            // Rising after a jump: the falling window opens in a few ticks.
            return false;
        }
        if (!PlayerUtil.canCritEver() || !PlayerUtil.canJump()) {
            // Crits are impossible here: Smart keeps attacking, Only keeps waiting.
            return !crits.is("Only");
        }
        PlayerUtil.pressJump();
        jumpQueued = true;
        return false;
    }

    /**
     * Sprint stop for the crit window. A hit only crits when the server thinks
     * we are not sprinting, and the STOP_SPRINTING packet is written by vanilla
     * itself at the head of sendMovementPackets right after this method
     * returns - so the attack waits exactly one tick and lands as a crit.
     */
    private boolean releaseSprintForHit(ClientPlayerEntity player) {
        if (!ServerSprint.isSprinting()) {
            sprintReleaseTicks = 0;
            return true;
        }
        if (sprintReleaseTicks >= MAX_SPRINT_RELEASE_TICKS) {
            // Sprint refuses to stop (lag, other module): hit anyway, no crit.
            return true;
        }
        PlayerUtil.releaseSprintForCrit(player);
        sprintReleaseTicks++;
        return false;
    }

    /**
     * Rough lethal check: the raw attack attribute of the player (weapon and
     * effects, no crit) against the health of the target, padded down because
     * the real damage is cut by armor and resistance.
     */
    private boolean isFinishingHit(ClientPlayerEntity player, LivingEntity hitTarget) {
        float damage = (float) player.getAttributeValue(EntityAttributes.ATTACK_DAMAGE);
        return hitTarget.getHealth() <= damage * FINISH_DAMAGE_MARGIN;
    }

    /** Ends the synthetic one-tick jump press (see PlayerUtil#pressJump). */
    private void releaseQueuedJump() {
        if (jumpQueued) {
            jumpQueued = false;
            KeyBinding.updatePressedStates();
        }
    }

    /**
     * Target search: FOV up to 360 degrees (no direction filter at all), range
     * measured from the eye to the nearest point of the hitbox, line of sight
     * optional. The score picks the target of the highest priority.
     */
    private LivingEntity findTarget(ClientPlayerEntity player) {
        Vec3d eye = player.getEyePos();
        double maxRange = effectiveRange();
        double halfFov = fov.get() >= 360 ? 180.0 : fov.get() * 0.5;
        boolean requireSight = !throughWalls.get();
        LivingEntity best = null;
        double bestScore = Double.MAX_VALUE;
        for (Entity entity : mc.world.getEntities()) {
            if (!(entity instanceof LivingEntity living) || living == player) {
                continue;
            }
            if (!isValidTarget(living)) {
                continue;
            }
            Vec3d aimPoint = TargetUtil.aimPoint(living);
            double distance = eye.distanceTo(closestPoint(eye, living.getBoundingBox()));
            if (distance > maxRange) {
                continue;
            }
            Rotation aim = RotationUtil.look(eye, aimPoint);
            float angle = TargetUtil.angleBetween(player, aim);
            if (angle > halfFov) {
                continue;
            }
            if (requireSight && !PlayerUtil.canSeePoint(eye, aimPoint)) {
                continue;
            }
            double score;
            if (priority.is("Distance")) {
                score = distance;
            } else if (priority.is("Health")) {
                score = living.getHealth() + distance * 0.1;
            } else {
                score = angle + distance * 0.05;
            }
            if (score < bestScore) {
                bestScore = score;
                best = living;
            }
        }
        return best;
    }

    private boolean isValidTarget(LivingEntity living) {
        if (!living.isAlive() || !living.isAttackable() || PlayerUtil.isFriend(living)) {
            return false;
        }
        boolean allowed = false;
        for (TargetType type : TargetType.values()) {
            if (type.matches(living) && targets.isSelected(type.getTargetName())) {
                allowed = true;
                break;
            }
        }
        if (!allowed) {
            return false;
        }
        if (living instanceof PlayerEntity other) {
            return !other.isCreative() && !other.isSpectator();
        }
        return true;
    }

    /**
     * Whether the current look ray actually hits the target hitbox within the
     * effective reach - the geometric requirement any server-side check
     * (vanilla reach, Shard2 ray-on-hitbox) would make.
     */
    private boolean isAimed(ClientPlayerEntity player, LivingEntity aimTarget) {
        return isAimed(player, aimTarget, new Rotation(player.getYaw(), player.getPitch()));
    }

    /**
     * Same ray test for an arbitrary rotation: the camera one (Legit) or the
     * silent server rotation (what the last movement packet carried).
     */
    private boolean isAimed(ClientPlayerEntity player, LivingEntity aimTarget, Rotation view) {
        Vec3d eye = player.getEyePos();
        Vec3d direction = Vec3d.fromPolar(view.pitch(), view.yaw());
        Vec3d end = eye.add(direction.multiply(effectiveRange()));
        return aimTarget.getBoundingBox().raycast(eye, end).isPresent();
    }

    /**
     * Picks a fresh aim point offset inside the hitbox: uniform over the safe
     * interior in X/Z, biased to the torso in Y so the sweep stays on the body
     * instead of the extreme edges of the box.
     */
    private void rerollAimOffset() {
        aimOffsetX = (float) MathUtil.random(0.15, 0.85);
        aimOffsetZ = (float) MathUtil.random(0.15, 0.85);
        aimOffsetY = (float) MathUtil.clamp(0.5 + MathUtil.gaussian(0.0, 0.18), 0.18, 0.82);
    }

    /**
     * The current aim point: the target's hitbox offset by the re-rolled
     * fractions. The offset is relative, so the point follows a moving
     * target around the box without snapping.
     */
    private Vec3d aimPoint(LivingEntity aimTarget) {
        Box box = aimTarget.getBoundingBox();
        return new Vec3d(
                box.minX + (box.maxX - box.minX) * aimOffsetX,
                box.minY + (box.maxY - box.minY) * aimOffsetY,
                box.minZ + (box.maxZ - box.minZ) * aimOffsetZ);
    }

    /** Writes the silent rotation into the player for the packet window. */
    private void applyServerRotation(ClientPlayerEntity player) {
        player.setYaw(serverRotation.yaw());
        player.setPitch(serverRotation.pitch());
    }

    /** Whether the current mode steers a virtual server rotation (Silent / Sloth). */
    private boolean serverRotations() {
        return rotations.is("Silent") || rotations.is("Sloth");
    }

    /** The strict human profile tuned to pass the Sloth anticheat. */
    private boolean slothProfile() {
        return rotations.is("Sloth");
    }

    /**
     * Movement consistency clamp, shared by the Silent and Sloth profiles.
     *
     * <p>While the player is actually moving horizontally, a real crosshair
     * can only sit so far off the movement direction: vanilla derives the
     * velocity from the view rotation plus the WASD input, so an anticheat
     * that compares the packet yaw with the direction of the position deltas
     * flags everything beyond a strafe-like offset. The clamp parks the aim
     * on the cone edge instead of rotating past it, so the packets stay inside
     * what a moving player can produce. Standing still, sneaking or moving
     * straight up (crit jumps) leaves the full 360-degree freedom.</p>
     */
    private Rotation clampAimToMovement(ClientPlayerEntity player, Rotation aim) {
        double cone = movementCone.get();
        if (cone <= 0.0 || cone >= 180.0) {
            return aim;
        }
        Vec3d velocity = player.getVelocity();
        double horizontal = Math.sqrt(velocity.x * velocity.x + velocity.z * velocity.z);
        if (horizontal < MOVEMENT_EPSILON) {
            return aim;
        }
        // Yaw convention: look vector = (-sin(yaw), cos(yaw)), so the yaw of a
        // velocity vector follows from atan2(-x, z).
        float movementYaw = (float) Math.toDegrees(Math.atan2(-velocity.x, velocity.z));
        float offset = MathUtil.angleDifference(aim.yaw(), movementYaw);
        float limit = (float) cone;
        if (Math.abs(offset) <= limit) {
            return aim;
        }
        return new Rotation(
                MathUtil.wrapDegrees(movementYaw + Math.signum(offset) * limit),
                aim.pitch());
    }

    /**
     * Packet-aim modes with no target: walk the server rotation back onto the
     * real camera rotation with the same humanized steps - GCD-snapped and
     * flick-capped, like a player flicking the crosshair back after the fight.
     * Once it parks inside the rotator's dead zone it just tracks the camera:
     * real mouse moves are mirrored as GCD deltas, sub-dead-zone drift sends
     * no rotation packet at all.
     */
    private void stepServerRotationHome(ClientPlayerEntity player) {
        Rotation home = new Rotation(player.getYaw(), player.getPitch());
        serverRotation = rotator.stepRotation(serverRotation, home,
                (float) rotationSpeed.get(),
                (float) rotationRandomization.get(),
                1.0F, true,
                (float) maxFlick.get());
        applyServerRotation(player);
    }

    private double effectiveRange() {
        return Math.max(1.0, range.get() - REACH_MARGIN);
    }

    private static Vec3d closestPoint(Vec3d point, Box box) {
        return new Vec3d(
                MathHelper.clamp(point.x, box.minX, box.maxX),
                MathHelper.clamp(point.y, box.minY, box.maxY),
                MathHelper.clamp(point.z, box.minZ, box.maxZ));
    }

    private void resetState() {
        if (silentPending) {
            // Interrupted mid-window (never happens in normal flow - the tail
            // hook runs first): put the camera back before anything renders.
            silentPending = false;
            ClientPlayerEntity player = mc.player;
            if (player != null) {
                player.setYaw(realYaw);
                player.setPitch(realPitch);
            }
        }
        target = null;
        sprintReleaseTicks = 0;
        aimStreak = 0;
        holdHomeUntilMs = 0L;
        rotator.reset();
        releaseQueuedJump();
        // The silent rotation dies here; the next packet carries the real
        // camera rotation again. Toggling the aura off mid-aim therefore
        // produces one snap-back packet - the price of a mid-fight toggle.
        serverRotation = null;
    }

    @Override
    public String getSuffix() {
        double value = fov.get();
        return value >= 360 ? "360" : String.valueOf(Math.round(value));
    }
}
