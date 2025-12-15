package com.zoma1101.music_player.sound;

import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import org.jetbrains.annotations.NotNull;

public class FadingMusicInstance extends AbstractTickableSoundInstance {

    private final float initialVolume;
    private final float initialPitch;
    private boolean isFadingOut = false;
    private int fadeOutDurationTicks;
    private int fadeOutTicksRemaining;

    public FadingMusicInstance(ResourceLocation location, float volume, float pitch, int fadeDurationTicks) {
        // ИСПРАВЛЕНИЕ 1: Оборачиваем ResourceLocation в SoundEvent
        super(SoundEvent.createVariableRangeEvent(location), SoundSource.MUSIC, SoundInstance.createUnseededRandom());

        this.initialVolume = volume;
        this.initialPitch = pitch;
        this.volume = volume;
        this.pitch = pitch;
        this.looping = true;
        this.delay = 0;
        this.attenuation = SoundInstance.Attenuation.NONE;
        this.relative = true;

        this.fadeOutDurationTicks = fadeDurationTicks;
        this.fadeOutTicksRemaining = fadeDurationTicks;
    }

    public void startFadeOut(int durationTicks, Runnable onComplete) {
        if (!this.isFadingOut) {
            this.isFadingOut = true;
            this.fadeOutDurationTicks = durationTicks;
            this.fadeOutTicksRemaining = durationTicks;
            // Громкость уже установлена, начинаем отсчет
        }
    }

    public boolean isFadingOut() {
        return this.isFadingOut;
    }

    @Override
    public void tick() {
        if (this.isFadingOut) {
            if (this.fadeOutTicksRemaining > 0) {
                this.fadeOutTicksRemaining--;

                float progress = (float) this.fadeOutTicksRemaining / this.fadeOutDurationTicks;

                // Плавно меняем громкость
                this.volume = this.initialVolume * Mth.clamp(progress, 0.0F, 1.0F);

            } else {
                // ИСПРАВЛЕНИЕ 2: Просто вызываем метод остановки (он final в родителе)
                this.stop();
            }
        }
        this.pitch = this.initialPitch;
    }

    // Метод stop() удален, так как он final и его нельзя переопределять.

    @Override
    public float getVolume() {
        return this.volume;
    }

    @Override
    public @NotNull SoundInstance.Attenuation getAttenuation() {
        return this.attenuation;
    }

    @Override
    public float getPitch() {
        return this.pitch;
    }
}