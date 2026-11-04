package moe.caa.multilogin.velocity.injector.handler;

import com.google.common.primitives.Longs;
import com.velocitypowered.api.proxy.crypto.IdentifiedKey;
import com.velocitypowered.proxy.VelocityServer;
import com.velocitypowered.proxy.connection.MinecraftConnection;
import com.velocitypowered.proxy.connection.MinecraftSessionHandler;
import com.velocitypowered.proxy.connection.client.AuthSessionHandler;
import com.velocitypowered.proxy.connection.client.InitialLoginSessionHandler;
import com.velocitypowered.proxy.connection.client.LoginInboundConnection;
import com.velocitypowered.proxy.crypto.EncryptionUtils;
import com.velocitypowered.proxy.protocol.StateRegistry;
import com.velocitypowered.proxy.protocol.packet.EncryptionResponsePacket;
import com.velocitypowered.proxy.protocol.packet.ServerLoginPacket;
import lombok.Getter;
import moe.caa.multilogin.api.internal.auth.AuthResult;
import moe.caa.multilogin.api.profile.GameProfile;
import moe.caa.multilogin.api.internal.logger.LoggerProvider;
import moe.caa.multilogin.api.internal.main.MultiCoreAPI;
import moe.caa.multilogin.api.internal.skinrestorer.SkinRestorerResult;
import moe.caa.multilogin.api.internal.util.reflect.Accessor;
import moe.caa.multilogin.api.internal.util.reflect.EnumAccessor;
import moe.caa.multilogin.api.internal.util.reflect.NoSuchEnumException;
import moe.caa.multilogin.api.internal.util.reflect.ReflectUtil;
import moe.caa.multilogin.core.auth.LoginAuthResult;
import net.kyori.adventure.text.Component;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.net.InetSocketAddress;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 接管 InitialLoginSessionHandler 类的其中一个方法
 */
@Getter()
public class MultiInitialLoginSessionHandler {
    private static EnumAccessor loginStatsEnumAccessor;
    private static Accessor initialLoginSessionHandlerAccessor;

    // LoginStateEnum 的枚举
    private static Enum<?> loginStateEnum$LOGIN_PACKET_EXPECTED;
    private static Enum<?> loginStateEnum$LOGIN_PACKET_RECEIVED;
    private static Enum<?> loginStateEnum$ENCRYPTION_REQUEST_SENT;
    private static Enum<?> loginStateEnum$ENCRYPTION_RESPONSE_RECEIVED;

    // 一些函数和字段的引用
    private static MethodHandle assertStateMethod;
    private static MethodHandle setCurrentStateField;
    private static MethodHandle getLoginField;
    private static MethodHandle getVerifyField;
    private static MethodHandle getServerField;
    private static MethodHandle getInboundField;
    private static MethodHandle getMcConnectionField;
    private static MethodHandle getCurrentStateField;
    /**
     * AuthSessionHandler 的构造器。
     * <p>Velocity 3.4.x: {@code (VelocityServer, LoginInboundConnection, GameProfile, boolean)}</p>
     * <p>Velocity 3.5.x / 4.x: {@code (VelocityServer, LoginInboundConnection, GameProfile, boolean, String serverIdHash)}</p>
     * <p>因此这里不再写死参数个数，而是按“前四个参数固定、其余参数按类型兜底”的方式解析。</p>
     */
    private static Constructor<?> authSessionHandlerConstructor;
    // 类体常量
    private final InitialLoginSessionHandler initialLoginSessionHandler;
    private final MultiCoreAPI multiCoreAPI; // 这个不是
    private final VelocityServer server;
    private final MinecraftConnection mcConnection;
    private final LoginInboundConnection inbound;
    // 运行时改动的实例
    private ServerLoginPacket login;
    private byte[] verify;
    // 自己的对象，表示是否通过加密
    private boolean encrypted = false;

