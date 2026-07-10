package dev.leonetic.features.modules.render;

import dev.leonetic.Homovore;
import dev.leonetic.event.impl.network.PacketEvent;
import dev.leonetic.event.impl.render.Render3DEvent;
import dev.leonetic.event.system.Subscribe;
import dev.leonetic.features.modules.Module;
import dev.leonetic.features.settings.Setting;
import dev.leonetic.util.InteractionUtil;
import dev.leonetic.util.render.RenderUtil;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.game.ClientboundBlockDestructionPacket;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.awt.Color;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.stream.Collectors;

public class BreakIndicatorsModule extends Module {

    private final Setting<Boolean> useDoubleminePrediction = bool("UseDoubleminePrediction", false).setPage("General");
    private final Setting<Float> rebreakCompletionAmount = num("RebreakCompletionAmount", 0.7f, 0.0f, 1.5f).setPage("General");
    private final Setting<Float> completionAmount = num("FullCompletionAmount", 1.0f, 0.0f, 1.5f).setPage("General");
    private final Setting<Float> removeCompletionAmount = num("ForceRemoveCompletionAmount", 1.3f, 0.0f, 1.5f).setPage("General");
    private final Setting<Boolean> ignoreFriends = bool("IgnoreFriends", false).setPage("General");

    private final Setting<Boolean> render = bool("DoRender", true).setPage("Render");
    private final Setting<Float> lineWidth = num("LineWidth", 1.5f, 0.5f, 5.0f).setPage("Render");
    private final Setting<Color> sideColor = color("SideColor", 255, 0, 80, 10).setPage("Render");
    private final Setting<Color> lineColor = color("LineColor", 255, 255, 255, 40).setPage("Render");

    private final Queue<BlockBreak> breakPackets = new ConcurrentLinkedQueue<>();
    private final Map<BlockPos, BlockBreak> breakStartTimes = new HashMap<>();

    public BreakIndicatorsModule() {
        super("BreakIndicators", "Renders the progress of a block being broken.", Category.RENDER);
    }

    @Override
    public void onEnable() {
        breakPackets.clear();
        breakStartTimes.clear();
    }

    @Override
    public void onDisable() {
        breakPackets.clear();
        breakStartTimes.clear();
    }

    @Subscribe
    private void onPacket(PacketEvent.Receive event) {
        if (nullCheck()) return;
        if (!(event.getPacket() instanceof ClientboundBlockDestructionPacket packet)) return;

        Entity entity = mc.level.getEntity(packet.getId());
        breakPackets.add(new BlockBreak(packet.getPos().immutable(), currentTick(0.0f), entity));
    }

    public boolean isBlockBeingBroken(BlockPos blockPos) {
        return breakStartTimes.containsKey(blockPos);
    }

    public Map<BlockPos, BreakInfo> getActiveBreaksSnapshot() {
        drainBreakPackets(currentTick(0.0f));
        Map<BlockPos, BreakInfo> snapshot = new HashMap<>();
        for (Map.Entry<BlockPos, BlockBreak> entry : breakStartTimes.entrySet()) {
            Entity entity = entry.getValue().entity;
            snapshot.put(entry.getKey(), new BreakInfo(entry.getKey(), entity instanceof Player player ? player : null));
        }
        return snapshot;
    }

    @Subscribe
    private void onRender(Render3DEvent event) {
        if (nullCheck()) return;

        double currentTick = currentTick(event.getDelta());
        drainBreakPackets(currentTick);

        Iterator<Map.Entry<BlockPos, BlockBreak>> iterator = breakStartTimes.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<BlockPos, BlockBreak> entry = iterator.next();
            BlockState state = mc.level.getBlockState(entry.getKey());

            if (state.isAir()
                    || entry.getValue().progress(currentTick) > removeCompletionAmount.getValue()
                    || !InteractionUtil.canBreak(entry.getKey(), state)) {
                iterator.remove();
            }
        }

        if (useDoubleminePrediction.getValue()) {
            Map<Player, List<BlockBreak>> playerBreakingBlocks = breakStartTimes.values().stream()
                    .sorted(Comparator.comparingDouble(blockBreak -> blockBreak.startTick))
                    .filter(blockBreak -> blockBreak.entity instanceof Player)
                    .collect(Collectors.groupingBy(blockBreak -> (Player) blockBreak.entity, Collectors.toList()));

            for (Map.Entry<Player, List<BlockBreak>> entry : playerBreakingBlocks.entrySet()) {
                entry.getValue().forEach(x -> x.isRebreak = false);

                if (entry.getValue().size() >= 2) {
                    entry.getValue().getLast().isRebreak = true;
                }
            }
        }

