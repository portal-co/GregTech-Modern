package com.gregtechceu.gtceu.api.data.worldgen.generator;

import com.gregtechceu.gtceu.api.data.chemical.ChemicalHelper;
import com.gregtechceu.gtceu.api.data.chemical.material.Material;
import com.gregtechceu.gtceu.api.data.worldgen.GTOreDefinition;
import com.gregtechceu.gtceu.api.data.worldgen.GTOreFeature;
import com.gregtechceu.gtceu.api.registry.GTRegistries;
import com.gregtechceu.gtceu.common.data.GTFeatures;
import com.gregtechceu.gtceu.utils.GTUtil;
import com.mojang.datafixers.util.Either;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import lombok.AllArgsConstructor;
import lombok.Setter;
import lombok.experimental.Accessors;
import net.minecraft.MethodsReturnNonnullByDefault;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.data.BuiltinRegistries;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.BulkSectionAccess;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.blending.Blender;
import net.minecraft.world.level.levelgen.feature.configurations.OreConfiguration;
import org.apache.commons.lang3.mutable.MutableBoolean;

import javax.annotation.ParametersAreNonnullByDefault;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@MethodsReturnNonnullByDefault
@ParametersAreNonnullByDefault
@AllArgsConstructor
@Accessors(fluent = true, chain = true)
public class VeinedVeinGenerator extends VeinGenerator {
    public static final Codec<Either<List<OreConfiguration.TargetBlockState>, Material>> BLOCK_ENTRY_CODEC = Codec.either(OreConfiguration.TargetBlockState.CODEC.listOf(), GTRegistries.MATERIALS.codec());

    public static final Codec<VeinedVeinGenerator> CODEC = RecordCodecBuilder.create((instance) -> instance.group(
            VeinBlockDefinition.CODEC.listOf().fieldOf("ore_blocks").forGetter(it -> it.oreBlocks),
            VeinBlockDefinition.CODEC.listOf().fieldOf("rare_blocks").forGetter(it -> it.rareBlocks),
            BlockState.CODEC.fieldOf("filler_block").orElse(Blocks.AIR.defaultBlockState()).forGetter(it -> it.fillerBlock),
            Codec.INT.fieldOf("min_y").forGetter(it -> it.minYLevel),
            Codec.INT.fieldOf("max_y").forGetter(it -> it.maxYLevel),
            Codec.FLOAT.fieldOf("veininess_threshold").orElse(0.4f).forGetter(it -> it.veininessThreshold),
            Codec.INT.fieldOf("edge_roundoff_begin").orElse(20).forGetter(it -> it.edgeRoundoffBegin),
            Codec.DOUBLE.fieldOf("max_edge_roundoff").orElse(0.2).forGetter(it -> it.maxEdgeRoundoff),
            Codec.FLOAT.fieldOf("min_richness").orElse(0.1f).forGetter(it -> it.minRichness),
            Codec.FLOAT.fieldOf("max_richness").orElse(0.3f).forGetter(it -> it.maxRichness),
            Codec.FLOAT.fieldOf("max_richness_threshold").orElse(0.6f).forGetter(it -> it.maxRichnessThreshold),
            Codec.FLOAT.fieldOf("rare_block_chance").orElse(0.02f).forGetter(it -> it.rareBlockChance)//,
    ).apply(instance, VeinedVeinGenerator::new));

    public List<VeinBlockDefinition> oreBlocks;
    public List<VeinBlockDefinition> rareBlocks;
    @Setter
    public BlockState fillerBlock;
    @Setter
    public int minYLevel;
    @Setter
    public int maxYLevel;
    @Setter
    public float veininessThreshold = 0.4f;
    @Setter
    public int edgeRoundoffBegin = 20;
    @Setter
    public double maxEdgeRoundoff = 0.2;
    @Setter
    public float minRichness = 0.1f;
    @Setter
    public float maxRichness = 0.3f;
    @Setter
    public float maxRichnessThreshold = 0.6f;
    @Setter
    public float rareBlockChance = 0.02f;

    public VeinedVeinGenerator(GTOreDefinition entry) {
        super(entry);
    }

