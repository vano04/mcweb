package dev.mcweb.graal;

import com.mojang.blaze3d.platform.FramerateLimitTracker;
import com.mojang.blaze3d.platform.FramerateLimitTracker.FramerateThrottleReason;
import com.mojang.blaze3d.systems.RenderSystem;
import dev.mcweb.graal.webgpu.BrowserGpu;
import net.minecraft.client.Minecraft;
import org.graalvm.webimage.api.JS;

/**
 * Animation-frame pump replacing Minecraft's blocking run() loop. The browser
 * host calls the registered callback once per requestAnimationFrame; each call
 * executes exactly one real runTick frame. run() is entered once in pump mode
 * only to install the game thread, then returns.
 */
public final class BrowserFramePump {
    private static Minecraft minecraft;
    private static long frameCount;
    private static boolean failed;
    /** Frames dropped in a row before the pump gives up for good. */
    private static final int MAX_CONSECUTIVE_FAILURES = 240;
    /** Cap on failure reports, so a repeating fault cannot flood the ring buffer. */
    private static final int MAX_REPORTED_FAILURES = 5;
    private static int consecutiveFailures;
    private static int reportedFailures;
    private static String currentPhase = "not-started";
    private static String lastScreenClass;
    private static boolean postReloadGcRequested;
    private static int postReloadGcAttempts;
    private static long postReloadGcBefore = -1L;
    private static String lastWorldState;
    private static net.minecraft.client.renderer.ViewArea lastViewArea;
    private static int viewAreaGeneration;
    private static int terrainReportCount;
    private static int lastBrowserFrameLimit = -1;
    private static boolean lastBrowserVsync;
    /** Set once Minecraft's own Quit Game has cleared {@code running}. */
    private static boolean quitting;

    private BrowserFramePump() {
    }

    public static void start(Minecraft instance) {
        minecraft = instance;
        BrowserMultiplayerCompat.install(instance);
        postReloadGcRequested = false;
        postReloadGcAttempts = 0;
        postReloadGcBefore = -1L;
        lastGameplayState = null;
        nextGameplayReportFrame = 0L;
        lastBrowserFrameLimit = -1;
        failed = false;
        consecutiveFailures = 0;
        reportedFailures = 0;
        currentPhase = "start";
        quitting = false;
        // Start the AFK clock here rather than at image start. The tracker's
        // latestInputTime begins at zero and is measured against a monotonic
        // clock, so by the time a long boot reaches the title screen Minecraft
        // already believes nobody has touched the game -- it then clamps to
        // 30 FPS (and to 10 after ten minutes) until the first real click.
        noteInput();
        updateBrowserFramePacing();
        // The runTick prologue patch lazily installs the game thread on the
        // first pumped frame; the blocking desktop run() loop is never called.
        // Names every pack the repository found, including the directory packs
        // restored from browser storage. Without it a pack that silently failed
        // discovery is indistinguishable from one the player never enabled.
        BrowserServerPacks.reportDiscoveredPacks(instance);
        BrowserGpu.registerFrameCallback(BrowserFramePump::frame);
        // DOM events from the page host flow through the retained GLFW
        // callback interfaces into Minecraft's input handlers.
        BrowserGpu.registerInputBridge(BrowserInputBridge.dispatcher());
    }

    /**
     * Keeps Minecraft's inactivity throttle honest.
     *
     * <p>Vanilla resets it from the GLFW key, button and scroll callbacks. This
     * port additionally counts cursor movement: the desktop reason for ignoring
     * it — a stray mouse should not keep an unattended game at full speed — is
     * already handled by the browser, which stops delivering animation frames
     * to a hidden tab entirely.</p>
     */
    static void noteInput() {
        if (minecraft == null) {
            return;
        }
        try {
            minecraft.getFramerateLimitTracker().onInputReceived();
        } catch (Throwable ignored) {
            // Pacing is an optimisation; input must never fail because of it.
        }
    }

