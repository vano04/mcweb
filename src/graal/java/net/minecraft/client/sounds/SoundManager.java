package net.minecraft.client.sounds;

import java.util.Collection;
import java.util.List;
import java.util.Set;
import net.minecraft.client.Camera;
import net.minecraft.client.Options;
import net.minecraft.client.resources.sounds.Sound;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.resources.sounds.TickableSoundInstance;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.util.valueproviders.ConstantFloat;

/**
 * Browser substitution for Minecraft's sound facade, backed by WebAudio.
 *
 * <p>The real manager builds a {@code SoundEngine}, whose OpenAL initialization
 * has no native ALC entry points under Web Image. This shadow keeps that whole
 * subtree unreachable and forwards to the host's {@code mcWebAudio}, which owns
 * the {@code sounds.json} lookup, the ogg decode cache and the mixer.</p>
 *
 * <p>Resolution lives on the host rather than here because Minecraft's sound
 * files are not in the client JAR at all — they come from the launcher's asset
 * object store (index 32 for 26.2), staged next to the page by
 * {@code stageMinecraftAudio}. Consequently {@code getSoundEvent} still returns
 * null and {@link SoundInstance#getSound()} stays unresolved; the one caller
 * that reads it ({@code MusicManager.getCurrentMusicTranslationKey}) null-checks.</p>
 *
 * <p>Playing sounds are tracked by handle so {@code MusicManager}'s contract
 * holds: it restarts music the moment {@link #isActive} goes false, so a
 * facade that always answered false would retrigger the menu track every tick.</p>
 */
public class SoundManager extends net.minecraft.server.packs.resources.SimplePreparableReloadListener<SoundManager.Preparations> {
    public static final Identifier EMPTY_SOUND_LOCATION = Identifier.withDefaultNamespace("empty");
    public static final Sound EMPTY_SOUND = new Sound(
            EMPTY_SOUND_LOCATION,
            ConstantFloat.of(1.0F),
            ConstantFloat.of(1.0F),
            1,
            Sound.Type.FILE,
            false,
            false,
            0
    );
    public static final Identifier INTENTIONALLY_EMPTY_SOUND_LOCATION =
            Identifier.withDefaultNamespace("intentionally_empty");
    public static final Sound INTENTIONALLY_EMPTY_SOUND = new Sound(
            INTENTIONALLY_EMPTY_SOUND_LOCATION,
            ConstantFloat.of(1.0F),
            ConstantFloat.of(1.0F),
            1,
            Sound.Type.FILE,
            false,
            false,
            0
    );
    public static final WeighedSoundEvents INTENTIONALLY_EMPTY_SOUND_EVENT =
            new WeighedSoundEvents(INTENTIONALLY_EMPTY_SOUND_LOCATION, null);

    static {
        INTENTIONALLY_EMPTY_SOUND_EVENT.addSound(INTENTIONALLY_EMPTY_SOUND);
    }

    /** Live playback handles, keyed by the instance Minecraft holds on to. */
    private final java.util.Map<SoundInstance, Integer> handles = new java.util.IdentityHashMap<>();
    private final java.util.List<TickableSoundInstance> tickingSounds = new java.util.ArrayList<>();
    private int reportedPlays;

    public SoundManager(Options options) {
    }

    @Override
    protected Preparations prepare(ResourceManager resourceManager, ProfilerFiller profiler) {
        return new Preparations();
    }

    @Override
    protected void apply(Preparations preparations, ResourceManager resourceManager, ProfilerFiller profiler) {
    }

    public List<String> getAvailableSoundDevices() {
        return List.of();
    }

    public com.mojang.blaze3d.audio.ListenerTransform getListenerTransform() {
        return com.mojang.blaze3d.audio.ListenerTransform.INITIAL;
    }

    public WeighedSoundEvents getSoundEvent(Identifier identifier) {
        return null;
    }

    public Collection<Identifier> getAvailableSounds() {
        return Set.of();
    }

    public void queueTickingSound(TickableSoundInstance sound) {
        if (play(sound) != SoundEngine.PlayResult.NOT_STARTED) {
            tickingSounds.add(sound);
        }
    }

