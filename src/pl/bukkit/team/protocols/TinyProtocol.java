package com.gmail.zahusek.protocol;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;

import net.minecraft.util.com.mojang.authlib.GameProfile;
import net.minecraft.util.io.netty.channel.Channel;
import net.minecraft.util.io.netty.channel.ChannelDuplexHandler;
import net.minecraft.util.io.netty.channel.ChannelFuture;
import net.minecraft.util.io.netty.channel.ChannelHandlerContext;
import net.minecraft.util.io.netty.channel.ChannelInboundHandlerAdapter;
import net.minecraft.util.io.netty.channel.ChannelInitializer;
import net.minecraft.util.io.netty.channel.ChannelPipeline;
import net.minecraft.util.io.netty.channel.ChannelPromise;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerLoginEvent;
import org.bukkit.event.server.PluginDisableEvent;
import org.bukkit.plugin.Plugin;

import com.gmail.zahusek.protocol.Reflection.*;
import com.google.common.collect.Lists;
import com.google.common.collect.MapMaker;

public class TinyProtocol {

	private static final AtomicInteger ID = new AtomicInteger(0);

	private static final MethodInvoker getPlayerHandle = Reflection.getMethod(
			"{obc}.entity.CraftPlayer", "getHandle");
	private static final FieldAccessor<Object> getConnection = Reflection
			.getField("{nms}.EntityPlayer", "playerConnection", Object.class);
	private static final FieldAccessor<Object> getManager = Reflection
			.getField("{nms}.PlayerConnection", "networkManager", Object.class);
	private static final FieldAccessor<Channel> getChannel = Reflection
			.getField("{nms}.NetworkManager", Channel.class, 0);

	private static final Class<Object> minecraftServerClass = Reflection
			.getUntypedClass("{nms}.MinecraftServer");
	private static final Class<Object> serverConnectionClass = Reflection
			.getUntypedClass("{nms}.ServerConnection");
	private static final FieldAccessor<Object> getMinecraftServer = Reflection
			.getField("{obc}.CraftServer", minecraftServerClass, 0);
	private static final FieldAccessor<Object> getServerConnection = Reflection
			.getField(minecraftServerClass, serverConnectionClass, 0);
	private static final MethodInvoker getNetworkMarkers = Reflection
			.getTypedMethod(serverConnectionClass, null, List.class,
					serverConnectionClass);

	private static final Class<?> PACKET_LOGIN_IN_START = Reflection
			.getMinecraftClass("PacketLoginInStart");
	private static final FieldAccessor<GameProfile> getGameProfile = Reflection
			.getField(PACKET_LOGIN_IN_START, GameProfile.class, 0);

	private Map<String, Channel> channelLookup = new MapMaker().weakValues()
			.makeMap();

	private Listener listener;

	private Set<Channel> uninjectedChannels = Collections
			.newSetFromMap(new MapMaker().weakKeys()
					.<Channel, Boolean> makeMap());

	private List<Object> networkManagers;

	private List<Channel> serverChannels = Lists.newArrayList();
	private ChannelInboundHandlerAdapter serverChannelHandler;
	private ChannelInitializer<Channel> beginInitProtocol;
	private ChannelInitializer<Channel> endInitProtocol;

	private String handlerName;

	private List<PacketListener> packetListeners = new ArrayList<PacketListener>();

	protected volatile boolean closed;
	protected Plugin plugin;

	public TinyProtocol(Plugin plugin) {
		this.plugin = plugin;

		this.handlerName = getHandlerName();

		registerBukkitEvents();
		registerChannelHandler();
		registerPlayers(plugin);
	}

	private void createServerChannelHandler() {
		endInitProtocol = new ChannelInitializer<Channel>() {

			@Override
			protected void initChannel(Channel channel) throws Exception {
				try {
					synchronized (networkManagers) {
						if (!closed) {
							injectChannelInternal(channel);
						}
					}
				} catch (Exception e) {
					plugin.getLogger().log(Level.SEVERE,
							"Cannot inject incomming channel " + channel, e);
				}
			}

		};

		beginInitProtocol = new ChannelInitializer<Channel>() {

			@Override
			protected void initChannel(Channel channel) throws Exception {
				channel.pipeline().addLast(endInitProtocol);
			}

		};

		serverChannelHandler = new ChannelInboundHandlerAdapter() {

			@Override
			public void channelRead(ChannelHandlerContext ctx, Object msg)
					throws Exception {
				Channel channel = (Channel) msg;

				channel.pipeline().addFirst(beginInitProtocol);
				ctx.fireChannelRead(msg);
			}

		};
	}

	private void registerBukkitEvents() {
		listener = new Listener() {

			@EventHandler(priority = EventPriority.LOWEST)
			public final void onPlayerLogin(PlayerLoginEvent e) {
				if (closed)
					return;

				Channel channel = getChannel(e.getPlayer());

				if (!uninjectedChannels.contains(channel)) {
					injectPlayer(e.getPlayer());
				}
			}

			@EventHandler
			public final void onPluginDisable(PluginDisableEvent e) {
				if (e.getPlugin().equals(plugin)) {
					close();
				}
			}

		};

		plugin.getServer().getPluginManager().registerEvents(listener, plugin);
	}

