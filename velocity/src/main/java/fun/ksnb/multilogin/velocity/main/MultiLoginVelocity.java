package fun.ksnb.multilogin.velocity.main;

import com.google.inject.Inject;
import com.velocitypowered.api.event.AwaitingEventExecutor;
import com.velocitypowered.api.event.EventTask;
import com.velocitypowered.api.event.PostOrder;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.connection.PostLoginEvent;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.proxy.connection.client.ConnectedPlayer;
import com.velocitypowered.proxy.network.Connections;
import fun.ksnb.multilogin.velocity.impl.ChatSessionHandler;
import fun.ksnb.multilogin.velocity.impl.NewChatSessionPacketIDEvent;
import fun.ksnb.multilogin.velocity.impl.VelocityServer;
import fun.ksnb.multilogin.velocity.logger.Slf4jLoggerBridge;
import io.netty.channel.Channel;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import lombok.Getter;
import moe.caa.multilogin.api.internal.injector.Injector;
import moe.caa.multilogin.api.internal.logger.LoggerProvider;
import moe.caa.multilogin.api.internal.main.MultiCoreAPI;
import moe.caa.multilogin.api.internal.plugin.IPlugin;
import moe.caa.multilogin.loader.main.PluginLoader;
import org.slf4j.Logger;

import java.io.File;
import java.nio.file.Path;

/**
 * Velocity Main
 */
public class MultiLoginVelocity implements IPlugin {
    @Getter
    private static MultiLoginVelocity instance;
    private final Path dataDirectory;
    @Getter
    private final com.velocitypowered.proxy.VelocityServer server;
    @Getter
    private final VelocityServer runServer;
    private final PluginLoader pluginLoader;
    @Getter
    private MultiCoreAPI multiCoreAPI;
    private static final String KEY = "MultiLoginChatSession";
    private Injector injector;
    @Inject
    public MultiLoginVelocity(ProxyServer server, Logger logger, @DataDirectory Path dataDirectory) {
        instance = this;
        this.server = (com.velocitypowered.proxy.VelocityServer) server;
        this.runServer = new VelocityServer(this.server);
        this.dataDirectory = dataDirectory;
        LoggerProvider.setLogger(new Slf4jLoggerBridge(logger));
        this.pluginLoader = new PluginLoader(this);
        try {
            pluginLoader.load("MultiLogin-Velocity-Injector.JarFile");
        } catch (Exception e) {
            // 注意: 这里绝对不要调用 server.shutdown()。
            // 插件初始化失败不应该把整个代理带走——代理保持存活，至少正版直连/其它插件还能工作，
            // 管理员也有机会看到日志去修配置，而不是整个群组瞬间掉线。
            failFast("An exception was encountered while initializing the plugin.", e);
        }
    }

    /**
     * 插件进入"降级模式": 只打印醒目错误并停止接管登录，但保持代理继续运行。
     */
    private void failFast(String message, Throwable throwable) {
        LoggerProvider.getLogger().error("======================================================");
        LoggerProvider.getLogger().error("MultiLogin failed to initialize and is now DISABLED.");
        LoggerProvider.getLogger().error("The proxy keeps running, but external/Yggdrasil logins will NOT work.");
        LoggerProvider.getLogger().error("This usually means MultiLogin does not support this Velocity build yet.");
        LoggerProvider.getLogger().error("Velocity: " + runServer.getVersion());
        LoggerProvider.getLogger().error("======================================================");
        LoggerProvider.getLogger().error(message, throwable);
        multiCoreAPI = null;
    }

    @Subscribe
    public void onInitialize(ProxyInitializeEvent event) {
        try {
            multiCoreAPI = pluginLoader.getCoreObject();
            multiCoreAPI.load();
            injector = (Injector) pluginLoader.findClass("moe.caa.multilogin.velocity.injector.VelocityInjector").getConstructor().newInstance();
            injector.inject(multiCoreAPI);
            injector.registerChatSession(multiCoreAPI.getMapperConfig().getPacketMapping());
        } catch (Throwable e) {
            failFast("An exception was encountered while loading the plugin.", e);
            return;
        }
        new GlobalListener(this).register();
        new CommandHandler(this).register("multilogin");
        //自动检测未映射的ChatSession Packet, 可能对性能有影响?
        {
            server.getEventManager().register(this, PostLoginEvent.class,
                    (AwaitingEventExecutor<PostLoginEvent>) postLoginEvent -> EventTask.withContinuation(continuation -> {
                        try {
                            if(postLoginEvent.getPlayer().getProtocolVersion().getProtocol() < 761) return;
                            injectPlayer(postLoginEvent.getPlayer());
                        } finally {
                            continuation.resume();
                        }
                    })
            );
            server.getEventManager().register(this, DisconnectEvent.class, PostOrder.LAST,
                    (AwaitingEventExecutor<DisconnectEvent>) disconnectEvent ->
                            disconnectEvent.getLoginStatus() == DisconnectEvent.LoginStatus.CONFLICTING_LOGIN
                                    ? null
                                    : EventTask.async(() -> removePlayer(disconnectEvent.getPlayer()))
            );
            server.getEventManager().register(this, NewChatSessionPacketIDEvent.class,
                    (AwaitingEventExecutor<NewChatSessionPacketIDEvent>) packetEvent -> EventTask.withContinuation(continuation -> {
                        runServer.getPlayerManager().kickPlayerIfOnline(packetEvent.getPlayer().getUniqueId(), multiCoreAPI.getLanguageHandler().getMessage("reconnect_msg"));
                        multiCoreAPI.getMapperConfig().getPacketMapping().put(packetEvent.getVersion().getProtocol(),packetEvent.getPacketID());
                        multiCoreAPI.getMapperConfig().save();
                        injector.registerChatSession(multiCoreAPI.getMapperConfig().getPacketMapping());
                    })
            );
        }

    }

    @Subscribe
    public void onDisable(ProxyShutdownEvent event) {
        try {
            if (multiCoreAPI != null) {
                multiCoreAPI.close();
            }
            pluginLoader.close();
        } catch (Exception e) {
            LoggerProvider.getLogger().error("An exception was encountered while close the plugin", e);
        } finally {
            multiCoreAPI = null;
        }
    }


    @Override
    public File getDataFolder() {
        return dataDirectory.toFile();
    }

    @Override
    public File getTempFolder() {
        return new File(getDataFolder(), "tmp");
    }

    private void injectPlayer(final Player player) {
        final ConnectedPlayer connectedPlayer = (ConnectedPlayer) player;
        connectedPlayer.getConnection()
                .getChannel()
                .pipeline()
                .addBefore(Connections.HANDLER, KEY, new ChatSessionHandler(player,server.getEventManager()));
    }

    private void removePlayer(final Player player) {
        final ConnectedPlayer connectedPlayer = (ConnectedPlayer) player;
        final Channel channel = connectedPlayer.getConnection().getChannel();
        channel.eventLoop().submit(() -> {
            channel.pipeline().remove(KEY);
        });
    }
}
