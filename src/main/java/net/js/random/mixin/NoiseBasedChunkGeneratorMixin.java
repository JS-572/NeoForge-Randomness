package net.js.random.mixin;

import net.js.random.RandomizationEvents;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(NoiseBasedChunkGenerator.class)
public abstract class NoiseBasedChunkGeneratorMixin {
    @Inject(
            method = "doFill(Lnet/minecraft/world/level/levelgen/NoiseChunk;Lnet/minecraft/world/level/chunk/ChunkAccess;)V",
            at = @At("HEAD")
    )
    private void random$beginTerrainFill(NoiseChunk noiseChunk, ChunkAccess chunk, CallbackInfo callbackInfo) {
        RandomizationEvents.beginTerrainFill(noiseChunk, chunk);
    }

    @ModifyArg(
            method = "doFill(Lnet/minecraft/world/level/levelgen/NoiseChunk;Lnet/minecraft/world/level/chunk/ChunkAccess;)V",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/level/chunk/LevelChunkSection;setBlockState(IIILnet/minecraft/world/level/block/state/BlockState;Z)Lnet/minecraft/world/level/block/state/BlockState;"
            ),
            index = 3
    )
    private BlockState random$selectTerrainBlock(BlockState state) {
        return RandomizationEvents.randomizeTerrainState(state);
    }

    @Inject(
            method = "doFill(Lnet/minecraft/world/level/levelgen/NoiseChunk;Lnet/minecraft/world/level/chunk/ChunkAccess;)V",
            at = @At("RETURN")
    )
    private void random$endTerrainFill(NoiseChunk noiseChunk, ChunkAccess chunk, CallbackInfo callbackInfo) {
        RandomizationEvents.endTerrainFill();
    }
}