    public MultiInitialLoginSessionHandler(InitialLoginSessionHandler initialLoginSessionHandler, MultiCoreAPI multiCoreAPI) {
        this.initialLoginSessionHandler = initialLoginSessionHandler;
        this.multiCoreAPI = multiCoreAPI;
        try {
            this.server = (VelocityServer) getServerField.invoke(initialLoginSessionHandler);
            this.mcConnection = (MinecraftConnection) getMcConnectionField.invoke(initialLoginSessionHandler);
            this.inbound = (LoginInboundConnection) getInboundField.invoke(initialLoginSessionHandler);
        } catch (Throwable e) {
            throw new RuntimeException(e);
        }
    }

    public static void init() throws ClassNotFoundException, NoSuchMethodException, IllegalAccessException, NoSuchFieldException, NoSuchEnumException {
        Class<InitialLoginSessionHandler> initialLoginSessionHandlerClass = InitialLoginSessionHandler.class;
        initialLoginSessionHandlerAccessor = new Accessor(initialLoginSessionHandlerClass);

        Class<?> loginStateEnum = Class.forName("com.velocitypowered.proxy.connection.client.InitialLoginSessionHandler$LoginState");
        loginStatsEnumAccessor = new EnumAccessor(loginStateEnum);

        loginStateEnum$LOGIN_PACKET_EXPECTED = loginStatsEnumAccessor.findByName("LOGIN_PACKET_EXPECTED");
        loginStateEnum$LOGIN_PACKET_RECEIVED = loginStatsEnumAccessor.findByName("LOGIN_PACKET_RECEIVED");
        loginStateEnum$ENCRYPTION_REQUEST_SENT = loginStatsEnumAccessor.findByName("ENCRYPTION_REQUEST_SENT");
        loginStateEnum$ENCRYPTION_RESPONSE_RECEIVED = loginStatsEnumAccessor.findByName("ENCRYPTION_RESPONSE_RECEIVED");

        MethodHandles.Lookup lookup = MethodHandles.lookup();


        assertStateMethod = lookup.unreflect(ReflectUtil.handleAccessible(
                initialLoginSessionHandlerAccessor.findFirstMethodByName(true, "assertState")
        ));

        Field currentState = ReflectUtil.handleAccessible(
                initialLoginSessionHandlerClass.getDeclaredField("currentState")
        );
        getCurrentStateField = lookup.unreflectGetter(currentState);
        setCurrentStateField = lookup.unreflectSetter(currentState);

        getLoginField = lookup.unreflectGetter(ReflectUtil.handleAccessible(
                initialLoginSessionHandlerAccessor.findFirstFieldByType(true, ServerLoginPacket.class)
        ));

        getVerifyField = lookup.unreflectGetter(ReflectUtil.handleAccessible(
                initialLoginSessionHandlerAccessor.findFirstFieldByType(true, byte[].class)
        ));

        getServerField = lookup.unreflectGetter(ReflectUtil.handleAccessible(
                initialLoginSessionHandlerAccessor.findFirstFieldByType(true, VelocityServer.class)
        ));

        getInboundField = lookup.unreflectGetter(ReflectUtil.handleAccessible(
                initialLoginSessionHandlerAccessor.findFirstFieldByType(true, LoginInboundConnection.class)
        ));

        getMcConnectionField = lookup.unreflectGetter(ReflectUtil.handleAccessible(
                initialLoginSessionHandlerAccessor.findFirstFieldByType(true, MinecraftConnection.class)
        ));


        authSessionHandlerConstructor = resolveAuthSessionHandlerConstructor();
    }