    @Override
    public List<Map.Entry<Either<BlockState, Material>, Integer>> getAllEntries() {
        var s1 = this.oreBlocks.stream().flatMap(definition ->
                definition.block.map(
                        state -> state.stream().map(target -> Map.entry(Either.<BlockState, Material>left(target.state), definition.weight)),
                        material -> Stream.of(Map.entry(Either.<BlockState, Material>right(material), definition.weight))
                )
        );
        var s2 = this.rareBlocks == null ? null : this.rareBlocks.stream().flatMap(definition ->
                definition.block.map(
                        state -> state.stream().map(target -> Map.entry(Either.<BlockState, Material>left(target.state), definition.weight)),
                        material -> Stream.of(Map.entry(Either.<BlockState, Material>right(material), definition.weight))
                )
        );

        return (s2 == null ? s1 : Stream.concat(s1, s2)).collect(Collectors.toList());
    }

    @Override
    public boolean generate(WorldGenLevel level, RandomSource random, GTOreDefinition entry, BlockPos origin) {
        List<? extends Map.Entry<Integer, VeinBlockDefinition>> commonEntries = oreBlocks.stream().map(b -> Map.entry(b.weight, b)).toList();
        List<? extends Map.Entry<Integer, VeinBlockDefinition>> rareEntries = rareBlocks == null ? null : rareBlocks.stream().map(b -> Map.entry(b.weight, b)).toList(); // never accessed if rareBlocks is null

        BulkSectionAccess access = new BulkSectionAccess(level);
        RandomState randomState = level.getLevel().getChunkSource().randomState();
        Blender blender;
        if (level instanceof WorldGenRegion region) {
            blender = Blender.of(region);
        } else {
            blender = Blender.empty();
        }

        final Blender finalizedBlender = blender;
        DensityFunction veinToggle = mapToNoise(BuiltinRegistries.DENSITY_FUNCTION.get(GTFeatures.NEW_ORE_VEIN_TOGGLE), randomState);
        DensityFunction veinRidged = mapToNoise(BuiltinRegistries.DENSITY_FUNCTION.get(GTFeatures.NEW_ORE_VEIN_RIDGED), randomState);

        int size = entry.getClusterSize();

        // Limit to a radius of 22 to avoid generating outside the allowed 3x3 chunk area for features
        int radius = Math.min(Mth.ceil(size / 2f), 22);

        int placedCount = 0;

        int randOffsetX = random.nextInt(16);
        int randOffsetY = random.nextInt(16);
        int randOffsetZ = random.nextInt(16);

        var posMin = origin.offset(-radius, -radius, -radius);
        var posMax = origin.offset(+radius, +radius, +radius);

        for (BlockPos chunkedPos : BlockPos.betweenClosed(posMin, posMax)) {
            final int x = chunkedPos.getX();
            final int y = chunkedPos.getY();
            final int z = chunkedPos.getZ();

            DensityFunction.FunctionContext functionContext = new DensityFunction.FunctionContext() {
                @Override
                public int blockX() {
                    return x + randOffsetX;
                }

                @Override
                public int blockY() {
                    return y + randOffsetY;
                }

                @Override
                public int blockZ() {
                    return z + randOffsetZ;
                }

                @Override
                public Blender getBlender() {
                    return finalizedBlender;
                }
            };

            double toggleNoise = veinToggle.compute(functionContext);
            int blockY = origin.getY();
            double absToggleNoise = Math.abs(toggleNoise);
            int minY = blockY - this.minYLevel;
            int maxY = this.maxYLevel - blockY;
            if (minY < 0 || maxY < 0) {
                continue;
            }
            int lowY = Math.min(maxY, minY);
            double edgeRoundoff = Mth.clampedMap(lowY, 0.0, edgeRoundoffBegin, -maxEdgeRoundoff, 0.0);
            if (absToggleNoise + edgeRoundoff < veininessThreshold) {
                continue;
            }
            if (random.nextFloat() > entry.getDensity()) {
                continue;
            }
            if (veinRidged.compute(functionContext) >= 0.0) {
                continue;
            }
            double chance = Mth.clampedMap(absToggleNoise, veininessThreshold, maxRichnessThreshold, minRichness, maxRichness);

            BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos(x, y, z);
            LevelChunkSection section = access.getSection(pos);
            if (section == null)
                continue;
            int sectionX = SectionPos.sectionRelative(pos.getX());
            int sectionY = SectionPos.sectionRelative(pos.getY());
            int sectionZ = SectionPos.sectionRelative(pos.getZ());
            if (!level.ensureCanWrite(pos))
                continue;
            BlockState current = section.getBlockState(sectionX, sectionY, sectionZ);
            boolean placed = false;
            if (random.nextFloat() <= entry.getDensity()) {
                if (random.nextFloat() < chance) {
                    if (rareBlocks != null && !rareBlocks.isEmpty() && random.nextFloat() < rareBlockChance) {
                        placed = placeOre(rareBlocks.get(GTUtil.getRandomItem(random, rareEntries, rareEntries.size())).block, current, access, section, random, pos, entry);
                    } else {
                        placed = placeOre(oreBlocks.get(GTUtil.getRandomItem(random, commonEntries, commonEntries.size())).block, current, access, section, random, pos, entry);
                    }
                } else {
                    if (fillerBlock == null || fillerBlock.isAir())
                        continue;
                    if (!GTOreFeature.canPlaceOre(current, level::getBlockState, random, entry, pos))
                        continue;
                    section.setBlockState(sectionX, sectionY, sectionZ, fillerBlock, false);
                    if (level.getBlockState(pos) != current) placed = true;
                }
            }

            if (placed)  {
                ++placedCount;
            }
        }

        access.close();
        return placedCount > 0;
    }