    static void frame() {
        if (minecraft == null || failed) {
            return;
        }
        // Quit Game only clears Minecraft's `running` flag, which the desktop
        // run() loop would have noticed. This pump replaces that loop, so the
        // button did nothing at all: no unload, no menu, a live canvas.
        if (!quitting && !minecraft.isRunning()) {
            quitting = true;
            handleQuit();
            return;
        }
        final long frameStartedNanos = System.nanoTime();
        currentPhase = "frame.pre-tick";
        try {
            BrowserMinecraftMain.applyBenchmarkSeedIfPresent();
            // CompletableFuture stages used by world creation can enqueue
            // their final apply step after the inline worker returns. Pump the
            // real Minecraft main-executor queue at the frame boundary.
            InlineExecutorService.drainMainLoopFromFrame();
            // The server half of the memory connection is drained inside
            // pump(), immediately before tickServer; the client half is drained
            // by Minecraft's own Connection.tick during runTick.
            BrowserWorkerClientCompat.pump();
            // Multiplayer: a page-issued join, then the relay's inbound drain.
            // Same seam as the Worker lane, one pipe further out.
            BrowserMultiplayerCompat.pump();
            BrowserMultiplayerCompat.pumpConnect();
            BrowserIntegratedServerCompat.pump();
            // Server-pushed resource packs finish in the page; apply them here,
            // where the pack repository and its reload belong.
            BrowserServerPacks.pump();
            net.minecraft.network.Connection.drainBrowserClient();
            RenderSystem.pollEvents();
            int reloadCompletion = BrowserReloadDiagnostics.consumeReloadCompletion();
            if (reloadCompletion != 0) {
                BrowserGpu.reportProgress("reload:all-done result="
                        + (reloadCompletion > 0 ? "success" : "failure"));
                if (reloadCompletion > 0 && McWebRuntimeMode.isThreaded()) {
                    postReloadGcRequested = true;
                }
            }
            /*
             * Collect at the exact reload boundary, before the next interactive
             * frame. This is intentionally independent of screen class: screen
             * transitions are not a safe proxy for reload completion and made the
             * old Accessibility/Title path stutter visibly.
            */
            if (postReloadGcRequested && postReloadGcAttempts < 3) {
                long before = observedCollections();
                long refusalsBefore = observedAgentRefusals();
                if (postReloadGcBefore < 0L) {
                    postReloadGcBefore = before;
                }
                postReloadGcAttempts++;
                BrowserGpu.reportProgress("gc:post-reload-start attempt=" + postReloadGcAttempts);
                System.gc();
                long after = observedCollections();
                long refusalsAfter = observedAgentRefusals();
                boolean progressed = before >= 0L && after > before;
                boolean refused = refusalsBefore >= 0L && refusalsAfter > refusalsBefore;
                BrowserGpu.reportProgress("gc:post-reload-done attempt=" + postReloadGcAttempts
                        + " before=" + before + " after=" + after
                        + " progressed=" + progressed + " refused=" + refused);
                // Keep the flag for at most two later overlay frames when the
                // collector explicitly refused to park workers. A successful
                // collection, an ignored System.gc(), or the bounded attempt limit
                // ends the lifecycle request.
                postReloadGcRequested = !progressed && refused && postReloadGcAttempts < 3;
                if (!postReloadGcRequested) {
                    postReloadGcBefore = -1L;
                }
            }
            AgentExecutorService.servicePrimaryCollectionRequest("frame-boundary");
            // The boolean enables the render section of runTick; every
            // scheduled callback is a real, fully rendered game frame.
            currentPhase = "frame.minecraft-run-tick";
            minecraft.runTick(true);
            currentPhase = "frame.post-tick";
            // Minecraft's client files live in Web Image's in-memory filesystem.
            // Publish changed options/server-list/resource-pack files at a
            // bounded cadence; IndexedDB writes stay asynchronous on the page.
            BrowserPersistentStorage.pump();
            // FramerateLimitTracker includes Minecraft's menu/AFK throttles,
            // while enableVsync is the user's presentation choice. Publish a
            // change only when either value moves so the browser scheduler can
            // match desktop semantics without adding a hot bridge crossing.
            updateBrowserFramePacing();
            frameCount++;
            BrowserGpu.reportFrame(frameCount);
            BrowserMeshProbe.maybeRun(minecraft);
            BrowserLiveMeshDispatcher.pump();
            // Paced section meshing on the cooperative lane: each drained task
            // meshes one section and stages its GPU upload for the next frame's
            // uploadTerrainBuffersToGpu, spreading reveal bursts across frames
            // instead of landing every staged upload in the frame that revealed
            // the sections. No-op off the cooperative lane.
            BrowserMeshPaceExecutor.drainFromFrame(frameStartedNanos);
            AgentExecutorService.maintain();
            // Small budget: this only fires when every worker is busy and work is still
            // queued, which is the starvation the pool cannot resolve on its own. Kept
            // far below the world-load budget so a starved pool costs frame time rather
            // than the frame.
            if (McWebRuntimeMode.isCooperativeServer()) {
                AgentExecutorService.drainInlineIfStarved(4);
            }
            BrowserReloadDiagnostics.heartbeat(frameCount);
            if (frameCount % 120 == 1) {
                BrowserGpu.reportProgress("threads:" + describeThreadIdentity());
                BrowserSkinTextureCompat.reportLivePlayerState(minecraft);
            }
            String screenClass = minecraft.gui.screen() == null
                    ? "<none>"
                    : minecraft.gui.screen().getClass().getName();
            if (!screenClass.equals(lastScreenClass)) {
                lastScreenClass = screenClass;
                BrowserGpu.reportProgress("screen:" + screenClass);
            }
            reportWorldState();
            reportGameplayState();
            reportTerrainState();
            consecutiveFailures = 0;
        } catch (Throwable frameFailure) {
            consecutiveFailures++;
            reportedFailures++;
            String failurePhase = currentPhase;
            // Key this to total reports, not consecutive failures. An
            // intermittent fault has successful frames between occurrences;
            // the old counter therefore reran the bisect and emitted a full
            // crash report forever.
            String noRenderResult = "";
            if (reportedFailures == 1) {
                try {
                    minecraft.runTick(false);
                    noRenderResult = "runTick(false)=OK";
                } catch (Throwable noRenderFailure) {
                    noRenderResult = "runTick(false)=" + noRenderFailure.getClass().getName()
                            + ":" + noRenderFailure.getMessage();
                }
            }
            // A failing frame used to latch `failed` forever, so one bad frame
            // ended the session: the screen froze on whatever was last drawn and
            // every later frame returned immediately. That is why terrain
            // "stopped drawing" two frames after it started -- the pump was dead,
            // not the renderer. Every report so far says runTick(false)=OK, so
            // the simulation survives and only rendering threw; dropping the
            // frame and trying again is strictly better than ending the run.
            // Still bounded, so a permanently broken renderer cannot spin.
            if (consecutiveFailures >= MAX_CONSECUTIVE_FAILURES) {
                failed = true;
            }
            if (reportedFailures > MAX_REPORTED_FAILURES) {
                return;
            }
            if (reportedFailures == 1
                    && frameFailure instanceof net.minecraft.ReportedException reported) {
                BrowserMinecraftMain.reportCrashReport(
                        "frame:crash-report:", reported.getReport());
            }
            String detail = BrowserMinecraftMain.describeFailure(frameFailure)
                    + " | phase=" + failurePhase
                    + " | consecutive=" + consecutiveFailures
                    + " | reported=" + reportedFailures + "/" + MAX_REPORTED_FAILURES
                    + (noRenderResult.isEmpty() ? "" : " | " + noRenderResult);
            // A bare OutOfMemoryError carries no message and no useful cause
            // chain, so the report on its own says nothing about what exhausted
            // the heap. The boot path already dumps heap totals plus the largest
            // live native blocks; the frame pump needs the same, because this is
            // where terrain meshing actually runs out of memory.
            if (BrowserMinecraftMain.isHeapExhaustion(frameFailure)) {
                detail += " | " + BrowserMinecraftMain.describeHeapAtFailure()
                        + " | headroom=" + probeHeadroom();
            }
            detail += " | at " + topFrames(frameFailure, 12);
            BrowserGpu.reportJavaFailure(
                    "frame#" + frameCount,
                    frameFailure.getClass().getName(),
                    detail
            );
        }
    }