    /**
     * 解析 {@code AuthSessionHandler} 的构造器。
     * <p>Velocity 在小版本间会往这个构造器尾部追加参数（例如 3.5.0 追加了 {@code String serverIdHash}，
     * 用于 {@code LoginEvent} 的 server id），旧代码写死四参数签名会直接抛
     * {@code NoSuchMethodException} 并连带关掉整个代理。这里改成模糊匹配:</p>
     * <ol>
     *     <li>前四个参数必须是 (VelocityServer, LoginInboundConnection, GameProfile, boolean)</li>
     *     <li>尾部的额外参数按类型填入默认值（String 填 serverId，boolean 填 true，数值填 0，其余 null）</li>
     * </ol>
     */
    private static Constructor<?> resolveAuthSessionHandlerConstructor() throws NoSuchMethodException {
        List<Constructor<?>> matched = new ArrayList<>();
        StringBuilder dump = new StringBuilder();
        for (Constructor<?> constructor : AuthSessionHandler.class.getDeclaredConstructors()) {
            Class<?>[] types = constructor.getParameterTypes();
            dump.append("\n  - AuthSessionHandler(");
            for (int i = 0; i < types.length; i++) {
                if (i > 0) dump.append(", ");
                dump.append(types[i].getSimpleName());
            }
            dump.append(')');

            if (types.length < 4) continue;
            if (!types[0].isAssignableFrom(VelocityServer.class)) continue;
            if (!types[1].isAssignableFrom(LoginInboundConnection.class)) continue;
            if (!types[2].isAssignableFrom(com.velocitypowered.api.util.GameProfile.class)) continue;
            if (types[3] != boolean.class && types[3] != Boolean.class) continue;
            matched.add(constructor);
        }

        if (matched.isEmpty()) {
            throw new NoSuchMethodException(
                    "No compatible AuthSessionHandler constructor was found on this Velocity version."
                            + " Expected (VelocityServer, LoginInboundConnection, GameProfile, boolean, ...)"
                            + " but the class only declares:" + dump
            );
        }

        // 参数少的优先（更贴近旧 API）；同数量时取声明顺序靠前的那个
        matched.sort(Comparator.comparingInt(Constructor::getParameterCount));
        Constructor<?> chosen = ReflectUtil.handleAccessible(matched.get(0));
        LoggerProvider.getLogger().debug(String.format(
                "Resolved AuthSessionHandler constructor: AuthSessionHandler(%s)",
                Arrays.stream(chosen.getParameterTypes()).map(Class::getSimpleName).collect(Collectors.joining(", "))
        ));
        return chosen;
    }

    /**
     * 构造一个 {@code AuthSessionHandler} 实例，兼容不同 Velocity 版本的构造器参数个数。
     */
    private static Object newAuthSessionHandler(VelocityServer server, LoginInboundConnection inbound,
                                                com.velocitypowered.api.util.GameProfile profile,
                                                boolean onlineMode, String serverIdHash) throws ReflectiveOperationException {
        Class<?>[] types = authSessionHandlerConstructor.getParameterTypes();
        Object[] args = new Object[types.length];
        args[0] = server;
        args[1] = inbound;
        args[2] = profile;
        args[3] = onlineMode;
        for (int i = 4; i < types.length; i++) {
            Class<?> type = types[i];
            if (type == String.class || type == CharSequence.class) {
                // Velocity 3.5+: serverIdHash，会原样传给 LoginEvent
                args[i] = serverIdHash;
            } else if (type == boolean.class || type == Boolean.class) {
                args[i] = Boolean.TRUE;
            } else if (type == int.class || type == Integer.class) {
                args[i] = 0;
            } else if (type == long.class || type == Long.class) {
                args[i] = 0L;
            } else {
                args[i] = null;
            }
        }
        return authSessionHandlerConstructor.newInstance(args);
    }

    private void initValues() throws Throwable {
        this.login = (ServerLoginPacket) getLoginField.invoke(initialLoginSessionHandler);
        this.verify = (byte[]) getVerifyField.invoke(initialLoginSessionHandler);
    }

