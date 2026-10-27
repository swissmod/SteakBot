package com.example.steakbot;

import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.LongArgumentType;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ingame.InventoryScreen;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.screen.GenericContainerScreenHandler;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.text.Text;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Random;
import java.util.function.BooleanSupplier;

/**
 * SteakBot v2: takes steaks out of a chest, then lists them on /ah in small batches.
 *
 * Setup:
 *  - Keep hotbar slot 9 (the last one) EMPTY. The bot uses it as the "listing slot".
 *  - Stand within reach of the chest, look at it, run /flipbot chest
 *  - /flipbot price <amount>      price for each listing
 *  - /flipbot count <n>           steaks per listing (default 1)
 *  - /flipbot cooldown <seconds>  minimum gap between /ah sell commands (default 3)
 *  - /flipbot delay <min> <max>   tick delay between actions (default 2-4)
 *  - /flipbot debug               toggle step-by-step chat messages
 *  - /flipbot start | stop
 *
 * v2 changes: turns to face the chest and uses a real raycast to open it, opens the real
 * inventory screen for inventory clicks, adds a cooldown + delay settings, and names every
 * step so a failure tells you exactly where it stopped.
 */
public class SteakBot implements ClientModInitializer {

    // ---- settings ----
    static boolean running = false;
    static boolean verbose = true;
    static long price = 1000;
    static int count = 1;
    static BlockPos chestPos = null;
    static long cooldownMs = 3000;
    static int delayMin = 2, delayMax = 4;

    // ---- constants ----
    static final int WORK_HOTBAR = 8;   // hotbar index 8 = 9th slot
    static final int WORK_SLOT = 44;    // same slot in the player screen handler (36 + 8)
    static final int MAIN_START = 9, MAIN_END = 35;

    // ---- engine ----
    static final Deque<BooleanSupplier> queue = new ArrayDeque<>();
    static final Random rng = new Random();
    static int wait = 0;
    static int stuck = 0;
    static long lastSell = 0;
    static BooleanSupplier lastLogged = null;
    static final int STUCK_LIMIT = 80; // ticks one step may wait before the bot gives up

    /** A step with a name, so logs and timeouts say what was happening. */
    static class Named implements BooleanSupplier {
        final String name;
        final BooleanSupplier fn;
        Named(String name, BooleanSupplier fn) { this.name = name; this.fn = fn; }
        @Override public boolean getAsBoolean() { return fn.getAsBoolean(); }
        @Override public String toString() { return name; }
    }

    static Named step(String name, BooleanSupplier fn) { return new Named(name, fn); }