    /**
     * Unloads the game and hands the page back to the launcher.
     *
     * <p>The page reloads rather than reconstructing Minecraft in place: the
     * image's statics, the render system and the GPU objects are all built once
     * per {@code main}, so a second {@code new Minecraft(GameConfig)} in the
     * same heap is not a supported state. The runtime is already in Cache
     * Storage, so the return trip is the launcher's ordinary warm start.</p>
     */
    private static void handleQuit() {
        currentPhase = "frame.quit";
        BrowserGpu.reportProgress("lifecycle:quit-requested");
        try {
            BrowserMultiplayerCompat.disconnect("quit");
        } catch (Throwable ignored) {
            // A half-open connection must not block the exit.
        }
        try {
            BrowserPersistentStorage.syncClientFilesNow();
        } catch (Throwable ignored) {
            // Options and server list are best-effort at this point.
        }
        // No further frames: the page owns the tear-down from here.
        failed = true;
        BrowserGpu.reportProgress("lifecycle:quit-handed-to-page");
        returnToLauncher();
    }

    @JS.Coerce
    @JS(value = "globalThis.mcWebLifecycle?.quitToLauncher?.();", args = {})
    private static native void returnToLauncher();

    private static void updateBrowserFramePacing() {
        FramerateLimitTracker tracker = minecraft.getFramerateLimitTracker();
        FramerateThrottleReason throttleReason = tracker.getThrottleReason();
        int configuredLimit = minecraft.options.framerateLimit().get();
        int effectiveLimit = tracker.getFramerateLimit();
        // Desktop Minecraft hard-codes 60 FPS while no ClientLevel exists.
        // That is useful for a native title screen, but it became an arbitrary
        // browser cap during boot, menus, and dimension hand-off even though
        // the user selected a higher limit and the host already has a finite
        // runaway-safety ceiling. Preserve the meaningful iconified/AFK
        // throttles; only the out-of-level 60 FPS special case is omitted.
        int selectedLimit = throttleReason == FramerateThrottleReason.OUT_OF_LEVEL_MENU
                ? configuredLimit
                : effectiveLimit;
        int frameLimit = Math.max(1, Math.min(260, selectedLimit));
        boolean vsync = minecraft.options.enableVsync().get();
        if (frameLimit == lastBrowserFrameLimit && vsync == lastBrowserVsync) {
            return;
        }
        lastBrowserFrameLimit = frameLimit;
        lastBrowserVsync = vsync;
        BrowserGpu.configureFramePacing(vsync, frameLimit);
        BrowserGpu.reportProgress("frame-pacing:vsync=" + vsync
                + " maxFps=" + frameLimit
                + " configured=" + configuredLimit
                + " tracker=" + effectiveLimit
                + " reason=" + throttleReason);
    }

    @JS.Coerce
    @JS(value = "const c=globalThis.mcWebThreadRuntime?.imageCounters?.();"
            + "return c && typeof c.gcStopped === 'number' && typeof c.gcUncontended === 'number'"
            + " ? c.gcStopped + c.gcUncontended : -1;", args = {})
    private static native int observedCollections();

    @JS.Coerce
    @JS(value = "const c=globalThis.mcWebThreadRuntime?.imageCounters?.();"
            + "return c && typeof c.gcAgentRefused === 'number' ? c.gcAgentRefused : -1;", args = {})
    private static native int observedAgentRefusals();

    /**
     * Identity of the browser thread as Minecraft's event loop sees it.
     *
     * <p>{@code BlockableEventLoop.isSameThread()} is a reference comparison of
     * {@code Thread.currentThread()} against the loop's running thread, and the
     * whole client depends on it: {@code Minecraft.execute} runs a task inline
     * only when it holds, and the transformed {@code blockUntilDone} drains the
     * main queue only when it holds. If {@code Thread.currentThread()} does not
     * return one stable object on the primary WasmLM agent, every deferred task
     * — including every UI click — is queued and never run, and the wait loops
     * spin instead of draining.</p>
     */
    private static String describeThreadIdentity() {
        StringBuilder state = new StringBuilder();
        try {
            Thread first = Thread.currentThread();
            Thread second = Thread.currentThread();
            state.append("stable=").append(first == second)
                    .append(" id=").append(first.getId())
                    .append(" name=").append(first.getName())
                    .append(" hash=").append(System.identityHashCode(first))
                    .append(" secondHash=").append(System.identityHashCode(second))
                    .append(" sameThread=").append(minecraft.isSameThread())
                    .append(" mainQueue=").append(minecraft.getPendingTasksCount());
        } catch (Throwable failure) {
            state.append(" identity-failed=").append(failure.getClass().getSimpleName());
        }
        try {
            state.append(' ').append(AgentExecutorService.stats());
        } catch (Throwable failure) {
            state.append(" pool-failed=").append(failure.getClass().getSimpleName());
        }
        return state.toString();
    }

