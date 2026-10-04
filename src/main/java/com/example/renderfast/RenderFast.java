package com.example.renderfast;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.TicketType;
import net.minecraft.server.permissions.Permissions;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.storage.LevelResource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.Consumer;
import java.util.function.Supplier;

import static net.minecraft.commands.Commands.argument;
import static net.minecraft.commands.Commands.literal;

public class RenderFast implements ModInitializer {
	public static final String MOD_ID = "renderfast";
	public static final Logger LOGGER = LoggerFactory.getLogger("RenderFast");
	public static RenderFastConfig CONFIG;

	private static short[] offsetX;
	private static short[] offsetZ;
	private static int totalChunks;
	private static int chunksSinceLastSave = 0;
	private static int nextRequestIndex = -1;
	private static long sessionStartTime = 0;
	private static long lastConsoleReportTime = 0;
	private static long currentTickStartTime = 0;
	private static RenderFastState state;
	private static ChunkStatus cachedTargetStatus;
	private static String currentPauseReason = "";

	private static final Set<Integer> inFlightIndices = Collections.synchronizedSet(new HashSet<>());
	private static final Map<Integer, Long> inFlightStartTimes = new ConcurrentHashMap<>();
	private static final Set<Integer> completedIndices = Collections.synchronizedSet(new HashSet<>());
	private static final ConcurrentLinkedQueue<Integer> pendingCompletionQueue = new ConcurrentLinkedQueue<>();

	private static final Deque<Long> timeWindow = new ArrayDeque<>();
	private static final Deque<Integer> countWindow = new ArrayDeque<>();
	private static float currentCps = 0;

	private static boolean inLagRecovery = false;
	private static int lastBroadcastDone = -1;
	private static boolean lastBroadcastActive = false;
	private static final int BROADCAST_INTERVAL_TICKS = 5;
	private static int tickCounter = 0;

	private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
			.followRedirects(HttpClient.Redirect.NORMAL)
			.build();

	@Override
	public void onInitialize() {
		CONFIG = RenderFastConfig.load();
		buildSpiral(CONFIG.radius);

		PayloadTypeRegistry.clientboundPlay().register(RenderFastProgressPayload.TYPE, RenderFastProgressPayload.CODEC);
		CommandRegistrationCallback.EVENT.register((dispatcher, _, _) -> registerCommands(dispatcher));

		ServerPlayConnectionEvents.JOIN.register((handler, _, server) -> {
			ensureState(server);
			sendProgress(handler.getPlayer());
		});

		ServerTickEvents.START_SERVER_TICK.register(server -> {
			currentTickStartTime = System.currentTimeMillis();
			if (state != null && state.started && !state.completed) {
				ServerLevel level = getLevelForDimension(server, state.dimension);
				if (level != null) {
					processCompletions(level);
				}
			}
		});

		ServerTickEvents.END_SERVER_TICK.register(this::onServerTick);
		ServerLifecycleEvents.SERVER_STOPPING.register(_ -> stopAllPreloading());
	}