    @Override
    public void onInitializeClient() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registry) ->
            dispatcher.register(ClientCommandManager.literal("flipbot")
                .then(ClientCommandManager.literal("start").executes(c -> {
                    if (chestPos == null) { say("Set the chest first: look at it and run /flipbot chest"); return 0; }
                    queue.clear(); wait = 0; stuck = 0; lastLogged = null; running = true;
                    say("Started. price=" + price + " count=" + count
                        + " cooldown=" + (cooldownMs / 1000.0) + "s");
                    return 1;
                }))
                .then(ClientCommandManager.literal("stop").executes(c -> { stop("Stopped."); return 1; }))
                .then(ClientCommandManager.literal("debug").executes(c -> {
                    verbose = !verbose;
                    say("Debug messages " + (verbose ? "on" : "off"));
                    return 1;
                }))
                .then(ClientCommandManager.literal("chest").executes(c -> {
                    MinecraftClient mc = MinecraftClient.getInstance();
                    if (mc.crosshairTarget instanceof BlockHitResult b
                            && mc.crosshairTarget.getType() == HitResult.Type.BLOCK) {
                        chestPos = b.getBlockPos();
                        say("Chest set to " + chestPos.toShortString());
                        return 1;
                    }
                    say("Look at the chest first.");
                    return 0;
                }))
                .then(ClientCommandManager.literal("price")
                    .then(ClientCommandManager.argument("amount", LongArgumentType.longArg(1)).executes(c -> {
                        price = LongArgumentType.getLong(c, "amount");
                        say("Price set to " + price);
                        return 1;
                    })))
                .then(ClientCommandManager.literal("count")
                    .then(ClientCommandManager.argument("n", IntegerArgumentType.integer(1, 64)).executes(c -> {
                        count = IntegerArgumentType.getInteger(c, "n");
                        say("Steaks per listing: " + count);
                        return 1;
                    })))
                .then(ClientCommandManager.literal("cooldown")
                    .then(ClientCommandManager.argument("seconds", DoubleArgumentType.doubleArg(0, 120)).executes(c -> {
                        cooldownMs = (long) (DoubleArgumentType.getDouble(c, "seconds") * 1000);
                        say("Gap between /ah sell commands: " + (cooldownMs / 1000.0) + "s");
                        return 1;
                    })))
                .then(ClientCommandManager.literal("delay")
                    .then(ClientCommandManager.argument("min", IntegerArgumentType.integer(1, 100))
                        .then(ClientCommandManager.argument("max", IntegerArgumentType.integer(1, 100)).executes(c -> {
                            int a = IntegerArgumentType.getInteger(c, "min");
                            int b = IntegerArgumentType.getInteger(c, "max");
                            delayMin = Math.min(a, b);
                            delayMax = Math.max(a, b);
                            say("Tick delay between actions: " + delayMin + "-" + delayMax);
                            return 1;
                        }))))
            ));

        ClientTickEvents.END_CLIENT_TICK.register(SteakBot::tick);
    }

    // ---------------------------------------------------------------- engine

    static void tick(MinecraftClient mc) {
        if (!running || mc.player == null || mc.interactionManager == null || mc.world == null) return;
        if (wait > 0) { wait--; return; }

        BooleanSupplier s = queue.peekFirst();
        if (s != null) {
            if (s != lastLogged) {
                lastLogged = s;
                if (verbose) say("> " + s);
            }
            if (s.getAsBoolean()) {
                queue.removeFirstOccurrence(s);
                stuck = 0;
                lastLogged = null;
                wait = delayMin + rng.nextInt(delayMax - delayMin + 1);
            } else if (++stuck > STUCK_LIMIT) {
                stop("Timed out on step: " + s + ". Check the chat above for a server message.");
            }
            return;
        }
        if (mc.currentScreen != null) return; // let any open screen close first
        plan(mc);
    }

    static void plan(MinecraftClient mc) {
        if (findSteak(mc) != -1) queueSellOne(mc);
        else queueFetch(mc);
    }

    // ---------------------------------------------------------------- selling

    static void queueSellOne(MinecraftClient mc) {
        final int[] src = {-1};

        // open the real inventory screen so the clicks look like a normal player's
        queue.add(step("open inventory", () -> {
            if (mc.currentScreen == null) mc.setScreen(new InventoryScreen(mc.player));
            return true;
        }));

        queue.add(step("pick up steak stack", () -> {
            src[0] = findSteak(mc);
            if (src[0] == -1) { queue.clear(); return true; }
            click(mc, src[0], 0);
            return true;
        }));

        for (int i = 0; i < count; i++) {
            final int n = i + 1;
            queue.add(step("place steak " + n + " in listing slot", () -> {
                if (!cursor(mc).isEmpty()) click(mc, WORK_SLOT, 1);
                return true;
            }));
        }

        queue.add(step("put leftover stack back", () -> {
            if (!cursor(mc).isEmpty()) click(mc, src[0], 0);
            return true;
        }));

        queue.add(step("close inventory", () -> {
            if (mc.currentScreen != null) mc.player.closeHandledScreen();
            return true;
        }));

        queue.add(step("select listing slot", () -> {
            mc.player.getInventory().setSelectedSlot(WORK_HOTBAR);
            return true;
        }));

        queue.add(step("send /ah sell", () -> {
            if (mc.player.currentScreenHandler.getSlot(WORK_SLOT).getStack().isEmpty()) return true;
            long now = System.currentTimeMillis();
            if (now - lastSell < cooldownMs) { stuck = 0; return false; } // waiting out the cooldown
            lastSell = now;
            mc.player.networkHandler.sendChatCommand("ah sell " + price);
            return true;
        }));

        queue.add(step("wait for listing to be taken", () -> {
            if (mc.currentScreen != null) { stop("A screen opened after /ah sell (confirm screen?). Stopped."); return true; }
            return mc.player.currentScreenHandler.getSlot(WORK_SLOT).getStack().isEmpty();
        }));
    }

    // ---------------------------------------------------------------- fetching

    static void queueFetch(MinecraftClient mc) {
        if (chestPos == null) { stop("No chest set."); return; }

        // turn to face the chest, like a player would
        queue.add(step("face the chest", () -> {
            Vec3d eye = mc.player.getEyePos();
            Vec3d c = Vec3d.ofCenter(chestPos);
            if (eye.distanceTo(c) > 4.5) { stop("Too far from the chest. Stopped."); return true; }
            Vec3d d = c.subtract(eye);
            double hor = Math.sqrt(d.x * d.x + d.z * d.z);
            mc.player.setYaw((float) (Math.toDegrees(Math.atan2(d.z, d.x)) - 90.0));
            mc.player.setPitch((float) -Math.toDegrees(Math.atan2(d.y, hor)));
            return true;
        }));

        // give the rotation a moment to reach the server
        queue.add(step("settle", () -> true));

        queue.add(step("open the chest", () -> {
            Vec3d eye = mc.player.getEyePos();
            Vec3d end = eye.add(mc.player.getRotationVec(1.0f).multiply(5.0));
            BlockHitResult hit = mc.world.raycast(new RaycastContext(
                eye, end, RaycastContext.ShapeType.OUTLINE, RaycastContext.FluidHandling.NONE, mc.player));
            if (hit.getType() != HitResult.Type.BLOCK || !hit.getBlockPos().equals(chestPos)) {
                stop("Can't see the chest from here (something is in the way, or it moved). Stopped.");
                return true;
            }
            mc.interactionManager.interactBlock(mc.player, Hand.MAIN_HAND, hit);
            mc.player.swingHand(Hand.MAIN_HAND);
            return true;
        }));

        queue.add(step("wait for chest to open",
            () -> mc.player.currentScreenHandler instanceof GenericContainerScreenHandler));

        queue.add(transferStep(mc, new boolean[]{false}));
    }

    static BooleanSupplier transferStep(MinecraftClient mc, boolean[] movedAny) {
        return step("move steaks from chest", () -> {
            if (!(mc.player.currentScreenHandler instanceof GenericContainerScreenHandler h)) return true;
            int chestSlots = h.getRows() * 9;
            int invStart = chestSlots;
            int invEnd = chestSlots + 27; // main inventory only, never the hotbar

            int from = -1;
            for (int i = 0; i < chestSlots; i++) {
                if (h.getSlot(i).getStack().isOf(Items.COOKED_BEEF)) { from = i; break; }
            }
            int to = -1;
            for (int i = invStart; i < invEnd; i++) {
                if (h.getSlot(i).getStack().isEmpty()) { to = i; break; }
            }

            if (from == -1 || to == -1) {
                mc.player.closeHandledScreen();
                if (!movedAny[0]) stop("No steaks left in the chest. Stopped.");
                return true;
            }

            mc.interactionManager.clickSlot(h.syncId, from, 0, SlotActionType.PICKUP, mc.player);
            mc.interactionManager.clickSlot(h.syncId, to, 0, SlotActionType.PICKUP, mc.player);
            movedAny[0] = true;

            // queue another round at the front; the engine removes only the step that just ran
            queue.addFirst(transferStep(mc, movedAny));
            return true;
        });
    }

    // ---------------------------------------------------------------- helpers

    static int findSteak(MinecraftClient mc) {
        ScreenHandler h = mc.player.currentScreenHandler;
        for (int i = MAIN_START; i <= MAIN_END; i++) {
            if (h.getSlot(i).getStack().isOf(Items.COOKED_BEEF)) return i;
        }
        return -1;
    }

    static ItemStack cursor(MinecraftClient mc) {
        return mc.player.currentScreenHandler.getCursorStack();
    }

    static void click(MinecraftClient mc, int slot, int button) {
        ScreenHandler h = mc.player.currentScreenHandler;
        mc.interactionManager.clickSlot(h.syncId, slot, button, SlotActionType.PICKUP, mc.player);
    }

    static void stop(String msg) {
        running = false;
        queue.clear();
        say(msg);
    }

    static void say(String msg) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player != null) mc.player.sendMessage(Text.literal("[SteakBot] " + msg), false);
    }
}
