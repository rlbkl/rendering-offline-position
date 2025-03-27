package com.rendering_offline_position;

import com.mojang.blaze3d.systems.RenderSystem;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.render.*;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Util;
import net.minecraft.util.math.Vec3d;
import net.minecraft.registry.RegistryKey;
import net.minecraft.world.World;
import org.joml.Matrix4f;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class Rendering_offline_position implements ModInitializer {
	// 配置参数
	private static final int CHECK_RADIUS = 128;
	private static final long COOLDOWN = 5000;
	private static final float[] BEAM_COLOR = {1.0f, 0.2f, 0.2f};
	private static final float BEAM_ALPHA = 0.5f;
	private static final float BEAM_HEIGHT = 2.0f;
	private static final float BEAM_WIDTH = 0.5f;

	private static final Map<UUID, PlayerRecord> onlinePlayers = new ConcurrentHashMap<>();
	private static final Map<UUID, PlayerRecord> offlinePlayers = new ConcurrentHashMap<>();
	private static long lastCheckTime = 0;

	private static class PlayerRecord {
		final String name;
		final Vec3d position;
		final RegistryKey<World> dimension;
		final long timestamp;

		PlayerRecord(String name, Vec3d pos, World world) {
			this.name = name;
			this.position = pos;
			this.dimension = world.getRegistryKey();
			this.timestamp = Util.getMeasuringTimeMs();
		}

		public String getOfflineDuration() {
			long duration = Util.getMeasuringTimeMs() - timestamp;
			long minutes = duration / 60000;
			if (minutes < 1) {
				return "刚刚";
			} else if (minutes < 60) {
				return minutes + "分钟前";
			} else {
				return (minutes / 60) + "小时前";
			}
		}
	}

	@Override
	public void onInitialize() {
		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			if (client.world == null || client.player == null) return;

			long currentTime = Util.getMeasuringTimeMs();
			if (currentTime - lastCheckTime > COOLDOWN) {
				updatePlayerRecords(client);
				lastCheckTime = currentTime;
			}
		});

		WorldRenderEvents.AFTER_TRANSLUCENT.register(this::renderCustomBeams);
	}

	private void updatePlayerRecords(MinecraftClient client) {
		World world = client.world;
		PlayerEntity localPlayer = client.player;
		if (world == null || localPlayer == null) return;

		Set<UUID> currentOnline = new HashSet<>();
		List<? extends PlayerEntity> players = world.getPlayers();

		// 更新在线玩家位置
		for (PlayerEntity player : players) {
			if (isLocalPlayer(player)) continue;
			UUID uuid = player.getUuid();
			currentOnline.add(uuid);
			onlinePlayers.put(uuid, new PlayerRecord(
					player.getName().getString(),
					player.getPos(),
					world
			));
		}

		// 检测离线玩家
		Iterator<UUID> iterator = onlinePlayers.keySet().iterator();
		while (iterator.hasNext()) {
			UUID uuid = iterator.next();
			if (!currentOnline.contains(uuid)) {
				PlayerRecord record = onlinePlayers.get(uuid);
				if (record != null) {
					offlinePlayers.put(uuid, record);
					sendOfflineAlert(client, record);
				}
				iterator.remove();
			}
		}

		// 清理过期数据（11分钟）
		long expireTime = Util.getMeasuringTimeMs() - 660_000;
		offlinePlayers.values().removeIf(record -> record.timestamp < expireTime);
	}

	private void renderCustomBeams(WorldRenderContext context) {
		MinecraftClient client = MinecraftClient.getInstance();
		World world = client.world;
		PlayerEntity player = client.player;
		if (world == null || player == null) return;

		MatrixStack matrices = context.matrixStack();
		Camera camera = context.camera();
		Vec3d cameraPos = camera.getPos();
		TextRenderer textRenderer = client.textRenderer;

		RenderSystem.enableBlend();
		RenderSystem.defaultBlendFunc();
		RenderSystem.setShader(GameRenderer::getPositionColorProgram);
		RenderSystem.depthMask(false);

		for (PlayerRecord record : offlinePlayers.values()) {
			if (!record.dimension.equals(world.getRegistryKey())) continue;
			if (player.squaredDistanceTo(record.position) > CHECK_RADIUS * CHECK_RADIUS) continue;

			// 渲染光柱
			Objects.requireNonNull(matrices).push();
			matrices.translate(
					record.position.x - cameraPos.x,
					record.position.y - cameraPos.y,
					record.position.z - cameraPos.z
			);

			Matrix4f matrix = matrices.peek().getPositionMatrix();
			Tessellator tessellator = Tessellator.getInstance();
			BufferBuilder buffer = tessellator.getBuffer();

			buffer.begin(VertexFormat.DrawMode.QUADS, VertexFormats.POSITION_COLOR);
			buildBeam(buffer, matrix, BEAM_WIDTH, BEAM_HEIGHT, BEAM_COLOR, BEAM_ALPHA);
			tessellator.draw();

			matrices.pop();

			// 优化后的名称标签渲染
			Vec3d textPos = record.position.add(0, BEAM_HEIGHT + 0.5, 0);
			String info = String.format("§5%s §7%s §b%.1f, %.1f, %.1f",
					record.name,
					record.getOfflineDuration(),
					record.position.x,
					record.position.y,
					record.position.z);

			matrices.push();
			matrices.translate(textPos.x - cameraPos.x, textPos.y - cameraPos.y, textPos.z - cameraPos.z);
			matrices.multiply(camera.getRotation());
			matrices.scale(-0.02f, -0.02f, 0.02f); // 调整缩放比例

			// 优化文本渲染参数
			Matrix4f positionMatrix = matrices.peek().getPositionMatrix();
			int packedLight = LightmapTextureManager.pack(15, 15);

			textRenderer.draw(
					Text.literal(info), // 使用Text对象避免格式化错误
					(float) -textRenderer.getWidth(info) / 2,
					0,
					0xFFFFFFFF, // 白色+全透明度
					false,
					positionMatrix,
					context.consumers(),
					TextRenderer.TextLayerType.NORMAL,
					0, // 背景透明
					packedLight
			);
			matrices.pop();
		}

		RenderSystem.depthMask(true);
		RenderSystem.disableBlend();
	}

	private void buildBeam(BufferBuilder buffer, Matrix4f matrix, float width, float height, float[] color, float alpha) {
		final float halfWidth = width / 2;

		// 底面到顶面的四个侧面
		addQuad(buffer, matrix,
				-halfWidth, 0, -halfWidth,
				halfWidth, 0, -halfWidth,
				halfWidth, height, -halfWidth,
				-halfWidth, height, -halfWidth,
				color, alpha); // 北面

		addQuad(buffer, matrix,
				-halfWidth, 0, halfWidth,
				-halfWidth, height, halfWidth,
				halfWidth, height, halfWidth,
				halfWidth, 0, halfWidth,
				color, alpha); // 南面

		addQuad(buffer, matrix,
				-halfWidth, 0, -halfWidth,
				-halfWidth, height, -halfWidth,
				-halfWidth, height, halfWidth,
				-halfWidth, 0, halfWidth,
				color, alpha); // 西面

		addQuad(buffer, matrix,
				halfWidth, 0, -halfWidth,
				halfWidth, 0, halfWidth,
				halfWidth, height, halfWidth,
				halfWidth, height, -halfWidth,
				color, alpha); // 东面

		// 顶面
		addQuad(buffer, matrix,
				-halfWidth, height, -halfWidth,
				halfWidth, height, -halfWidth,
				halfWidth, height, halfWidth,
				-halfWidth, height, halfWidth,
				color, alpha);
	}

	private void addQuad(BufferBuilder buffer, Matrix4f matrix,
						 float x1, float y1, float z1,
						 float x2, float y2, float z2,
						 float x3, float y3, float z3,
						 float x4, float y4, float z4,
						 float[] color, float alpha) {
		buffer.vertex(matrix, x1, y1, z1).color(color[0], color[1], color[2], alpha).next();
		buffer.vertex(matrix, x2, y2, z2).color(color[0], color[1], color[2], alpha).next();
		buffer.vertex(matrix, x3, y3, z3).color(color[0], color[1], color[2], alpha).next();
		buffer.vertex(matrix, x4, y4, z4).color(color[0], color[1], color[2], alpha).next();
	}

	private void sendOfflineAlert(MinecraftClient client, PlayerRecord record) {
		PlayerEntity player = client.player;
		if (player == null) return;

		double distance = player.getPos().distanceTo(record.position);
		if (distance > CHECK_RADIUS) return;

		String message = String.format("§c[离线提示] §f%s 在 §b%.1f, %.1f, %.1f 离线§7(距离%.1f格)",
				record.name,
				record.position.x,
				record.position.y,
				record.position.z,
				distance);

		player.sendMessage(Text.literal(message), false);
	}

	private boolean isLocalPlayer(PlayerEntity player) {
		return MinecraftClient.getInstance().player != null &&
				MinecraftClient.getInstance().player.getUuid().equals(player.getUuid());
	}
}