	private static void registerCommands(CommandDispatcher<CommandSourceStack> dispatcher) {
		var root = literal("renderfast")
				.requires(s -> s.permissions().hasPermission(Permissions.COMMANDS_ADMIN));

		root.then(literal("start")
				.executes(c -> {
					ensureState(c.getSource().getServer());
					BlockPos p = BlockPos.containing(c.getSource().getPosition());
					if (canContinue(c.getSource().getLevel(), p.getX() >> 4, p.getZ() >> 4, CONFIG.radius)) {
						if (state.paused) {
							state.setPaused(false);
							currentPauseReason = "";
							c.getSource().sendSuccess(() -> Component.literal("Resuming task..."), true);
						} else {
							c.getSource().sendSuccess(() -> Component.literal("Already running here."), false);
						}
						return 1;
					}
					startPreload(c.getSource().getLevel(), p.getX() >> 4, p.getZ() >> 4, CONFIG.radius);
					c.getSource().sendSuccess(() -> Component.literal("Started preload."), true);
					return 1;
				})
				.then(argument("radius", IntegerArgumentType.integer(1))
						.executes(c -> {
							int r = IntegerArgumentType.getInteger(c, "radius");
							ensureState(c.getSource().getServer());
							BlockPos p = BlockPos.containing(c.getSource().getPosition());
							startPreload(c.getSource().getLevel(), p.getX() >> 4, p.getZ() >> 4, r);
							c.getSource().sendSuccess(() -> Component.literal("Started radius " + r), true);
							return 1;
						})));

		root.then(literal("pause")
				.executes(c -> {
					ensureState(c.getSource().getServer());
					if (!state.started || state.completed) return 0;
					state.setPaused(true);
					currentPauseReason = "MANUAL";
					c.getSource().sendSuccess(() -> Component.literal("Paused"), true);
					return 1;
				}));

		root.then(literal("resume")
				.executes(c -> {
					ensureState(c.getSource().getServer());
					if (!state.started || state.completed) return 0;
					state.setPaused(false);
					currentPauseReason = "";
					c.getSource().sendSuccess(() -> Component.literal("Resumed"), true);
					return 1;
				}));

		root.then(literal("stop")
				.executes(c -> {
					ensureState(c.getSource().getServer());
					if (!state.started || state.completed) return 0;
					state.markCompleted();
					c.getSource().sendSuccess(() -> Component.literal("Stopped"), true);
					return 1;
				}));

		root.then(literal("reset")
				.executes(c -> {
					ensureState(c.getSource().getServer());
					if (!state.started) return 0;
					BlockPos p = BlockPos.containing(c.getSource().getPosition());
					startPreload(c.getSource().getLevel(), p.getX() >> 4, p.getZ() >> 4, state.radius);
					c.getSource().sendSuccess(() -> Component.literal("Reset and restarted."), true);
					return 1;
				}));
		
		root.then(literal("status")
				.executes(c -> {
					ensureState(c.getSource().getServer());
					if (!state.started) {
						c.getSource().sendSuccess(() -> Component.literal("Not started"), false);
					} else if (state.completed) {
						c.getSource().sendSuccess(() -> Component.literal("Completed: " + state.doneCount + "/" + totalChunks), false);
					} else {
						int p = (int) (((float) state.doneCount / totalChunks) * 100);
						String msg = String.format(
								"§6Status:§r %d%% (%d/%d) | Speed: %.1f ch/s | ETA: %s | Throttled: %s",
								p, state.doneCount, totalChunks, currentCps, formatEta(),
								currentPauseReason.isEmpty() ? "None" : currentPauseReason
						);
						c.getSource().sendSuccess(() -> Component.literal(msg), false);
					}
					return 1;
				}));

		var config = literal("config");
		addBool(config, "enable", () -> CONFIG.enabled, v -> CONFIG.enabled = v);
		addBool(config, "turbo", () -> CONFIG.turboMode, v -> CONFIG.turboMode = v);
		addBool(config, "hud", () -> CONFIG.showHud, v -> CONFIG.showHud = v);
		addBool(config, "metrics", () -> CONFIG.showHudMetrics, v -> CONFIG.showHudMetrics = v);
		addBool(config, "minimap", () -> CONFIG.showMiniMap, v -> CONFIG.showMiniMap = v);
		addBool(config, "statusmsg", () -> CONFIG.showStatusMessages, v -> CONFIG.showStatusMessages = v);
		addBool(config, "adaptive", () -> CONFIG.adaptiveThrottling, v -> CONFIG.adaptiveThrottling = v);
		addBool(config, "watchdog", () -> CONFIG.watchdogBreather, v -> CONFIG.watchdogBreather = v);
		addBool(config, "pauseoffline", () -> CONFIG.pauseWhenEmpty, v -> CONFIG.pauseWhenEmpty = v);
		addBool(config, "pauseonline", () -> CONFIG.onlyPreloadWhenEmpty, v -> CONFIG.onlyPreloadWhenEmpty = v);
		addBool(config, "autoturbo", () -> CONFIG.autoTurboWhenEmpty, v -> CONFIG.autoTurboWhenEmpty = v);
		addBool(config, "voxy", () -> CONFIG.voxyIntegration, v -> CONFIG.voxyIntegration = v);
		addBool(config, "dh", () -> CONFIG.distantHorizonsIntegration, v -> CONFIG.distantHorizonsIntegration = v);
		addBool(config, "refill", () -> CONFIG.immediateRefill, v -> CONFIG.immediateRefill = v);
		addBool(config, "lighting", () -> CONFIG.lightingFixMode, v -> CONFIG.lightingFixMode = v);
		addBool(config, "structure", () -> CONFIG.structureOnlyMode, v -> CONFIG.structureOnlyMode = v);
		addBool(config, "gc", () -> CONFIG.aggressiveUnload, v -> CONFIG.aggressiveUnload = v);

		config.then(literal("radius")
				.then(argument("v", IntegerArgumentType.integer(1, 2048))
						.executes(c -> {
							CONFIG.radius = IntegerArgumentType.getInteger(c, "v");
							CONFIG.save();
							c.getSource().sendSuccess(() -> Component.literal("Radius: " + CONFIG.radius), true);
							return 1;
						})));
		config.then(literal("ram")
				.then(argument("p", IntegerArgumentType.integer(1, 100))
						.executes(c -> {
							CONFIG.memoryUsageThreshold = IntegerArgumentType.getInteger(c, "p") / 100.0;
							CONFIG.save();
							c.getSource().sendSuccess(() -> Component.literal("RAM limit: " + (CONFIG.memoryUsageThreshold * 100) + "%"), true);
							return 1;
						})));
		config.then(literal("disk")
				.then(argument("m", IntegerArgumentType.integer(1))
						.executes(c -> {
							CONFIG.minFreeDiskSpaceMb = IntegerArgumentType.getInteger(c, "m");
							CONFIG.save();
							c.getSource().sendSuccess(() -> Component.literal("Min Disk: " + CONFIG.minFreeDiskSpaceMb + "MB"), true);
							return 1;
						})));
		config.then(literal("timeout")
				.then(argument("s", IntegerArgumentType.integer(1))
						.executes(c -> {
							CONFIG.watchdogTimeoutSeconds = IntegerArgumentType.getInteger(c, "s");
							CONFIG.save();
							c.getSource().sendSuccess(() -> Component.literal("Timeout: " + CONFIG.watchdogTimeoutSeconds + "s"), true);
							return 1;
						})));
		config.then(literal("report")
				.then(argument("s", IntegerArgumentType.integer(1))
						.executes(c -> {
							CONFIG.consoleReportIntervalSeconds = IntegerArgumentType.getInteger(c, "s");
							CONFIG.save();
							c.getSource().sendSuccess(() -> Component.literal("Report interval: " + CONFIG.consoleReportIntervalSeconds + "s"), true);
							return 1;
						})));
		
		config.then(literal("webhook")
				.executes(c -> {
					c.getSource().sendSuccess(() -> Component.literal("Webhook: " + (CONFIG.discordWebhookUrl.isEmpty() ? "Not set" : "Configured")), false);
					return 1;
				})
				.then(literal("clear")
						.executes(c -> {
							CONFIG.discordWebhookUrl = "";
							CONFIG.save();
							c.getSource().sendSuccess(() -> Component.literal("Webhook cleared"), true);
							return 1;
						}))
				.then(argument("u", StringArgumentType.greedyString())
						.executes(c -> {
							CONFIG.discordWebhookUrl = StringArgumentType.getString(c, "u");
							CONFIG.save();
							c.getSource().sendSuccess(() -> Component.literal("Webhook updated"), true);
							return 1;
						})));

		config.then(literal("dimensions")
				.then(argument("l", StringArgumentType.greedyString())
						.executes(c -> {
							CONFIG.dimensions = new ArrayList<>(Arrays.asList(StringArgumentType.getString(c, "l").split(",")));
							CONFIG.dimensions.replaceAll(String::trim);
							CONFIG.save();
							c.getSource().sendSuccess(() -> Component.literal("Dimensions updated"), true);
							return 1;
						})));
		
		var poi = literal("poi");
		poi.then(literal("add")
				.then(argument("c", StringArgumentType.greedyString())
						.executes(c -> {
							CONFIG.pointsOfInterest.add(StringArgumentType.getString(c, "c"));
							CONFIG.save();
							c.getSource().sendSuccess(() -> Component.literal("Added POI"), true);
							return 1;
						})));
		poi.then(literal("clear")
				.executes(c -> {
					CONFIG.pointsOfInterest.clear();
					CONFIG.save();
					c.getSource().sendSuccess(() -> Component.literal("POIs cleared"), true);
					return 1;
				}));
		config.then(poi);

		root.then(config);
		root.then(literal("help")
				.executes(c -> {
					c.getSource().sendSuccess(() -> Component.literal("§6RenderFast:§r /rf start|status|pause|stop|reset|config"), false);
					return 1;
				}));

		dispatcher.register(root);
		dispatcher.register(literal("rf")
				.requires(s -> s.permissions().hasPermission(Permissions.COMMANDS_ADMIN))
				.redirect(root.build()));
	}