    public SoundEngine.PlayResult play(SoundInstance sound) {
        try {
            Identifier location = sound.getIdentifier();
            if (location == null) {
                return SoundEngine.PlayResult.NOT_STARTED;
            }

            // Mandatory before reading volume or pitch: AbstractSoundInstance
            // computes them as `this.volume * this.sound.getVolume().sample(..)`
            // and `this.sound` stays null until resolve() runs, so skipping it
            // throws NullPointerException before anything reaches the host.
            // Vanilla's SoundEngine.play resolves first for the same reason.
            // getSoundEvent returns null here (sounds.json is resolved on the
            // host, not in Java), which makes resolve fall back to EMPTY_SOUND
            // — non-null, volume and pitch 1.0, so the instance's own values
            // pass through unscaled.
            sound.resolve(this);

            int handle = dev.mcweb.graal.BrowserAudio.playInstance(
                    location.toString(),
                    sound.getSource().getName(),
                    sound.getVolume(),
                    sound.getPitch(),
                    sound.isLooping() ? 1 : 0
            );
            // The @JS bridge has been fragile with wider signatures in this
            // project; report the first few crossings so a silent game can be
            // told apart from a game that never asked for a sound.
            if (reportedPlays < 5) {
                reportedPlays++;
                dev.mcweb.graal.webgpu.BrowserGpu.reportProgress(
                        "sound:play " + location + " handle=" + handle
                );
            }
            if (handle == 0) {
                return SoundEngine.PlayResult.NOT_STARTED;
            }

            // A relative instance is already listener-centred; only world
            // sounds carry a position worth panning and attenuating.
            if (!sound.isRelative()) {
                dev.mcweb.graal.BrowserAudio.positionInstance(
                        handle, sound.getX(), sound.getY(), sound.getZ()
                );
            }

            handles.put(sound, handle);
            return SoundEngine.PlayResult.STARTED;
        } catch (Throwable failure) {
            // Audio must never take down a frame, but a swallowed failure here
            // is indistinguishable from silence, so say so once.
            if (reportedPlays < 5) {
                reportedPlays++;
                StackTraceElement[] frames = failure.getStackTrace();
                dev.mcweb.graal.webgpu.BrowserGpu.reportProgress(
                        "sound:failed " + failure.getClass().getName() + ": " + failure.getMessage()
                                + " at " + (frames.length > 0 ? frames[0] : "<no frames>")
                );
            }
            return SoundEngine.PlayResult.NOT_STARTED;
        }
    }

    public void playDelayed(SoundInstance sound, int delay) {
        // Delayed playback is only used for record/ambient scheduling; without
        // a scheduler here, start it now rather than dropping it.
        play(sound);
    }

    public void updateSource(Camera camera) {
        try {
            if (camera != null && camera.isInitialized()) {
                net.minecraft.world.phys.Vec3 position = camera.position();
                dev.mcweb.graal.BrowserAudio.setListener(position.x, position.y, position.z);
            }
        } catch (Throwable ignored) {
        }
    }

    public void pauseAllExcept(SoundSource... sources) {
    }

    public void stop() {
        try {
            dev.mcweb.graal.BrowserAudio.stopAll();
        } catch (Throwable ignored) {
        }
        handles.clear();
        tickingSounds.clear();
    }

    public void destroy() {
        stop();
    }

    public void emergencyShutdown() {
        stop();
    }

    public void tick(boolean paused) {
        for (int index = tickingSounds.size() - 1; index >= 0; index--) {
            TickableSoundInstance sound = tickingSounds.get(index);
            if (sound.isStopped()) {
                stop(sound);
                tickingSounds.remove(index);
            } else if (!paused) {
                sound.tick();
            }
        }

        // Reap finished one-shots so the handle map does not grow without
        // bound over a play session.
        handles.entrySet().removeIf(entry -> !isPlayingHandle(entry.getValue()));
    }

    public void resume() {
    }

    public void refreshCategoryVolume(SoundSource source) {
    }

    public void stop(SoundInstance sound) {
        Integer handle = handles.remove(sound);
        if (handle != null) {
            try {
                dev.mcweb.graal.BrowserAudio.stopInstance(handle);
            } catch (Throwable ignored) {
            }
        }
    }

    public void updateCategoryVolume(SoundSource source, float volume) {
        try {
            dev.mcweb.graal.BrowserAudio.setCategoryVolume(source.getName(), volume);
        } catch (Throwable ignored) {
        }
    }

    public boolean isActive(SoundInstance sound) {
        Integer handle = handles.get(sound);
        return handle != null && isPlayingHandle(handle);
    }

    private static boolean isPlayingHandle(int handle) {
        try {
            return dev.mcweb.graal.BrowserAudio.isPlaying(handle);
        } catch (Throwable ignored) {
            return false;
        }
    }

    public void addListener(SoundEventListener listener) {
    }

    public void removeListener(SoundEventListener listener) {
    }

    public void stop(Identifier sound, SoundSource source) {
        handles.keySet().removeIf(instance -> {
            boolean matchesSound = sound == null || sound.equals(instance.getIdentifier());
            boolean matchesSource = source == null || source == instance.getSource();
            if (matchesSound && matchesSource) {
                Integer handle = handles.get(instance);
                if (handle != null) {
                    try {
                        dev.mcweb.graal.BrowserAudio.stopInstance(handle);
                    } catch (Throwable ignored) {
                    }
                }
                return true;
            }
            return false;
        });
    }

    public String getChannelDebugString() {
        return "browser WebAudio: " + handles.size() + " live";
    }

    public void getSoundCacheDebugStats(SoundBufferLibrary.DebugOutput output) {
    }

    public void reload() {
    }

    /**
     * Silent replacement for the JAR's preparation holder. The vanilla class
     * parses sounds.json and hands the result to SoundEngine; the silent
     * facade never applies it.
     */
    protected static class Preparations {
        protected Preparations() {
        }
    }
}