    /**
     * How much the heap will still hand out immediately after an
     * OutOfMemoryError.
     *
     * <p>This settles the question every other measurement has dodged. A bare
     * {@code OutOfMemoryError} carries no message, Web Image strips stack
     * traces, {@code Runtime}'s numbers are inert here (used always reads 0),
     * and {@code performance.memory} covers only the JS heap — not the WasmGC
     * one. So the heap is asked directly: if 64 MiB still allocates the instant
     * after the throw, nothing was exhausted and the failure was one oversized
     * or pathological request; if even 1 MiB fails, it is a genuine ceiling and
     * the fix is to shrink the resident set.</p>
     */
    private static String probeHeadroom() {
        StringBuilder result = new StringBuilder();
        for (int megabytes : new int[] {1, 8, 32, 64, 128}) {
            boolean ok;
            try {
                byte[] probe = new byte[megabytes * 1024 * 1024];
                // Touch both ends so the allocation cannot be elided.
                probe[0] = 1;
                probe[probe.length - 1] = 1;
                ok = true;
            } catch (Throwable outOfMemory) {
                ok = false;
            }
            result.append(megabytes).append("MiB=").append(ok ? "ok" : "FAIL").append(' ');
        }
        return result.toString().trim();
    }

    private static String lastTerrainState;
    private static long nextTerrainReportFrame;

    /**
     * Reports the state of the chunk-section render pipeline.
     *
     * <p>In-world the frame draws sky, fog, entities and the HUD but no block
     * geometry, and the F3 overlay omits its section-count line entirely. That
     * narrows the fault to the section pipeline, but not to a stage within it,
     * so this walks the pipeline end to end: whether the {@code ViewArea}
     * exists at all, how many sections it holds, how many the occlusion graph
     * considers visible, and — for those visible sections — how many actually
     * carry a compiled mesh with renderable layers.</p>
     *
     * <p>The distinction is what identifies the culprit. Sections present but
     * none visible means the occlusion/frustum stage; sections visible but no
     * renderable meshes means the compile or upload stage; no ViewArea at all
     * means the renderer was never set up for the level.</p>
     */
    private static void reportTerrainState() {
        if (minecraft.level == null || frameCount < nextTerrainReportFrame) {
            return;
        }
        // Sampling: this walks the visible-section list, which is cheap next to
        // a frame but not free, and the numbers only matter as they change.
        // Sample every frame at first, then back off. The intermittent OOM ends
        // in-world runs after a few dozen frames, and at any coarser interval
        // every run so far produced exactly one sample -- which cannot tell a
        // state that never changes from a probe that only ran once. Several
        // consecutive frames are what make per-frame churn (a ViewArea rebuilt
        // every frame, say) visible at all.
        nextTerrainReportFrame = frameCount + (terrainReportCount < 40 ? 1 : 60);
        terrainReportCount++;
        String state;
        try {
            net.minecraft.client.renderer.LevelRenderer renderer = minecraft.levelRenderer;
            if (renderer == null) {
                state = "levelRenderer=null";
            } else {
                net.minecraft.client.renderer.ViewArea area = renderer.viewArea();
                if (area == null) {
                    state = "viewArea=null";
                } else {
                    var visible = renderer.visibleSections();
                    int withMesh = 0;
                    int renderable = 0;
                    for (var section : visible) {
                        net.minecraft.client.renderer.chunk.SectionMesh mesh = section.getSectionMesh();
                        if (mesh != null) {
                            withMesh++;
                            if (mesh.hasRenderableLayers()) {
                                renderable++;
                            }
                        }
                    }
                    // visible == 0 cannot distinguish "no meshes were ever
                    // compiled" from "meshes exist but the occlusion graph
                    // culls them all", and those have opposite fixes. Sampling
                    // the sections around the player answers it directly,
                    // because it bypasses visibility entirely.
                    int sampled = 0;
                    int sampledWithMesh = 0;
                    int sampledRenderable = 0;
                    int sampledWithNode = 0;
                    var graph = renderer.sectionOcclusionGraph();
                    int sampledUncompiled = 0;
                    int sampledEmpty = 0;
                    int sampledCompiled = 0;
                    if (minecraft.player != null) {
                        net.minecraft.core.BlockPos origin = minecraft.player.blockPosition();
                        for (int dx = -32; dx <= 32; dx += 16) {
                            for (int dz = -32; dz <= 32; dz += 16) {
                                for (int dy = -32; dy <= 16; dy += 16) {
                                    var section = area.getRenderSectionAt(origin.offset(dx, dy, dz));
                                    if (section == null) {
                                        continue;
                                    }
                                    sampled++;
                                    var mesh = section.getSectionMesh();
                                    if (mesh != null) {
                                        sampledWithMesh++;
                                        if (mesh.hasRenderableLayers()) {
                                            sampledRenderable++;
                                        }
                                        // A non-null mesh proves nothing on its
                                        // own: CompiledSectionMesh.UNCOMPILED
                                        // and EMPTY are shared sentinels that
                                        // inherit hasRenderableLayers() = false
                                        // from the interface default. Only the
                                        // identity of the instance separates
                                        // "never compiled" from "compiled to
                                        // nothing", and those have unrelated
                                        // fixes.
                                        // The occlusion graph only has a Node
                                        // for a section once its full update
                                        // has actually run. That update is a
                                        // CompletableFuture.runAsync on
                                        // Util.backgroundExecutor(), which this
                                        // port replaces -- so "graph has no
                                        // nodes" (update never ran) and "graph
                                        // built but the frustum culls all of
                                        // it" both present as visible == 0 and
                                        // need opposite fixes.
                                        if (graph != null && graph.getNode(section) != null) {
                                            sampledWithNode++;
                                        }
                                        if (mesh == net.minecraft.client.renderer.chunk.CompiledSectionMesh.UNCOMPILED) {
                                            sampledUncompiled++;
                                        } else if (mesh == net.minecraft.client.renderer.chunk.CompiledSectionMesh.EMPTY) {
                                            sampledEmpty++;
                                        } else {
                                            sampledCompiled++;
                                        }
                                    }
                                }
                            }
                        }
                    }
                    // Every sampled section has a mesh yet none has renderable
                    // layers, so the compile step is producing empty results.
                    // That has two very different causes: the client level
                    // really is air here (chunk payload never applied), or the
                    // blocks exist and the mesh build drops them (models,
                    // atlas, buffer upload). Counting non-air blocks in the
                    // client level under the player separates the two.
                    int solidBelow = 0;
                    String groundBlock = "n/a";
                    if (minecraft.player != null) {
                        net.minecraft.core.BlockPos origin = minecraft.player.blockPosition();
                        for (int dy = 0; dy < 40; dy++) {
                            net.minecraft.core.BlockPos probe = origin.below(dy);
                            var blockState = minecraft.level.getBlockState(probe);
                            if (!blockState.isAir()) {
                                solidBelow++;
                                if (groundBlock.equals("n/a")) {
                                    groundBlock = blockState.getBlock().toString();
                                }
                            }
                        }
                    }
                    // Visibility comes from an Octree built around the view
                    // area's camera section, and Octree.add() rejects anything
                    // outside those bounds. If the camera section is stale --
                    // still the origin from before the player spawned, say --
                    // every add fails and the octree stays empty, which is
                    // indistinguishable from "propagation never ran" in every
                    // other number here.
                    String cameraSection = "n/a";
                    String playerSection = "n/a";
                    try {
                        cameraSection = String.valueOf(area.getCameraSectionPos());
                        if (minecraft.player != null) {
                            playerSection = String.valueOf(
                                    net.minecraft.core.SectionPos.of(minecraft.player.blockPosition())
                            );
                        }
                    } catch (Throwable ignored) {
                        cameraSection = "failed";
                    }
                    // The exact count of chunks the client has actually
                    // received and stored. Every other number here is derived
                    // from the render graph, which can lag or cull; this one
                    // answers "did the chunk arrive at all" on its own, and is
                    // the client-side counterpart to the server's trackedReady.
                    String clientChunks = "n/a";
                    if (minecraft.level.getChunkSource()
                            instanceof net.minecraft.client.multiplayer.ClientChunkCache cache) {
                        clientChunks = cache.getLoadedChunksCount() + " " + cache.gatherStats();
                    }
                    state = "total=" + area.size()
                            + " clientChunks=" + clientChunks
                            + " cameraSection=" + cameraSection
                            + " playerSection=" + playerSection
                            + " height=" + area.sectionCount()
                            + " viewDistance=" + area.getViewDistance()
                            + " visible=" + visible.size()
                            + " nearbyVisible=" + renderer.nearbyVisibleSections().size()
                            + " visWithMesh=" + withMesh
                            + " visRenderable=" + renderable
                            + " sampled=" + sampled
                            + " sampledWithMesh=" + sampledWithMesh
                            + " sampledRenderable=" + sampledRenderable
                            + " withNode=" + sampledWithNode
                            + " expectedChunks=" + (graph == null ? "n/a" : graph.expectedChunks().size())
                            + " uncompiled=" + sampledUncompiled
                            + " empty=" + sampledEmpty
                            + " compiled=" + sampledCompiled
                            + " solidBelow=" + solidBelow
                            + " ground=" + groundBlock
                            + " dispatcher=[" + dispatcherState(renderer) + "]"
                            + " occlusion=[" + occlusionState(renderer, graph) + "]";
                }
            }
        } catch (Throwable probeFailure) {
            state = "probe-failed:" + probeFailure.getClass().getName()
                    + ":" + probeFailure.getMessage();
        }
        if (!state.equals(lastTerrainState)) {
            lastTerrainState = state;
            BrowserGpu.reportProgress("terrain:" + state);
        }
    }