        if (!render.getValue()) return;

        for (Map.Entry<BlockPos, BlockBreak> entry : breakStartTimes.entrySet()) {
            if (ignoreFriends.getValue() && entry.getValue().entity instanceof Player player
                    && Homovore.friendManager.isFriend(player)) {
                continue;
            }

            entry.getValue().renderBlock(event, currentTick);
        }
    }

    private void drainBreakPackets(double currentTick) {
        while (!breakPackets.isEmpty()) {
            BlockBreak breakEvent = breakPackets.remove();

            if (useDoubleminePrediction.getValue() && breakEvent.entity instanceof Player) {
                List<BlockBreak> playerBreakingBlocks = breakStartTimes.values().stream()
                        .filter(x -> x.entity == breakEvent.entity && !x.blockPos.equals(breakEvent.blockPos))
                        .sorted(Comparator.comparingDouble(x -> x.startTick))
                        .toList();

                if (playerBreakingBlocks.size() >= 2) {
                    breakStartTimes.remove(playerBreakingBlocks.getLast().blockPos);
                }
            }

            BlockBreak existing = breakStartTimes.get(breakEvent.blockPos);
            if (existing == null) {
                breakStartTimes.put(breakEvent.blockPos, breakEvent);
            }
        }
    }

    private double currentTick(float partialTick) {
        return mc.level.getGameTime() + partialTick;
    }

    public record BreakInfo(BlockPos pos, Player player) {}

    private class BlockBreak {
        private final BlockPos blockPos;
        private final double startTick;
        private final Entity entity;
        private boolean isRebreak;

        private BlockBreak(BlockPos blockPos, double startTick, Entity entity) {
            this.blockPos = blockPos;
            this.startTick = startTick;
            this.entity = entity;
        }

        private void renderBlock(Render3DEvent event, double currentTick) {
            BlockState state = mc.level.getBlockState(blockPos);
            VoxelShape shape = state.getShape(mc.level, blockPos);
            if (shape.isEmpty()) {
                RenderUtil.drawBoxFilled(event.getMatrix(), blockPos, sideColor.getValue());
                RenderUtil.drawBox(event.getMatrix(), blockPos, lineColor.getValue(), lineWidth.getValue());
                return;
            }

            AABB orig = shape.bounds();

            double completion = isRebreak ? rebreakCompletionAmount.getValue() : completionAmount.getValue();
            double scale = completion <= 0.0 ? 1.0 : Math.clamp(progress(currentTick) / completion, 0.0, 1.0);

            double centerX = (orig.minX + orig.maxX) * 0.5;
            double centerY = (orig.minY + orig.maxY) * 0.5;
            double centerZ = (orig.minZ + orig.maxZ) * 0.5;
            double halfX = orig.getXsize() * scale * 0.5;
            double halfY = orig.getYsize() * scale * 0.5;
            double halfZ = orig.getZsize() * scale * 0.5;

            double x1 = blockPos.getX() + centerX - halfX;
            double y1 = blockPos.getY() + centerY - halfY;
            double z1 = blockPos.getZ() + centerZ - halfZ;
            double x2 = blockPos.getX() + centerX + halfX;
            double y2 = blockPos.getY() + centerY + halfY;
            double z2 = blockPos.getZ() + centerZ + halfZ;

            AABB renderBox = new AABB(x1, y1, z1, x2, y2, z2);
            RenderUtil.drawBoxFilled(event.getMatrix(), renderBox, sideColor.getValue());
            RenderUtil.drawBox(event.getMatrix(), renderBox, lineColor.getValue(), lineWidth.getValue());
        }

        private double progress(double currentTick) {
            BlockState state = mc.level.getBlockState(blockPos);
            int slot = InteractionUtil.fastestToolSlot(state);
            double speed = InteractionUtil.rawMiningSpeed(slot, state, true);
            return InteractionUtil.breakDelta(speed, state) * (currentTick - startTick);
        }
    }
}