	private static void addBool(LiteralArgumentBuilder<CommandSourceStack> node, String name, Supplier<Boolean> getter, Consumer<Boolean> setter) {
		node.then(literal(name)
				.executes(c -> {
					setter.accept(!getter.get());
					CONFIG.save();
					c.getSource().sendSuccess(() -> Component.literal(name + ": " + (getter.get() ? "ON" : "OFF")), true);
					return 1;
				}));
	}

	private static boolean canContinue(ServerLevel level, int cx, int cz, int r) {
		return state != null
				&& state.started
				&& !state.completed
				&& state.centerX == cx
				&& state.centerZ == cz
				&& state.radius == r
				&& state.dimension.equals(level.dimension().identifier().toString());
	}

	private static void stopAllPreloading() {
		state = null;
		nextRequestIndex = -1;
		inFlightIndices.clear();
		inFlightStartTimes.clear();
		completedIndices.clear();
		pendingCompletionQueue.clear();
	}

	private static void ensureState(MinecraftServer server) {
		if (state == null) {
			state = RenderFastState.get(server);
			if (state.started) {
				buildSpiral(state.radius);
				nextRequestIndex = state.doneCount;
			}
		}
	}

	private void onServerTick(MinecraftServer server) {
		ensureState(server);
		if (state == null || !state.started || state.completed) return;

		ServerLevel currentLevel = getLevelForDimension(server, state.dimension);
		if (currentLevel == null) return;

		processCompletions(currentLevel);
		runWatchdog();

		if (CONFIG.enabled && !state.paused) {
			updateCps();
			if (!isInsideWorkHours()) {
				currentPauseReason = "OFF HOURS";
				broadcastProgress(server);
				return;
			}

			long now = System.currentTimeMillis();
			long elapsed = now - currentTickStartTime;
			if (inLagRecovery) {
				double avgMs = server.getAverageTickTimeNanos() / 1_000_000.0;
				if (avgMs < CONFIG.recoveryThresholdMs && elapsed < CONFIG.recoveryThresholdMs) {
					inLagRecovery = false;
				} else {
					currentPauseReason = "LAG RECOVERY";
					broadcastProgress(server);
					return;
				}
			}

			if (CONFIG.playerSafetyRadius > 0) {
				requestSafetyChunks(server);
			}

			boolean empty = server.getPlayerCount() == 0;
			boolean isTurbo = CONFIG.turboMode || (CONFIG.autoTurboWhenEmpty && empty);

			if (isLowMemory()) {
				currentPauseReason = "LOW RAM";
			} else if (CONFIG.onlyPreloadWhenEmpty && !empty) {
				currentPauseReason = "PLAYERS ONLINE";
			} else if (CONFIG.pauseWhenEmpty && empty) {
				currentPauseReason = "OFFLINE";
			} else if ((server.getWorldPath(LevelResource.ROOT).toFile().getFreeSpace() / (1024 * 1024)) < CONFIG.minFreeDiskSpaceMb) {
				currentPauseReason = "LOW DISK";
			} else if (!isTurbo && CONFIG.adaptiveThrottling && server.getAverageTickTimeNanos() > (long) (CONFIG.busyTickThresholdMs * 1_000_000L)) {
				currentPauseReason = "BUSY TICK";
			} else {
				currentPauseReason = "";
				int limit = (CONFIG.voxyIntegration || CONFIG.distantHorizonsIntegration) ? (isTurbo ? 8 : 2) : (isTurbo ? 16 : 4);
				requestMoreChunks(currentLevel, limit);
			}

			if (state.doneCount >= totalChunks && !state.completed) {
				finishDimension(server);
				return;
			}
		} else if (state.paused) {
			currentPauseReason = "MANUAL";
		}

		tickCounter++;
		if (tickCounter >= BROADCAST_INTERVAL_TICKS) {
			tickCounter = 0;
			broadcastProgress(server);
		}

		if (System.currentTimeMillis() - lastConsoleReportTime >= CONFIG.consoleReportIntervalSeconds * 1000L) {
			lastConsoleReportTime = System.currentTimeMillis();
			if (state != null && state.started && !state.completed) {
				LOGGER.info("Progress: {}% ({}/{}) | Speed: {} ch/s",
						(int) (((float) state.doneCount / totalChunks) * 100),
						state.doneCount, totalChunks, String.format("%.1f", currentCps));
			}
		}
	}