    /**
     * State of the step that fills {@code visibleSections}: the octree census
     * and the frustum that filters it.
     *
     * <p>{@code visible=0} has three surviving causes that every other number
     * here reports identically, so each gets its own measurement.</p>
     *
     * <ul>
     * <li>{@code inTree} — sections the octree holds at all, counted through a
     * deliberately permissive frustum. Zero means propagation never reached
     * {@code Octree.add}; non-zero rules the octree out, which a host-JVM
     * harness against the real jar already did for the insert path itself
     * (75/75 added).</li>
     * <li>{@code inFrustum} — the same walk under the real cull frustum. A gap
     * against {@code inTree} means the frustum is culling everything.</li>
     * <li>{@code offsetIters} — {@code addSectionsInFrustum} does not use the
     * cull frustum directly, it uses
     * {@code offsetToFullyIncludeCameraCube(8)}, an <em>unbounded</em> loop
     * that walks the camera back along {@code viewVector} until an 8-block cube
     * is fully inside. That vector is row 2 of projection*view, so a projection
     * built with reverse-Z inverts its sign and shrinks it by ~20000x: measured
     * on the host JVM, the vanilla forward-Z culling projection converges in 4
     * iterations while a reverse-Z one never converges. This replicates the
     * loop with a bound rather than calling it, because the real method would
     * hang the frame instead of reporting the problem.</li>
     * </ul>
     */
    private static String occlusionState(
            final net.minecraft.client.renderer.LevelRenderer renderer,
            final net.minecraft.client.renderer.SectionOcclusionGraph graph) {
        try {
            if (graph == null) {
                return "graph=null";
            }
            net.minecraft.client.Camera camera = minecraft.gameRenderer.mainCamera();
            net.minecraft.client.renderer.culling.Frustum cull = camera.getCullFrustum();
            if (cull == null) {
                return "cullFrustum=null";
            }
            net.minecraft.client.renderer.Octree tree = graph.getOctree();
            if (tree == null) {
                return "octree=null";
            }

            // offset(t) advances the camera by viewVector*t, and is the only
            // public surface that exposes the vector the offset loop steps by.
            net.minecraft.client.renderer.culling.Frustum vectorProbe =
                    new net.minecraft.client.renderer.culling.Frustum(cull);
            double baseX = vectorProbe.getCamX();
            double baseY = vectorProbe.getCamY();
            double baseZ = vectorProbe.getCamZ();
            vectorProbe.offset(1.0F);
            double viewX = vectorProbe.getCamX() - baseX;
            double viewY = vectorProbe.getCamY() - baseY;
            double viewZ = vectorProbe.getCamZ() - baseZ;

            int[] inTree = countOctree(tree, permissiveFrustum(baseX, baseY, baseZ));
            int[] inFrustum = countOctree(tree, cull);
            // The one that matters: addSectionsInFrustum does not walk with the
            // cull frustum, it walks with offsetFrustum(cull) -- the camera
            // pushed back along viewVector until its own 8-block cube is fully
            // inside. inFrustum counts what the plain cull frustum sees;
            // inOffset counts what applyFrustum would actually have collected.
            // A gap between them means the offset step is the fault.
            net.minecraft.client.renderer.culling.Frustum offset =
                    new net.minecraft.client.renderer.culling.Frustum(cull);
            String iterations = offsetConvergence(offset);
            int[] inOffset = countOctree(tree, offset);
            return "inTree=" + inTree[1] + "/" + inTree[0]
                    + " inFrustum=" + inFrustum[1] + "/" + inFrustum[0]
                    + " inOffset=" + inOffset[1] + "/" + inOffset[0]
                    + " offsetCam=(" + round(offset.getCamX() - baseX)
                    + "," + round(offset.getCamY() - baseY)
                    + "," + round(offset.getCamZ() - baseZ) + ")"
                    + " view=(" + round(viewX) + "," + round(viewY) + "," + round(viewZ) + ")"
                    + " offsetIters=" + iterations
                    + " shape=" + frustumShape(cull)
                    + " window=" + minecraft.getWindow().getWidth()
                    + "x" + minecraft.getWindow().getHeight()
                    + " fov=" + minecraft.options.fov().get()
                    + " zZeroToOne=" + RenderSystem.getDevice().getDeviceInfo().isZZeroToOne()
                    + " " + applyFrustumGates(renderer, camera);
        } catch (Throwable probeFailure) {
            return "failed:" + probeFailure.getClass().getName() + ":" + probeFailure.getMessage();
        }
    }