    public void handle(EncryptionResponsePacket packet) throws Throwable {
        initValues();

        // 模拟常规流程
        assertStateMethod.invoke(initialLoginSessionHandler, loginStateEnum$ENCRYPTION_REQUEST_SENT);
        setCurrentStateField.invoke(initialLoginSessionHandler, loginStateEnum$ENCRYPTION_RESPONSE_RECEIVED);

        ServerLoginPacket login = this.login;
        if (login == null) {
            throw new IllegalStateException("No ServerLogin packet received yet.");
        }
        if (this.verify.length == 0) {
            throw new IllegalStateException("No EncryptionRequest packet sent yet.");
        }

        try {

            // 加密部分
            KeyPair serverKeyPair = this.server.getServerKeyPair();
            if (this.inbound.getIdentifiedKey() != null) {
                IdentifiedKey playerKey = this.inbound.getIdentifiedKey();
                if (!playerKey.verifyDataSignature(packet.getVerifyToken(), this.verify, Longs.toByteArray(packet.getSalt()))) {
                    throw new IllegalStateException("Invalid client public signature.");
                }
            } else {
                byte[] decryptedSharedSecret = EncryptionUtils.decryptRsa(serverKeyPair, packet.getVerifyToken());
                if (!MessageDigest.isEqual(this.verify, decryptedSharedSecret)) {
                    throw new IllegalStateException("Unable to successfully decrypt the verification token.");
                }
            }

            byte[] decryptedSharedSecret = EncryptionUtils.decryptRsa(serverKeyPair, packet.getSharedSecret());

            encrypted = true;
            // 验证
            String username = login.getUsername();
            String serverId = EncryptionUtils.generateServerId(decryptedSharedSecret, serverKeyPair.getPublic());
            String playerIp = ((InetSocketAddress) this.mcConnection.getRemoteAddress()).getHostString();

            multiCoreAPI.getPlugin().getRunServer().getScheduler().runTaskAsync(() -> {
                LoginAuthResult result = (LoginAuthResult) multiCoreAPI.getAuthHandler().auth(username, serverId, playerIp);
                try {
                    if (mcConnection.getChannel().eventLoop().submit(() -> {
                        if (this.mcConnection.isClosed()) return false;
                        try {
                            this.mcConnection.enableEncryption(decryptedSharedSecret);
                            return true;
                        } catch (GeneralSecurityException var8) {
                            LoggerProvider.getLogger().error("Unable to enable encryption for connection", var8);
                            this.mcConnection.close(true);
                            return false;
                        }
                    }).get()) {
                        if (result.getResult() == AuthResult.Result.ALLOW) {
                            GameProfile gameProfile = result.getResponse();

                            try {
                                SkinRestorerResult restorerResult = multiCoreAPI.getSkinRestorerHandler().doRestorer(result);
                                if (restorerResult.getThrowable() != null) {
                                    LoggerProvider.getLogger().error("An exception occurred while processing the skin repair.", restorerResult.getThrowable());
                                }
                                LoggerProvider.getLogger().debug(String.format("Skin restore result of %s is %s.", result.getBaseServiceAuthenticationResult().getResponse().getName(), restorerResult.getReason()));

                                if (restorerResult.getResponse() != null) {
                                    gameProfile = restorerResult.getResponse();
                                }
                            } catch (Exception e) {
                                LoggerProvider.getLogger().debug(String.format("Skin restore result of %s is %s.", result.getBaseServiceAuthenticationResult().getResponse().getName(), "error"));
                                LoggerProvider.getLogger().debug("An exception occurred while processing the skin repair.", e);
                            }

                            GameProfile finalGameProfile = gameProfile;
                            mcConnection.getChannel().eventLoop().submit(() -> {
                                try {
                                    this.mcConnection.setActiveSessionHandler(StateRegistry.LOGIN,
                                            (MinecraftSessionHandler) newAuthSessionHandler(
                                                    this.server, inbound, generateGameProfile(finalGameProfile), true, serverId
                                            ));
                                } catch (Throwable e) {
                                    throw new RuntimeException(e);
                                }
                            }).get();

                        } else {
                            this.inbound.disconnect(Component.text(result.getKickMessage()));
                        }
                    }
                } catch (Throwable e){
                    LoggerProvider.getLogger().error("An exception occurred while processing validation results.", e);
                    if (isEncrypted()) {
                        getInbound().disconnect(Component.text(multiCoreAPI.getLanguageHandler().getMessage("auth_error")));
                    }
                    mcConnection.close(true);
                }
            });
        } catch (GeneralSecurityException var9) {
            LoggerProvider.getLogger().error("Unable to enable encryption.", var9);
            this.mcConnection.close(true);
        }
    }

    private com.velocitypowered.api.util.GameProfile generateGameProfile(GameProfile response) {
        return new com.velocitypowered.api.util.GameProfile(
                response.getId(),
                response.getName(),
                response.getPropertyMap().values().stream().map(s ->
                        new com.velocitypowered.api.util.GameProfile.Property(s.getName(), s.getValue(), s.getSignature())
                ).collect(Collectors.toList())
        );
    }
}
