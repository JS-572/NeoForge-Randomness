package net.js.random;

import net.js.random.mixin.NoiseChunkAccessor;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.TagKey;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.FallingBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.NoiseChunk;
import net.minecraft.world.level.storage.loot.LootPool;
import net.minecraft.world.level.storage.loot.LootTable;
import net.minecraft.world.level.storage.loot.entries.TagEntry;
import net.minecraft.world.level.storage.loot.parameters.LootContextParamSets;
import net.minecraft.world.level.storage.loot.providers.number.ints.ContextIntProviders;
import net.neoforged.neoforge.event.LootTableLoadEvent;
import net.neoforged.neoforge.event.level.BlockDropsEvent;
import net.neoforged.neoforge.event.level.ChunkEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class RandomizationEvents {
    private static final Logger LOGGER = LoggerFactory.getLogger(RandomizationEvents.class);
    private static final TagKey<Item> RANDOM_ITEMS = TagKey.create(
            Registries.ITEM,
            Identifier.fromNamespaceAndPath(RandomnessMod.MOD_ID, "item")
    );
    private static final TagKey<Block> RANDOM_BLOCKS = TagKey.create(
            Registries.BLOCK,
            Identifier.fromNamespaceAndPath(RandomnessMod.MOD_ID, "block")
    );
    private static final ThreadLocal<List<BlockState>> TERRAIN_CANDIDATES = new ThreadLocal<>();
    private static final ThreadLocal<RandomSource> TERRAIN_RANDOM = new ThreadLocal<>();
    private static final ThreadLocal<RandomSource> FEATURE_RANDOM = ThreadLocal.withInitial(RandomSource::create);
    private static volatile List<BlockState> WORLDGEN_CANDIDATES;

    private RandomizationEvents() {}

    static void onBlockDrops(BlockDropsEvent event) {
        if (!(event.getBreaker() instanceof Player) || event.getBlockEntity() != null) {
            return;
        }

        HolderSet.Named<Item> itemTag = event.getLevel().registryAccess()
                .lookupOrThrow(Registries.ITEM)
                .get(RANDOM_ITEMS)
                .orElse(null);
        if (itemTag == null) {
            LOGGER.error("Cannot randomize block drops: missing item tag {}", RANDOM_ITEMS.location());
            return;
        }

        List<Holder<Item>> candidates = itemTag.stream()
                .filter(item -> item.value() != Items.AIR)
                .toList();
        if (candidates.isEmpty()) {
            LOGGER.error("Cannot randomize block drops: item tag {} has no non-air items", RANDOM_ITEMS.location());
            return;
        }

        Holder<Item> selected = candidates.get(event.getLevel().getRandom().nextInt(candidates.size()));
        event.getDrops().clear();
        event.getDrops().add(new ItemEntity(
                event.getLevel(),
                event.getPos().getX() + 0.5,
                event.getPos().getY() + 0.5,
                event.getPos().getZ() + 0.5,
                new ItemStack(selected.value())
        ));
    }

    static void onLootTableLoad(LootTableLoadEvent event) {
        if (!event.getName().getPath().startsWith("chests/")) {
            return;
        }

        HolderSet.Named<Item> itemTag = event.getRegistries()
                .lookupOrThrow(Registries.ITEM)
                .get(RANDOM_ITEMS)
                .orElse(null);
        if (itemTag == null) {
            LOGGER.error("Cannot randomize chest loot: missing item tag {}", RANDOM_ITEMS.location());
            return;
        }

        LootTable randomizedChestLoot = LootTable.lootTable()
                .setParamSet(LootContextParamSets.CHEST)
                .withPool(LootPool.lootPool()
                        .setRolls(ContextIntProviders.exactly(3))
                        .add(TagEntry.expandTag(itemTag)))
                .build();
        event.setTable(randomizedChestLoot);
    }

    static void onChunkLoad(ChunkEvent.Load event) {
        if (!event.isNewChunk() || !(event.getLevel() instanceof ServerLevel level)) {
            return;
        }

        LevelChunk chunk = event.getChunk();
        ChunkPos chunkPos = chunk.getPos();
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int y = level.getMinY(); y < level.getMaxY(); y++) {
            for (int x = chunkPos.getMinBlockX(); x <= chunkPos.getMaxBlockX(); x++) {
                for (int z = chunkPos.getMinBlockZ(); z <= chunkPos.getMaxBlockZ(); z++) {
                    pos.set(x, y, z);
                    Block block = chunk.getBlockState(pos).getBlock();
                    if (block instanceof FallingBlock
                            && y > level.getMinY()
                            && FallingBlock.isFree(chunk.getBlockState(pos.below()))) {
                        level.scheduleTick(pos, block, 1);
                    }
                }
            }
        }
    }

    public static void beginTerrainFill(NoiseChunk noiseChunk, ChunkAccess chunk) {
        List<BlockState> candidates = worldgenCandidates();
        if (candidates.isEmpty()) {
            LOGGER.error("Cannot randomize terrain: block tag {} has no solid blocks without block entities", RANDOM_BLOCKS.location());
            TERRAIN_CANDIDATES.remove();
            TERRAIN_RANDOM.remove();
            return;
        }

        TERRAIN_CANDIDATES.set(List.copyOf(candidates));
        long seed = chunk.getPos().pack() ^ ((NoiseChunkAccessor) noiseChunk).random$randomState().seed();
        TERRAIN_RANDOM.set(RandomSource.create(seed));
    }

    public static BlockState randomizeGeneratedBlock(BlockState original) {
        if (original.isAir() || original.hasBlockEntity()) {
            return original;
        }

        List<BlockState> candidates = worldgenCandidates();
        if (candidates.isEmpty()) {
            return original;
        }
        return candidates.get(FEATURE_RANDOM.get().nextInt(candidates.size()));
    }

    public static BlockState randomizeTerrainState(BlockState original) {
        if (original.isAir() || original.hasBlockEntity()) {
            return original;
        }

        List<BlockState> candidates = TERRAIN_CANDIDATES.get();
        RandomSource random = TERRAIN_RANDOM.get();
        if (candidates == null || random == null) {
            return original;
        }
        return candidates.get(random.nextInt(candidates.size()));
    }

    public static void endTerrainFill() {
        TERRAIN_CANDIDATES.remove();
        TERRAIN_RANDOM.remove();
    }

    private static List<BlockState> worldgenCandidates() {
        List<BlockState> candidates = WORLDGEN_CANDIDATES;
        if (candidates != null) {
            return candidates;
        }

        synchronized (RandomizationEvents.class) {
            candidates = WORLDGEN_CANDIDATES;
            if (candidates == null) {
                List<BlockState> discovered = new ArrayList<>();
                BuiltInRegistries.BLOCK.getTagOrEmpty(RANDOM_BLOCKS).forEach(holder -> {
                    BlockState state = holder.value().defaultBlockState();
                    if (state.isSolidRender() && !state.hasBlockEntity()) {
                        discovered.add(state);
                    }
                });
                candidates = List.copyOf(discovered);
                WORLDGEN_CANDIDATES = candidates;
            }
        }
        return candidates;
    }
}