    /**
     * The three conditions that can stop {@code LevelExtractor.extract} from
     * calling {@code applyFrustum}, which is the only writer of
     * {@code visibleSections}.
     *
     * <p>Measured 2026-07-27: the octree holds sections and the cull frustum
     * accepts them ({@code inTree=6}, {@code inFrustum=5}) while
     * {@code visible=0}, so the walk is fine and the call is simply not
     * happening. extract guards it with</p>
     *
     * <pre>
     * if (shouldInvalidateCompiledGeometry) { invalidateCompiledGeometry(); }
     * else if (camera.getCapturedFrustum() == null
     *          &amp;&amp; (consumeFrustumUpdate() || camera rotated)) { applyFrustum(); }
     * </pre>
     *
     * <p>and {@code applyFrustum} itself throws unless
     * {@code Minecraft.isSameThread()}. Each field below pins one of those:
     * {@code sameThread=false} means it throws on entry (and, because
     * {@code consumeFrustumUpdate} is a CAS evaluated first, the flag is
     * consumed and lost, so the state never recovers); {@code captured=true}
     * means the debug frustum-capture branch is swallowing it;
     * {@code viewAreaAge} counting 0 every report means
     * {@code invalidateCompiledGeometry} is re-arming every frame and the
     * {@code else} branch never runs at all.</p>
     */
    private static String applyFrustumGates(
            final net.minecraft.client.renderer.LevelRenderer renderer,
            final net.minecraft.client.Camera camera) {
        String sameThread;
        String threadStable;
        try {
            sameThread = String.valueOf(minecraft.isSameThread());
            // Web Image's thread model is synthetic, so identity of the current
            // thread is worth confirming rather than assuming: if
            // Thread.currentThread() does not return a stable object, every
            // isSameThread() in the game is false and this is the shared cause.
            threadStable = String.valueOf(Thread.currentThread() == Thread.currentThread());
        } catch (Throwable threadFailure) {
            sameThread = "failed";
            threadStable = "failed";
        }
        String captured;
        try {
            captured = String.valueOf(camera.getCapturedFrustum() != null);
        } catch (Throwable capturedFailure) {
            captured = "failed";
        }
        // invalidateCompiledGeometry is the only thing that builds a ViewArea,
        // and it also calls clearVisibleSections() and waitAndReset(). So a
        // rising generation count is a direct count of how often extract takes
        // the invalidate branch -- the branch whose "else" holds applyFrustum.
        // Comparing the generation delta against the report delta says whether
        // it is every frame or occasional.
        net.minecraft.client.renderer.ViewArea area = renderer.viewArea();
        if (area != lastViewArea) {
            lastViewArea = area;
            viewAreaGeneration++;
        }
        // allChanged() is the only setter of shouldInvalidateCompiledGeometry,
        // and extract calls it when getEffectiveRenderDistance() disagrees with
        // the value allChanged itself last stored. If these two disagree, that
        // comparison is the re-arming loop; if they agree, the trigger is a
        // repeated setLevel instead.
        String effective;
        String option;
        try {
            effective = String.valueOf(minecraft.options.getEffectiveRenderDistance());
            option = String.valueOf(minecraft.options.renderDistance().get());
        } catch (Throwable optionFailure) {
            effective = "failed";
            option = "failed";
        }
        // Deliberately not reading needsFrustumUpdate here. consumeFrustumUpdate
        // is a compareAndSet, so reading it steals it from extract, and the
        // reading is confounded anyway: the full update that runs later in the
        // same frame (inside LevelRenderer.render, after extract) sets the flag
        // again, so finding it set after runTick says nothing about whether
        // extract consumed it.
        return "sameThread=" + sameThread
                + " threadStable=" + threadStable
                + " captured=" + captured
                + " viewAreaGen=" + viewAreaGeneration
                + " reports=" + terrainReportCount
                + " rdEffective=" + effective
                + " rdOption=" + option
                + " areaViewDistance=" + area.getViewDistance();
    }