	private boolean isInsideWorkHours() {
		try {
			LocalTime now = LocalTime.now();
			LocalTime s = LocalTime.parse(CONFIG.startTime, DateTimeFormatter.ofPattern("HH:mm"));
			LocalTime e = LocalTime.parse(CONFIG.endTime, DateTimeFormatter.ofPattern("HH:mm"));
			return s.isBefore(e)
					? (!now.isBefore(s) && !now.isAfter(e))
					: (!now.isBefore(s) || !now.isAfter(e));
		} catch (Exception ex) {
			return true;
		}
	}

	private void runWatchdog() {
		long now = System.currentTimeMillis();
		inFlightStartTimes.forEach((idx, start) -> {
			if (now - start > CONFIG.watchdogTimeoutSeconds * 1000L) {
				inFlightIndices.remove(idx);
				inFlightStartTimes.remove(idx);
			}
		});
	}

	private static void requestSafetyChunks(MinecraftServer server) {
		for (ServerPlayer p : server.getPlayerList().getPlayers()) {
			ServerLevel level = (ServerLevel) p.level();
			ChunkPos center = p.chunkPosition();
			int r = CONFIG.playerSafetyRadius;
			for (int sx = -r; sx <= r; sx++) {
				for (int sz = -r; sz <= r; sz++) {
					level.getChunkSource().addTicketWithRadius(
							TicketType.FORCED,
							new ChunkPos(center.x() + sx, center.z() + sz),
							0
					);
				}
			}
		}
	}

