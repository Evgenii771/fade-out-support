package com.zoma1101.music_player;

import com.mojang.logging.LogUtils;
import com.zoma1101.music_player.sound.FadingMusicInstance;
import com.zoma1101.music_player.sound.MusicDefinition;
import com.zoma1101.music_player.util.MusicConditionEvaluator;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.sounds.SoundManager;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundSource;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.client.event.sound.PlaySoundEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.slf4j.Logger;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Random;

@Mod.EventBusSubscriber(modid = Music_Player.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE, value = Dist.CLIENT)
public class ClientMusicManager {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final int CHECK_INTERVAL_TICKS = 20;
    private static final int FADE_DURATION_TICKS = 60;

    @Nullable
    private static FadingMusicInstance currentMusicInstance = null;
    @Nullable
    private static String currentMusicSoundEventKey = null;

    private static final Random RANDOM = new Random();

    private static boolean isStopping = false;
    private static boolean isRecordPlaying = false;
    @Nullable
    private static SoundInstance lastPlayedRecordInstance = null;

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase == TickEvent.Phase.END) {
            Minecraft mc = Minecraft.getInstance();
            LocalPlayer player = mc.player;

            if (player != null && mc.level != null && player.tickCount % CHECK_INTERVAL_TICKS == 0) {
                if (isRecordPlaying) {
                    SoundManager soundManager = mc.getSoundManager();
                    if (lastPlayedRecordInstance != null && !soundManager.isActive(lastPlayedRecordInstance)) {
                        LOGGER.info("Record music stopped. Resuming MOD music checks.");
                        isRecordPlaying = false;
                        lastPlayedRecordInstance = null;
                        updateMusic();
                    } else if (lastPlayedRecordInstance == null) {
                        isRecordPlaying = false;
                        updateMusic();
                    } else {
                        // Record active -> ensure mod music is stopped immediately
                        if (currentMusicInstance != null) {
                            stopMusic(true);
                        }
                    }
                } else {
                    updateMusic();
                }
            }
            if (isStopping) {
                isStopping = false;
            }
        }
    }

    @SubscribeEvent
    public static void onPlayerLogin(ClientPlayerNetworkEvent.LoggingIn event) {
        LOGGER.info("Player logged in. Resetting music state.");
        stopMusic(true);
        isRecordPlaying = false;
        lastPlayedRecordInstance = null;
    }

    @SubscribeEvent
    public static void onPlayerLogout(ClientPlayerNetworkEvent.LoggingOut event) {
        LOGGER.info("Player logged out. Stopping music.");
        stopMusic(true);
        isRecordPlaying = false;
        lastPlayedRecordInstance = null;
    }

    @SubscribeEvent
    public static void onPlaySound(PlaySoundEvent event) {
        SoundInstance soundBeingPlayed = event.getSound();
        if (soundBeingPlayed == null) return;

        ResourceLocation playingSoundEventLocation = soundBeingPlayed.getLocation();
        SoundSource soundSource = soundBeingPlayed.getSource();
        String playingNamespace = playingSoundEventLocation.getNamespace();

        // 1. Handle Records (Jukebox/Other Mods)
        if (SoundSource.RECORDS.equals(soundSource)) {
            if (!isRecordPlaying || (lastPlayedRecordInstance != null && !lastPlayedRecordInstance.getLocation().equals(playingSoundEventLocation))) {
                LOGGER.info("Record-source music [{}] started.", playingSoundEventLocation);
                isRecordPlaying = true;
                lastPlayedRecordInstance = soundBeingPlayed;
                if (currentMusicInstance != null) {
                    stopMusic(true); // Immediate stop
                }
            }
            return;
        }

        // 2. Handle THIS Mod's Music
        if (SoundSource.MUSIC.equals(soundSource) && Music_Player.MOD_ID.equals(playingNamespace)) {
            if (isRecordPlaying) {
                event.setSound(null);
                return;
            }
            // Strict check: only allow the instance we control
            if (currentMusicInstance != null && event.getSound() == currentMusicInstance) {
                // OK
            } else {
                event.setSound(null);
            }
            return;
        }

        // 3. Handle Other/Vanilla Music
        if (SoundSource.MUSIC.equals(soundSource)) {
                LOGGER.info("Override enabled. Cancelling external music: {}", playingSoundEventLocation);
                event.setSound(null);
        }
    }

    private static void updateMusic() {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;

        if (player == null || mc.level == null) {
            if (currentMusicInstance != null) {
                stopMusic(true);
            }
            currentMusicSoundEventKey = null;
            return;
        }

        if (isStopping || isRecordPlaying) {
            if (isRecordPlaying && currentMusicInstance != null && !currentMusicInstance.isStopped()) {
                stopMusic(true);
            }
            return;
        }

        MusicConditionEvaluator.CurrentContext context = MusicConditionEvaluator.getCurrentContext(player, mc.level, mc.screen);
        List<MusicDefinition> definitions = Music_Player.soundPackManager.getActiveMusicDefinitionsSorted();

        // sticky logic
        MusicDefinition bestMatch = findBestMatch(definitions, context);

        String targetSoundEventKey = null;
        if (bestMatch != null && bestMatch.isValid()) {
            targetSoundEventKey = bestMatch.getSoundEventKey();
        }

        // --- Clean up and Transition Logic ---

        // 1. Clean up if current track stopped (completed fade-out or was hard-stopped)
        if (currentMusicInstance != null && currentMusicInstance.isStopped()) {
            currentMusicInstance = null;
            // Key was already cleared in stopMusic
        }

        // 2. Music Change Detected
        if (!Objects.equals(targetSoundEventKey, currentMusicSoundEventKey)) {

            // If a track is currently playing (and not already fading) -> start fade out.
            if (currentMusicInstance != null && !currentMusicInstance.isFadingOut()) {
                LOGGER.info("Music change detected: [{}] -> [{}]. Starting fade-out.", currentMusicSoundEventKey, targetSoundEventKey);
                // stopMusic(false) очистит currentMusicInstance/Key и поставит колбэк на остановку SoundManager'а
                stopMusic(false);
            }

            // If the old track is gone (null) AND there is a new track -> start playing.
            if (currentMusicInstance == null && targetSoundEventKey != null) {
                LOGGER.info("Starting new track: [{}]", targetSoundEventKey);
                playMusicByKey(targetSoundEventKey);
                currentMusicSoundEventKey = targetSoundEventKey;
            }
        }

        // 3. No change required (Keys match)
        else if (currentMusicInstance != null) {
            // Watchdog: If current music should be playing but is inactive (and not fading out) -> restart it
            SoundManager sm = Minecraft.getInstance().getSoundManager();
            if (!sm.isActive(currentMusicInstance) && !currentMusicInstance.isFadingOut()) {
                LOGGER.warn("Music inactive unexpectedly. Restarting.");
                stopMusic(true); // Hard stop to clear all states
                playMusicByKey(targetSoundEventKey);
                currentMusicSoundEventKey = targetSoundEventKey;
            }
        }

        // No action required if: Keys match AND instance is active/valid.
    }

    private static void playMusicByKey(String soundEventKey) {
        if (isStopping || isRecordPlaying || soundEventKey == null) return;

        try {
            ResourceLocation soundEventRl = ResourceLocation.fromNamespaceAndPath(Music_Player.MOD_ID, soundEventKey);

            if (currentMusicInstance != null) {
                Minecraft.getInstance().getSoundManager().stop(currentMusicInstance);
            }

            currentMusicInstance = new FadingMusicInstance(
                    soundEventRl,
                    1.0f,
                    1.0f,
                    FADE_DURATION_TICKS
            );

            Minecraft.getInstance().getSoundManager().play(currentMusicInstance);
            LOGGER.info("Playing music: [{}]", soundEventKey);

        } catch (Exception e) {
            LOGGER.error("Failed to play music [{}]: {}", soundEventKey, e.getMessage());
            currentMusicInstance = null;
        }
    }

    private static void stopMusic(boolean immediate) {
        if (currentMusicInstance != null) {
            final FadingMusicInstance instanceToStop = currentMusicInstance;

            if (immediate) {
                // Hard stop
                Minecraft.getInstance().getSoundManager().stop(instanceToStop);
                currentMusicInstance = null;
                currentMusicSoundEventKey = null;
            } else {
                // Soft stop (Fade out)
                if (!instanceToStop.isFadingOut()) {
                    currentMusicInstance = null;
                    currentMusicSoundEventKey = null;

                    LOGGER.debug("Starting fade-out of current instance.");

                    // Запускаем фейд и в колбэке останавливаем звук
                    instanceToStop.startFadeOut(FADE_DURATION_TICKS, () -> {
                        Minecraft.getInstance().getSoundManager().stop(instanceToStop);
                        LOGGER.debug("Fade-out complete. SoundManager stopped instance.");
                    });
                }
            }
        }
        if (immediate) {
            isStopping = true;
        }
    }

    @Nullable
    private static MusicDefinition findBestMatch(List<MusicDefinition> definitions, MusicConditionEvaluator.CurrentContext context) {

        final String playingKey = currentMusicSoundEventKey;
        MusicDefinition currentPlayingDefinition = null;

        int bestPriority = Integer.MIN_VALUE;
        for (MusicDefinition definition : definitions) {
            if (definition.isValid() && MusicConditionEvaluator.doesDefinitionMatch(definition, context)) {

                if (Objects.equals(definition.getSoundEventKey(), playingKey)) {
                    currentPlayingDefinition = definition;
                }

                if (definition.getPriority() > bestPriority) {
                    bestPriority = definition.getPriority();
                }
            }
        }

        if (bestPriority == Integer.MIN_VALUE) {
            return null;
        }

        if (currentPlayingDefinition != null && currentPlayingDefinition.getPriority() == bestPriority) {
            return currentPlayingDefinition;
        }

        List<MusicDefinition> candidates = new ArrayList<>();
        for (MusicDefinition definition : definitions) {
            if (definition.isValid()
                    && definition.getPriority() == bestPriority
                    && MusicConditionEvaluator.doesDefinitionMatch(definition, context)) {
                candidates.add(definition);
            }
        }

        if (candidates.isEmpty()) {
            return null;
        } else if (candidates.size() == 1) {
            return candidates.get(0);
        } else {
            int randomIndex = RANDOM.nextInt(candidates.size());
            return candidates.get(randomIndex);
        }
    }
}