    /** Sections the octree yields under {@code frustum}, as {nodes, sections}. */
    private static int[] countOctree(
            final net.minecraft.client.renderer.Octree tree,
            final net.minecraft.client.renderer.culling.Frustum frustum) {
        int[] counts = new int[2];
        tree.visitNodes((node, fullyInside, depth, nearby) -> {
            counts[0]++;
            if (node.getSection() != null) {
                counts[1]++;
            }
        }, frustum, 32);
        return counts;
    }

    /**
     * A frustum that accepts everything near the camera, so an octree walk
     * counts membership rather than visibility.
     */
    private static net.minecraft.client.renderer.culling.Frustum permissiveFrustum(
            final double camX, final double camY, final double camZ) {
        org.joml.Matrix4f wide = new org.joml.Matrix4f()
                .setOrtho(-1.0E7F, 1.0E7F, -1.0E7F, 1.0E7F, -1.0E7F, 1.0E7F, true);
        net.minecraft.client.renderer.culling.Frustum frustum =
                new net.minecraft.client.renderer.culling.Frustum(new org.joml.Matrix4f(), wide);
        frustum.prepare(camX, camY, camZ);
        return frustum;
    }

    /**
     * Iterations {@code offsetToFullyIncludeCameraCube(8)} would need, or
     * {@code >bound} if it would not terminate.
     */
    private static String offsetConvergence(final net.minecraft.client.renderer.culling.Frustum probe) {
        final int cube = 8;
        final int bound = 4096;
        int minX = (int) (Math.floor(probe.getCamX() / cube) * cube);
        int minY = (int) (Math.floor(probe.getCamY() / cube) * cube);
        int minZ = (int) (Math.floor(probe.getCamZ() / cube) * cube);
        int maxX = (int) (Math.ceil(probe.getCamX() / cube) * cube);
        int maxY = (int) (Math.ceil(probe.getCamY() / cube) * cube);
        int maxZ = (int) (Math.ceil(probe.getCamZ() / cube) * cube);
        // cubeInFrustum(BoundingBox) tests min..max+1, so the exclusive upper
        // corner has to be passed as max-1 to match the real loop's box.
        net.minecraft.world.level.levelgen.structure.BoundingBox box =
                new net.minecraft.world.level.levelgen.structure.BoundingBox(
                        minX, minY, minZ, maxX - 1, maxY - 1, maxZ - 1);
        for (int i = 0; i < bound; i++) {
            if (probe.cubeInFrustum(box) == -2) {
                return String.valueOf(i);
            }
            probe.offset(-4.0F);
        }
        return ">" + bound;
    }

    /**
     * Extent of the frustum's eight corners, which separates "the frustum is
     * pointing somewhere unhelpful" from "the frustum is not a view volume at
     * all" — a singular or reverse-Z projection shows up here as NaN, infinite
     * or collapsed spans.
     */
    private static String frustumShape(final net.minecraft.client.renderer.culling.Frustum cull) {
        try {
            org.joml.Vector4f[] points = cull.getFrustumPoints();
            if (points == null || points.length == 0) {
                return "none";
            }
            float minX = Float.MAX_VALUE;
            float minY = Float.MAX_VALUE;
            float minZ = Float.MAX_VALUE;
            float maxX = -Float.MAX_VALUE;
            float maxY = -Float.MAX_VALUE;
            float maxZ = -Float.MAX_VALUE;
            boolean finite = true;
            for (org.joml.Vector4f point : points) {
                finite &= Float.isFinite(point.x()) && Float.isFinite(point.y()) && Float.isFinite(point.z());
                minX = Math.min(minX, point.x());
                minY = Math.min(minY, point.y());
                minZ = Math.min(minZ, point.z());
                maxX = Math.max(maxX, point.x());
                maxY = Math.max(maxY, point.y());
                maxZ = Math.max(maxZ, point.z());
            }
            return "span(" + round(maxX - minX) + "," + round(maxY - minY) + "," + round(maxZ - minZ)
                    + ")finite=" + finite;
        } catch (Throwable shapeFailure) {
            return "failed:" + shapeFailure.getClass().getName();
        }
    }

    private static String round(final double value) {
        return String.valueOf(Math.round(value * 1.0E6) / 1.0E6);
    }

    /**
     * Compile-queue state, which says whether work is stuck or simply absent.
     * A non-empty queue that never drains points at the executor; an empty
     * queue with uncompiled sections means nothing was ever scheduled.
     */
    private static String dispatcherState(final net.minecraft.client.renderer.LevelRenderer renderer) {
        try {
            var dispatcher = renderer.sectionRenderDispatcher();
            if (dispatcher == null) {
                return "null";
            }
            return "queue=" + dispatcher.getCompileQueueSize()
                    + " empty=" + dispatcher.isQueueEmpty()
                    + " freeBuffers=" + dispatcher.getFreeBufferCount()
                    + " stats=" + dispatcher.getStats();
        } catch (Throwable probeFailure) {
            return "failed:" + probeFailure.getClass().getName();
        }
    }