	private void finishDimension(MinecraftServer server) {
		if (state == null) return;
		long time = (System.currentTimeMillis() - sessionStartTime) / 1000;
		String report = String.format("Pregen complete for %s: %d chunks in %ds", state.dimension, totalChunks, time);
		LOGGER.info(report);
		sendDiscordWebhook(report);

		server.getPlayerList().getPlayers().forEach(p ->
				((ServerLevel) p.level()).playSound(
						null, p.getX(), p.getY(), p.getZ(),
						SoundEvents.UI_TOAST_CHALLENGE_COMPLETE, SoundSource.MASTER,
						1.0f, 1.0f
				)
		);
		
		int idx = CONFIG.dimensions.indexOf(state.dimension);
		if (idx >= 0 && idx < CONFIG.dimensions.size() - 1) {
			ServerLevel next = getLevelForDimension(server, CONFIG.dimensions.get(idx + 1));
			if (next != null) {
				startPreload(next, state.centerX, state.centerZ, state.radius);
				return;
			}
		}
		state.markCompleted();
		if (!CONFIG.onCompleteCommand.isEmpty()) {
			server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), CONFIG.onCompleteCommand);
		}
	}

	private static ServerLevel getLevelForDimension(MinecraftServer s, String d) {
		Identifier id = Identifier.tryParse(d);
		return s.getLevel(ResourceKey.create(
				Registries.DIMENSION,
				id == null ? Identifier.parse("minecraft:overworld") : id
		));
	}

	private static void updateCps() {
		if (state == null) return;
		long now = System.currentTimeMillis();
		timeWindow.addLast(now);
		countWindow.addLast(state.doneCount);
		while (!timeWindow.isEmpty() && now - timeWindow.getFirst() > CONFIG.smoothEtaWindowSeconds * 1000L) {
			timeWindow.removeFirst();
			countWindow.removeFirst();
		}
		if (timeWindow.size() > 1) {
			long elapsed = now - timeWindow.getFirst();
			currentCps = (float) (state.doneCount - countWindow.getFirst()) * 1000f / Math.max(1, elapsed);
		}
	}

	private static String formatEta() {
		if (currentCps <= 0) return "N/A";
		long r = (long) ((totalChunks - state.doneCount) / currentCps);
		return String.format("%dm %ds", r / 60, r % 60);
	}

	private static void startPreload(ServerLevel level, int cx, int cz, int r) {
		if (state == null) return;
		int sr = Math.clamp(r, 1, 2048);
		String dim = level.dimension().identifier().toString();
		state.markStarted(cx, cz, dim, sr);
		buildSpiral(sr);
		sessionStartTime = System.currentTimeMillis();
		nextRequestIndex = 0;
		inFlightIndices.clear();
		inFlightStartTimes.clear();
		completedIndices.clear();
		pendingCompletionQueue.clear();
		cachedTargetStatus = null;
		LOGGER.info("Starting preload: {} chunks in {}", totalChunks, dim);
		sendDiscordWebhook("Started preloading " + totalChunks + " chunks in " + dim);
	}

	private static void sendDiscordWebhook(String m) {
		if (CONFIG.discordWebhookUrl == null || CONFIG.discordWebhookUrl.isEmpty()) return;
		try {
			HttpRequest req = HttpRequest.newBuilder()
					.uri(URI.create(CONFIG.discordWebhookUrl))
					.header("Content-Type", "application/json")
					.POST(HttpRequest.BodyPublishers.ofString("{\"content\":\"" + m + "\"}"))
					.build();
			HTTP_CLIENT.sendAsync(req, HttpResponse.BodyHandlers.ofString());
		} catch (Exception ignored) {
		}
	}

	private static boolean isLowMemory() {
		Runtime rt = Runtime.getRuntime();
		return ((double) (rt.totalMemory() - rt.freeMemory()) / rt.maxMemory()) > CONFIG.memoryUsageThreshold;
	}

	private static void processCompletions(ServerLevel level) {
		if (state == null) return;
		Integer idx;
		boolean ch = false;
		while ((idx = pendingCompletionQueue.poll()) != null) {
			if (idx < 0 || idx >= totalChunks) continue;
			level.getChunkSource().removeTicketWithRadius(
					TicketType.FORCED,
					new ChunkPos(state.centerX + offsetX[idx], state.centerZ + offsetZ[idx]),
					0
			);
			if (inFlightIndices.remove(idx)) {
				inFlightStartTimes.remove(idx);
				completedIndices.add(idx);
				ch = true;
				chunksSinceLastSave++;
			}
			if (CONFIG.saveIntervalChunks > 0 && chunksSinceLastSave >= CONFIG.saveIntervalChunks) {
				level.getServer().saveEverything(true, false, true);
				chunksSinceLastSave = 0;
			}
			if (CONFIG.watchdogBreather && (System.currentTimeMillis() - currentTickStartTime) > CONFIG.breatherThresholdMs) {
				break;
			}
		}
		if (ch) {
			state.doneCount = Math.min(completedIndices.size(), totalChunks);
			state.setDirty();
		}
	}

	private static void requestMoreChunks(ServerLevel level, int limit) {
		if (state == null || isLowMemory()) return;
		if (cachedTargetStatus == null) {
			cachedTargetStatus = BuiltInRegistries.CHUNK_STATUS.get(Identifier.parse(CONFIG.targetStatus))
					.map(Holder.Reference::value)
					.orElse(ChunkStatus.FULL);
		}
		if (!CONFIG.pointsOfInterest.isEmpty() && nextRequestIndex == 0) {
			for (String poi : CONFIG.pointsOfInterest) {
				try {
					String[] p = poi.split(",");
					int x = Integer.parseInt(p[0].trim()) >> 4;
					int z = Integer.parseInt(p[1].trim()) >> 4;
					level.getChunkSource().getChunkFuture(x, z, cachedTargetStatus, true);
				} catch (Exception ignored) {
				}
			}
		}
		
		int req = 0;
		while (inFlightIndices.size() < CONFIG.maxConcurrentAsyncChunks && nextRequestIndex < totalChunks && req < limit) {
			if (CONFIG.watchdogBreather && (System.currentTimeMillis() - currentTickStartTime) > CONFIG.breatherThresholdMs) {
				inLagRecovery = true;
				break;
			}
			int i = nextRequestIndex++;
			if (completedIndices.contains(i)) continue;
			req++;
			ChunkPos cp = new ChunkPos(state.centerX + offsetX[i], state.centerZ + offsetZ[i]);
			inFlightIndices.add(i);
			inFlightStartTimes.put(i, System.currentTimeMillis());
			level.getChunkSource().addTicketWithRadius(TicketType.FORCED, cp, 0);
			level.getChunkSource().getChunkFuture(cp.x(), cp.z(), cachedTargetStatus, true)
					.whenComplete((_, _) -> pendingCompletionQueue.add(i));
		}
	}

	private static void broadcastProgress(MinecraftServer s) {
		if (state == null) return;
		boolean a = CONFIG.enabled && state.started && !state.completed;
		if (state.doneCount == lastBroadcastDone && a == lastBroadcastActive) return;
		lastBroadcastDone = state.doneCount;
		lastBroadcastActive = a;
		RenderFastProgressPayload p = new RenderFastProgressPayload(
				state.doneCount, totalChunks, a, state.paused,
				state.dimension, currentCps, currentPauseReason, getMapData()
		);
		s.getPlayerList().getPlayers().forEach(pl -> {
			if (ServerPlayNetworking.canSend(pl, RenderFastProgressPayload.TYPE)) {
				ServerPlayNetworking.send(pl, p);
			}
		});
	}

	private static byte[] getMapData() {
		byte[] d = new byte[50];
		if (totalChunks <= 0 || state == null) return d;
		int sz = 20;
		for (int i = 0; i < totalChunks; i++) {
			if (completedIndices.contains(i)) {
				int r = state.radius;
				int x = Math.clamp((int) (((offsetX[i] + r) / (double) (2 * r)) * sz), 0, sz - 1);
				int z = Math.clamp((int) (((offsetZ[i] + r) / (double) (2 * r)) * sz), 0, sz - 1);
				int bit = z * sz + x;
				d[bit / 8] |= (byte) (1 << (bit % 8));
			}
		}
		return d;
	}

	private static void sendProgress(ServerPlayer pl) {
		if (state != null) {
			RenderFastProgressPayload p = new RenderFastProgressPayload(
					state.doneCount, totalChunks,
					CONFIG.enabled && state.started && !state.completed,
					state.paused, state.dimension, currentCps, currentPauseReason, getMapData()
			);
			if (ServerPlayNetworking.canSend(pl, RenderFastProgressPayload.TYPE)) {
				ServerPlayNetworking.send(pl, p);
			}
		}
	}

	public static void rebuildSpiral() {
		buildSpiral(state != null && state.started && !state.completed ? state.radius : CONFIG.radius);
	}

	private static void buildSpiral(int r) {
		int side = 2 * r + 1;
		short[] tx = new short[side * side];
		short[] tz = new short[side * side];
		int c = 0;
		long r2 = (long) r * r;
		tx[c] = 0;
		tz[c] = 0;
		c++;
		for (int ir = 1; ir <= r; ir++) {
			for (int dx = -ir; dx <= ir; dx++) {
				if (CONFIG.shape == RenderFastConfig.Shape.SQUARE || ((long) dx * dx + (long) ir * ir <= r2)) {
					tx[c] = (short) dx;
					tz[c] = (short) -ir;
					c++;
					tx[c] = (short) dx;
					tz[c] = (short) ir;
					c++;
				}
			}
			for (int dz = -ir + 1; dz <= ir - 1; dz++) {
				if (CONFIG.shape == RenderFastConfig.Shape.SQUARE || ((long) ir * ir + (long) dz * dz <= r2)) {
					tx[c] = (short) -ir;
					tz[c] = (short) dz;
					c++;
					tx[c] = (short) ir;
					tz[c] = (short) dz;
					c++;
				}
			}
		}
		offsetX = Arrays.copyOf(tx, c);
		offsetZ = Arrays.copyOf(tz, c);
		totalChunks = c;
	}
}