	@SuppressWarnings("unchecked")
	private void registerChannelHandler() {
		Object mcServer = getMinecraftServer.get(Bukkit.getServer());
		Object serverConnection = getServerConnection.get(mcServer);
		boolean looking = true;

		networkManagers = (List<Object>) getNetworkMarkers.invoke(null,
				serverConnection);
		createServerChannelHandler();

		for (int i = 0; looking; i++) {
			List<Object> list = Reflection.getField(
					serverConnection.getClass(), List.class, i).get(
					serverConnection);

			for (Object item : list) {
				if (!ChannelFuture.class.isInstance(item))
					break;

				Channel serverChannel = ((ChannelFuture) item).channel();

				serverChannels.add(serverChannel);
				serverChannel.pipeline().addFirst(serverChannelHandler);
				looking = false;
			}
		}
	}

	private void unregisterChannelHandler() {
		if (serverChannelHandler == null)
			return;

		for (Channel serverChannel : serverChannels) {
			final ChannelPipeline pipeline = serverChannel.pipeline();

			// Remove channel handler
			serverChannel.eventLoop().execute(new Runnable() {

				@Override
				public void run() {
					try {
						pipeline.remove(serverChannelHandler);
					} catch (NoSuchElementException e) {
						// That's fine
					}
				}

			});
		}
	}

	private void registerPlayers(Plugin plugin) {
		for (Player player : plugin.getServer().getOnlinePlayers()) {
			injectPlayer(player);
		}
	}

	public void sendPacket(Player player, Object packet) {
		sendPacket(getChannel(player), packet);
	}

	public void sendPacket(Channel channel, Object packet) {
		channel.pipeline().writeAndFlush(packet);
	}

	public void receivePacket(Player player, Object packet) {
		receivePacket(getChannel(player), packet);
	}

	public void receivePacket(Channel channel, Object packet) {
		channel.pipeline().context("encoder").fireChannelRead(packet);
	}

	protected String getHandlerName() {
		return "tiny-" + plugin.getName() + "-" + ID.incrementAndGet();
	}

	public void injectPlayer(Player player) {
		injectChannelInternal(getChannel(player)).player = player;
	}

	public void injectChannel(Channel channel) {
		injectChannelInternal(channel);
	}

	private PacketInterceptor injectChannelInternal(Channel channel) {
		try {
			PacketInterceptor interceptor = (PacketInterceptor) channel
					.pipeline().get(handlerName);

			if (interceptor == null) {
				interceptor = new PacketInterceptor();
				channel.pipeline().addBefore("packet_handler", handlerName,
						interceptor);
				uninjectedChannels.remove(channel);
			}

			return interceptor;
		} catch (IllegalArgumentException e) {
			return (PacketInterceptor) channel.pipeline().get(handlerName);
		}
	}

	public Channel getChannel(Player player) {
		Channel channel = channelLookup.get(player.getName());

		if (channel == null) {
			Object connection = getConnection.get(getPlayerHandle
					.invoke(player));
			Object manager = getManager.get(connection);

			channelLookup.put(player.getName(),
					channel = getChannel.get(manager));
		}

		return channel;
	}

	public void uninjectPlayer(Player player) {
		uninjectChannel(getChannel(player));
	}

	public void uninjectChannel(final Channel channel) {
		if (!closed) {
			uninjectedChannels.add(channel);
		}

		channel.eventLoop().execute(new Runnable() {

			@Override
			public void run() {
				channel.pipeline().remove(handlerName);
			}

		});
	}

	public boolean hasInjected(Player player) {
		return hasInjected(getChannel(player));
	}

	public boolean hasInjected(Channel channel) {
		return channel.pipeline().get(handlerName) != null;
	}

	public final void close() {
		if (!closed) {
			closed = true;

			// Remove our handlers
			for (Player player : plugin.getServer().getOnlinePlayers()) {
				uninjectPlayer(player);
			}

			// Clean up Bukkit
			HandlerList.unregisterAll(listener);
			unregisterChannelHandler();
		}
	}

	private final class PacketInterceptor extends ChannelDuplexHandler {
		public volatile Player player;

		@Override
		public void channelRead(ChannelHandlerContext ctx, Object msg)
				throws Exception {
			final Channel channel = ctx.channel();
			handleLoginStart(channel, msg);

			try {
				for (PacketListener listener : packetListeners) {
					msg = listener.onPacketInAsync(player, channel, msg);
				}
			} catch (Exception e) {
				plugin.getLogger().log(Level.SEVERE,
						"Error in onPacketInAsync().", e);
			}

			if (msg != null) {
				super.channelRead(ctx, msg);
			}
		}

		@Override
		public void write(ChannelHandlerContext ctx, Object msg,
				ChannelPromise promise) throws Exception {
			try {
				for (PacketListener listener : packetListeners) {
					msg = listener.onPacketOutAsync(player, ctx.channel(), msg);
				}
			} catch (Exception e) {
				plugin.getLogger().log(Level.SEVERE,
						"Error in onPacketOutAsync().", e);
			}

			if (msg != null) {
				super.write(ctx, msg, promise);
			}
		}

		private void handleLoginStart(Channel channel, Object packet) {
			if (PACKET_LOGIN_IN_START.isInstance(packet)) {
				GameProfile profile = getGameProfile.get(packet);
				channelLookup.put(profile.getName(), channel);
			}
		}
	}

	public Plugin getPlugin() {
		return this.plugin;
	}

	public void registerListener(PacketListener listener) {
		this.packetListeners.add(listener);
	}

	public void unregisterListener(PacketListener listener) {
		this.packetListeners.remove(listener);
	}

	public interface PacketListener {

		public Object onPacketOutAsync(Player reciever, Channel channel,
				Object packet);

		public Object onPacketInAsync(Player sender, Channel channel,
				Object packet);

	}
}