    /**
     * Top stack frames of a failure, innermost first.
     *
     * <p>The reported stage is only the last {@code reportProgress} marker, so
     * it is routinely stale and has already sent debugging down the wrong path
     * once. The actual throw site is what identifies the culprit.</p>
     */
    private static String topFrames(final Throwable failure, final int limit) {
        try {
            StackTraceElement[] trace = failure.getStackTrace();
            if (trace == null || trace.length == 0) {
                return "<no stack trace>";
            }
            StringBuilder frames = new StringBuilder();
            for (int i = 0; i < Math.min(limit, trace.length); i++) {
                if (i > 0) {
                    frames.append(" < ");
                }
                frames.append(trace[i]);
            }
            return frames.toString();
        } catch (Throwable dumpFailure) {
            return "<stack-dump-failed:" + dumpFailure.getClass().getName() + ">";
        }
    }

    /**
     * Reports client world/player presence when it changes. The screen class
     * alone cannot tell "still loading" from "in the world" — both can render
     * with no screen — so this is what marks world entry as actually complete.
     */
    private static void reportWorldState() {
        String state = (minecraft.level == null ? "level=none" : "level=" + minecraft.level.dimension().identifier())
                + " player=" + (minecraft.player == null ? "none" : "present");
        if (!state.equals(lastWorldState)) {
            lastWorldState = state;
            BrowserGpu.reportProgress("world-state:" + state);
        }
    }

    /**
     * Low-rate, opt-in-friendly state sampling for the gameplay smoke test. It
     * contains only vanilla state already owned by Minecraft; it is not a second
     * input or world simulation path.
     */
    private static String lastGameplayState;
    private static long nextGameplayReportFrame;
    private static boolean lastScreenWasDeath;

    private static void reportGameplayState() {
        if (minecraft.level == null || minecraft.player == null || frameCount < nextGameplayReportFrame) {
            return;
        }
        nextGameplayReportFrame = frameCount + 15;
        try {
            net.minecraft.world.phys.HitResult hit = minecraft.hitResult;
            String target = "none";
            if (hit instanceof net.minecraft.world.phys.BlockHitResult blockHit) {
                net.minecraft.core.BlockPos pos = blockHit.getBlockPos();
                target = pos.getX() + "," + pos.getY() + "," + pos.getZ();
                // What the CLIENT believes is at the crosshair. "The block did
                // not break" is this staying non-air a second after the dig, so
                // the probe needs the block, not only the position.
                target += "@" + net.minecraft.core.registries.BuiltInRegistries.BLOCK
                        .getKey(minecraft.level.getBlockState(pos).getBlock()).getPath();
            } else if (hit != null) {
                target = hit.getType().name();
            }
            // Entity census: total entities in the client level plus the one
            // nearest the player. A pick that never targets an entity while
            // this shows one adjacent means the client entity index or the
            // pick's entity pass is broken.
            int entityCount = 0;
            String nearestEntity = "none";
            double nearestDistance = Double.MAX_VALUE;
            try {
                for (net.minecraft.world.entity.Entity entity : minecraft.level.entitiesForRendering()) {
                    if (entity == minecraft.player) {
                        continue;
                    }
                    entityCount++;
                    double distance = entity.distanceToSqr(minecraft.player);
                    if (distance < nearestDistance) {
                        nearestDistance = distance;
                        nearestEntity = entity.getType().getDescriptionId()
                                + "@" + Math.round(entity.getX() * 10.0) / 10.0
                                + "," + Math.round(entity.getY() * 10.0) / 10.0
                                + "," + Math.round(entity.getZ() * 10.0) / 10.0;
                    }
                }
            } catch (Throwable ignored) {
                // Census is diagnostic; a failure must not stop the report.
            }
            String state = "x=" + Math.round(minecraft.player.getX() * 100.0) / 100.0
                    + " y=" + Math.round(minecraft.player.getY() * 100.0) / 100.0
                    + " z=" + Math.round(minecraft.player.getZ() * 100.0) / 100.0
                    + " yaw=" + Math.round(minecraft.player.getYRot() * 10.0) / 10.0
                    + " pitch=" + Math.round(minecraft.player.getXRot() * 10.0) / 10.0
                    + " hp=" + Math.round(minecraft.player.getHealth() * 10.0) / 10.0
                    + " entities=" + entityCount
                    + " nearest=" + nearestEntity
                    + " target=" + target
                    + " screen=" + (minecraft.gui.screen() == null
                            ? "<none>" : minecraft.gui.screen().getClass().getSimpleName());
            if (!state.equals(lastGameplayState)) {
                lastGameplayState = state;
                BrowserGpu.reportProgress("gameplay-state:" + state);
            }
            // Death at superflat survival spawn is a port anomaly worth
            // pinning: record the transition the moment it happens, with the
            // position the player occupied when health hit zero.
            if (minecraft.gui.screen() instanceof net.minecraft.client.gui.screens.DeathScreen
                    && !lastScreenWasDeath) {
                lastScreenWasDeath = true;
                String cause = "?";
                try {
                    net.minecraft.client.gui.screens.Screen deathScreen = minecraft.gui.screen();
                    if (deathScreen != null) {
                        cause = deathScreen.getTitle().getString().replace('\n', ' ');
                    }
                } catch (Throwable ignored) {
                    // Diagnostic only.
                }
                BrowserGpu.reportProgress("player-death:"
                        + " cause=" + cause
                        + " x=" + Math.round(minecraft.player.getX() * 100.0) / 100.0
                        + " y=" + Math.round(minecraft.player.getY() * 100.0) / 100.0
                        + " z=" + Math.round(minecraft.player.getZ() * 100.0) / 100.0);
            } else if (!(minecraft.gui.screen() instanceof net.minecraft.client.gui.screens.DeathScreen)) {
                lastScreenWasDeath = false;
            }
        } catch (Throwable ignored) {
            // Diagnostics must never make a frame fail.
        }
    }
}