    protected static boolean placeOre(Either<List<OreConfiguration.TargetBlockState>, Material> block, BlockState current, BulkSectionAccess level, LevelChunkSection section, RandomSource random, BlockPos.MutableBlockPos pos, GTOreDefinition entry) {
        MutableBoolean returnValue = new MutableBoolean(false);

        int x = SectionPos.sectionRelative(pos.getX());
        int y = SectionPos.sectionRelative(pos.getY());
        int z = SectionPos.sectionRelative(pos.getZ());

        block.ifLeft(blockStates -> {
            for (OreConfiguration.TargetBlockState targetState : blockStates) {
                if (!GTOreFeature.canPlaceOre(current, level::getBlockState, random, entry, targetState, pos))
                    continue;
                if (targetState.state.isAir())
                    continue;
                section.setBlockState(x, y, z, targetState.state, false);
                returnValue.setTrue();
                break;
            }
        }).ifRight(material -> {
            if (!GTOreFeature.canPlaceOre(current, level::getBlockState, random, entry, pos))
                return;
            BlockState currentState = level.getBlockState(pos);
            var prefix = ChemicalHelper.ORES_INVERSE.get(currentState);
            if (prefix == null) return;
            Block toPlace = ChemicalHelper.getBlock(prefix, material);
            if (toPlace == null || toPlace.defaultBlockState().isAir())
                return;
            section.setBlockState(x, y, z, toPlace.defaultBlockState(), false);
            returnValue.setTrue();
        });
        return returnValue.isTrue();
    }

    @Override
    public VeinGenerator build() {
        return this;
    }

    @Override
    public VeinGenerator copy() {
        return new VeinedVeinGenerator(this.oreBlocks, this.rareBlocks, this.fillerBlock, this.minYLevel, this.maxYLevel, this.veininessThreshold, this.edgeRoundoffBegin, this.maxEdgeRoundoff, this.minRichness, this.maxRichness, this.maxRichnessThreshold, this.rareBlockChance);
    }

    @Override
    public Codec<? extends VeinGenerator> codec() {
        return CODEC;
    }

    public VeinedVeinGenerator oreBlock(VeinBlockDefinition material) {
        if (this.oreBlocks == null) this.oreBlocks = new ArrayList<>();
        this.oreBlocks.add(material);
        return this;
    }

    public VeinedVeinGenerator rareBlock(VeinBlockDefinition material) {
        if (this.rareBlocks == null) this.rareBlocks = new ArrayList<>();
        this.rareBlocks.add(material);
        return this;
    }

    public record VeinBlockDefinition(Either<List<OreConfiguration.TargetBlockState>, Material> block, int weight) {
        public static final Codec<VeinBlockDefinition> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                BLOCK_ENTRY_CODEC.fieldOf("block").forGetter(x -> x.block),
                Codec.INT.fieldOf("weight").forGetter(x -> x.weight)
        ).apply(instance, VeinBlockDefinition::new));

        public VeinBlockDefinition(Material block, int weight) {
            this(Either.right(block), weight);
        }

        public VeinBlockDefinition(List<OreConfiguration.TargetBlockState> block, int weight) {
            this(Either.left(block), weight);
        }
    }

    private static DensityFunction mapToNoise(DensityFunction function, RandomState randomState) {
        return function.mapAll(new DensityFunction.Visitor() {
            @Override
            public DensityFunction apply(DensityFunction densityFunction) {
                return densityFunction;
            }

            @Override
            public DensityFunction.NoiseHolder visitNoise(DensityFunction.NoiseHolder noiseHolder) {
                var holder = noiseHolder.noiseData();
                var noise = randomState.getOrCreateNoise(holder.unwrapKey().orElseThrow());
                return new DensityFunction.NoiseHolder(holder, noise);
            }
        });
    }
}
