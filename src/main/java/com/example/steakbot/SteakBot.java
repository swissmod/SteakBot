package com.example.steakbot;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.LongArgumentType;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.screen.GenericContainerScreenHandler;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.text.Text;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.Hand;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Random;
import java.util.function.BooleanSupplier;

/**
 * SteakBot: takes steaks out of a chest, then lists them on /ah in small batches.
 *
 * Setup:
 *  - Keep hotbar slot 9 (the last one) EMPTY. The bot uses it as the "listing slot".
 *  - Stand within reach of the chest, look at it, run /flipbot chest
 *  - /flipbot price <amount>   price for each listing
 *  - /flipbot count <n>        how many steaks per listing (default 1)
 *  - /flipbot start | stop
 *
 * Mappings: written against Yarn names. If a method name doesn't compile on your
 * exact 1.21.11 mappings, the fix is usually a one-word rename.
 */
public class SteakBot implements ClientModInitializer {

    // ---- settings ----
    static boolean running = false;
    static long price = 1000;
    static int count = 1;
    static BlockPos chestPos = null;

    // ---- constants ----
    static final int WORK_HOTBAR = 8;   // hotbar index 8 = 9th slot
    static final int WORK_SLOT = 44;    // same slot in the player screen handler (36 + 8)
    static final int MAIN_START = 9, MAIN_END = 35; // main inventory in player screen handler

    // ---- engine ----
    static final Deque<BooleanSupplier> queue = new ArrayDeque<>();
    static final Random rng = new Random();
    static int wait = 0;
    static int stuck = 0;
    static final int STUCK_LIMIT = 80; // ticks a single step may wait before the bot gives up

    @Override
    public void onInitializeClient() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registry) ->
            dispatcher.register(ClientCommandManager.literal("flipbot")
                .then(ClientCommandManager.literal("start").executes(c -> {
                    if (chestPos == null) { say("Set the chest first: look at it and run /flipbot chest"); return 0; }
                    queue.clear(); wait = 0; stuck = 0; running = true;
                    say("Started. price=" + price + " count=" + count);
                    return 1;
                }))
                .then(ClientCommandManager.literal("stop").executes(c -> { stop("Stopped."); return 1; }))
                .then(ClientCommandManager.literal("chest").executes(c -> {
                    MinecraftClient mc = MinecraftClient.getInstance();
                    if (mc.crosshairTarget instanceof BlockHitResult b) {
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
            ));

        ClientTickEvents.END_CLIENT_TICK.register(SteakBot::tick);
    }

    // ---------------------------------------------------------------- engine

    static void tick(MinecraftClient mc) {
        if (!running || mc.player == null || mc.interactionManager == null) return;
        if (wait > 0) { wait--; return; }

        BooleanSupplier step = queue.peekFirst();
        if (step != null) {
            if (step.getAsBoolean()) {
                queue.removeFirstOccurrence(step);
                stuck = 0;
                wait = 2 + rng.nextInt(3); // small random delay between actions
            } else if (++stuck > STUCK_LIMIT) {
                stop("A step timed out (listing limit, confirm screen, or out of reach?). Stopped.");
            }
            return;
        }
        if (mc.currentScreen != null) return; // let any open screen close first
        plan(mc);
    }

    static void plan(MinecraftClient mc) {
        if (findSteak(mc) != -1) {
            queueSellOne(mc);
        } else {
            queueFetch(mc);
        }
    }

    // ---------------------------------------------------------------- selling

    static void queueSellOne(MinecraftClient mc) {
        final int[] src = {-1};

        // 1. pick up a steak stack
        queue.add(() -> {
            src[0] = findSteak(mc);
            if (src[0] == -1) { queue.clear(); return true; }
            click(mc, src[0], 0);
            return true;
        });

        // 2. right-click the listing slot `count` times (places one steak each time)
        for (int i = 0; i < count; i++) {
            queue.add(() -> {
                if (!cursor(mc).isEmpty()) click(mc, WORK_SLOT, 1);
                return true;
            });
        }

        // 3. put the leftover stack back where it came from
        queue.add(() -> {
            if (!cursor(mc).isEmpty()) click(mc, src[0], 0);
            return true;
        });

        // 4. hold the listing slot and list it
        queue.add(() -> {
            mc.player.getInventory().setSelectedSlot(WORK_HOTBAR);
            return true;
        });
        queue.add(() -> {
            if (mc.player.currentScreenHandler.getSlot(WORK_SLOT).getStack().isEmpty()) return true;
            mc.player.networkHandler.sendChatCommand("ah sell " + price);
            return true;
        });

        // 5. wait until the listing slot is empty (the plugin took the item)
        queue.add(() -> {
            if (mc.currentScreen != null) { stop("A screen opened after /ah sell (confirm screen?). Stopped."); return true; }
            return mc.player.currentScreenHandler.getSlot(WORK_SLOT).getStack().isEmpty();
        });
    }

    // ---------------------------------------------------------------- fetching

    static void queueFetch(MinecraftClient mc) {
        if (chestPos == null) { stop("No chest set."); return; }

        // open the chest
        queue.add(() -> {
            Vec3d center = Vec3d.ofCenter(chestPos);
            if (mc.player.getEyePos().distanceTo(center) > 4.5) { stop("Too far from the chest. Stopped."); return true; }
            BlockHitResult hit = new BlockHitResult(center, Direction.UP, chestPos, false);
            mc.interactionManager.interactBlock(mc.player, Hand.MAIN_HAND, hit);
            return true;
        });

        // wait for it to open
        queue.add(() -> mc.player.currentScreenHandler instanceof GenericContainerScreenHandler);

        // move stacks one at a time into the main inventory (never the hotbar)
        queue.add(transferStep(mc, new boolean[]{false}));
    }

    static BooleanSupplier transferStep(MinecraftClient mc, boolean[] movedAny) {
        return new BooleanSupplier() {
            @Override public boolean getAsBoolean() {
                if (!(mc.player.currentScreenHandler instanceof GenericContainerScreenHandler h)) return true;
                int chestSlots = h.getRows() * 9;
                int invStart = chestSlots;            // main inventory follows the chest slots
                int invEnd = chestSlots + 27;         // exclusive; the hotbar comes after this

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

                // queue another round at the front; the engine removes only this step
                queue.addFirst(transferStep(mc, movedAny));
                return true;
            }
        };
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
