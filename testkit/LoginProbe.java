import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.spec.X509EncodedKeySpec;
import java.util.UUID;

/**
 * 一个"够用就好"的 Minecraft 客户端登录探测器。
 * <p>它只做登录阶段: Handshake -> LoginStart -> EncryptionRequest/Response ->
 * SetCompression -> LoginSuccess，用于端到端验证代理端插件是否正确接管了登录。</p>
 * <p>用法: java LoginProbe <port> [protocol] [username] [expectedUuid]</p>
 * <p>退出码: 0=收到 LoginSuccess 且 UUID/名字匹配; 1=被踢/协议错误; 2=参数或网络问题</p>
 */
public final class LoginProbe {

    private static final int PACKET_DISCONNECT = 0x00;
    private static final int PACKET_ENCRYPTION_REQUEST = 0x01;
    private static final int PACKET_LOGIN_SUCCESS = 0x02;
    private static final int PACKET_SET_COMPRESSION = 0x03;

    private static final SecureRandom RANDOM = new SecureRandom();

    private Socket socket;
    private InputStream in;
    private OutputStream out;
    private int compressionThreshold = -1;
    private Cipher decryptCipher;
    private Cipher encryptCipher;

    public static void main(String[] args) {
        int port = Integer.parseInt(args[0]);
        int protocol = args.length > 1 ? Integer.parseInt(args[1]) : 767;
        String username = args.length > 2 ? args[2] : "TestPlayer";
        String expectedUuid = args.length > 3 ? args[3] : null;
        LoginProbe probe = new LoginProbe();
        try {
            int code = probe.run(port, protocol, username, expectedUuid);
            System.out.println("[LoginProbe] RESULT=" + (code == 0 ? "OK" : "FAILED") + " exit=" + code);
            System.out.flush();
            System.exit(code);
        } catch (Throwable t) {
            System.out.println("[LoginProbe] RESULT=ERROR " + t);
            t.printStackTrace(System.out);
            System.out.flush();
            System.exit(2);
        } finally {
            probe.close();
        }
    }

    private int run(int port, int protocol, String username, String expectedUuid) throws Exception {
        socket = new Socket();
        socket.connect(new InetSocketAddress("127.0.0.1", port), 10_000);
        socket.setSoTimeout(30_000);
        in = socket.getInputStream();
        out = socket.getOutputStream();

        // ---- Handshake(0x00) + LoginStart(0x00) ----
        ByteArrayOutputStream handshake = new ByteArrayOutputStream();
        writeVarInt(handshake, protocol);
        writeString(handshake, "127.0.0.1");
        handshake.write((port >> 8) & 0xFF);
        handshake.write(port & 0xFF);
        writeVarInt(handshake, 2); // next state = login
        writePacket(0x00, handshake.toByteArray());

        ByteArrayOutputStream loginStart = new ByteArrayOutputStream();
        writeString(loginStart, username);
        UUID clientUuid = UUID.nameUUIDFromBytes(("OfflinePlayer:" + username).getBytes(StandardCharsets.UTF_8));
        writeUuid(loginStart, clientUuid);
        writePacket(0x00, loginStart.toByteArray());
        System.out.println("[LoginProbe] sent handshake+loginStart protocol=" + protocol + " user=" + username);

        while (true) {
            byte[] payload = readPacketPayload();
            int packetId = readVarIntFrom(payload, new int[]{0});
            int[] offset = new int[]{varIntSize(packetId)};
            switch (packetId) {
                case PACKET_DISCONNECT: {
                    String json = readString(payload, offset);
                    System.out.println("[LoginProbe] DISCONNECTED by proxy: " + json);
                    return 1;
                }
                case PACKET_ENCRYPTION_REQUEST: {
                    String serverId = readString(payload, offset);
                    byte[] publicKey = readByteArray(payload, offset);
                    byte[] verifyToken = readByteArray(payload, offset);
                    boolean shouldAuthenticate = true;
                    if (protocol >= 766) {
                        shouldAuthenticate = payload[offset[0]] != 0;
                    }
                    System.out.println("[LoginProbe] encryptionRequest serverId='" + serverId
                            + "' pubKey=" + publicKey.length + "B verifyToken=" + verifyToken.length
                            + "B shouldAuthenticate=" + shouldAuthenticate);
                    handleEncryption(publicKey, verifyToken);
                    continue;
                }
                case PACKET_SET_COMPRESSION: {
                    compressionThreshold = readVarIntFrom(payload, offset);
                    System.out.println("[LoginProbe] setCompression threshold=" + compressionThreshold);
                    continue;
                }
                case PACKET_LOGIN_SUCCESS: {
                    String undashed = readUuidString(payload, offset);
                    String name = readString(payload, offset);
                    int propertyCount = readVarIntFrom(payload, offset);
                    for (int i = 0; i < propertyCount; i++) {
                        String pn = readString(payload, offset);
                        String pv = readString(payload, offset);
                        boolean signed = payload[offset[0]++] != 0;
                        if (signed) readString(payload, offset);
                        System.out.println("[LoginProbe]   property " + pn + " (" + pv.length() + " chars) signed=" + signed);
                    }
                    if (protocol == 766 || protocol == 767) {
                        if (offset[0] < payload.length) {
                            boolean strict = payload[offset[0]] != 0;
                            System.out.println("[LoginProbe]   strictErrorHandling=" + strict);
                        }
                    }
                    System.out.println("[LoginProbe] LOGIN SUCCESS uuid=" + undashed + " name=" + name);
                    if (expectedUuid != null && !expectedUuid.replace("-", "").equalsIgnoreCase(undashed.replace("-", ""))) {
                        System.out.println("[LoginProbe] UUID MISMATCH: expected " + expectedUuid);
                        return 1;
                    }
                    return 0;
                }
                default: {
                    System.out.println("[LoginProbe] unknown packet id=0x" + Integer.toHexString(packetId)
                            + " len=" + payload.length);
                }
            }
        }
    }

    private void handleEncryption(byte[] derPublicKey, byte[] verifyToken) throws Exception {
        PublicKey publicKey = KeyFactory.getInstance("RSA")
                .generatePublic(new X509EncodedKeySpec(derPublicKey));
        byte[] sharedSecret = new byte[16];
        RANDOM.nextBytes(sharedSecret);

        Cipher rsa = Cipher.getInstance("RSA/ECB/PKCS1Padding");
        rsa.init(Cipher.ENCRYPT_MODE, publicKey);
        byte[] encryptedSecret = rsa.doFinal(sharedSecret);
        rsa.init(Cipher.ENCRYPT_MODE, publicKey);
        byte[] encryptedVerifyToken = rsa.doFinal(verifyToken);

        ByteArrayOutputStream response = new ByteArrayOutputStream();
        writeByteArray(response, encryptedSecret);
        writeByteArray(response, encryptedVerifyToken);
        writePacket(0x01, response.toByteArray());

        // 发送完 EncryptionResponse 之后流量立即进入加密态
        SecretKeySpec keySpec = new SecretKeySpec(sharedSecret, "AES");
        IvParameterSpec ivSpec = new IvParameterSpec(sharedSecret);
        decryptCipher = Cipher.getInstance("AES/CFB8/NoPadding");
        decryptCipher.init(Cipher.DECRYPT_MODE, keySpec, ivSpec);
        encryptCipher = Cipher.getInstance("AES/CFB8/NoPadding");
        encryptCipher.init(Cipher.ENCRYPT_MODE, keySpec, ivSpec);
        System.out.println("[LoginProbe] encryption enabled (AES/CFB8)");
    }

    // ------------------------------------------------------------------
    // 协议读写
    // ------------------------------------------------------------------

    private void writePacket(int packetId, byte[] data) throws Exception {
        ByteArrayOutputStream inner = new ByteArrayOutputStream();
        writeVarInt(inner, packetId);
        inner.write(data);
        byte[] body = inner.toByteArray();

        ByteArrayOutputStream frame = new ByteArrayOutputStream();
        if (compressionThreshold >= 0) {
            ByteArrayOutputStream compressedBody = new ByteArrayOutputStream();
            writeVarInt(compressedBody, body.length);
            compressedBody.write(body);
            writeVarInt(frame, compressedBody.size());
            frame.write(compressedBody.toByteArray());
        } else {
            writeVarInt(frame, body.length);
            frame.write(body);
        }

        byte[] raw = frame.toByteArray();
        if (encryptCipher != null) {
            raw = encryptCipher.update(raw);
        }
        out.write(raw);
        out.flush();
    }

    private byte[] readPacketPayload() throws IOException {
        int length = readVarIntFromStream();
        if (length < 0 || length > 8 * 1024 * 1024) {
            throw new IOException("Illegal packet length " + length);
        }
        byte[] frame = readFully(length);
        if (compressionThreshold >= 0) {
            int[] offset = new int[]{0};
            int uncompressed = readVarIntFrom(frame, offset);
            int payloadLength = frame.length - offset[0];
            if (uncompressed == 0) {
                byte[] out = new byte[payloadLength];
                System.arraycopy(frame, offset[0], out, 0, payloadLength);
                return out;
            }
            byte[] compressed = new byte[payloadLength];
            System.arraycopy(frame, offset[0], compressed, 0, payloadLength);
            byte[] restored = inflate(compressed, uncompressed);
            return restored;
        }
        return frame;
    }

    private byte[] inflate(byte[] compressed, int expectedLength) throws IOException {
        java.util.zip.Inflater inflater = new java.util.zip.Inflater();
        try {
            inflater.setInput(compressed);
            byte[] out = new byte[expectedLength];
            int read = 0;
            while (read < expectedLength && !inflater.finished()) {
                int n = inflater.inflate(out, read, expectedLength - read);
                if (n == 0) {
                    if (inflater.needsInput() || inflater.needsDictionary()) break;
                }
                read += n;
            }
            if (read != expectedLength) {
                throw new IOException("Inflate mismatch, expected " + expectedLength + " got " + read);
            }
            return out;
        } catch (java.util.zip.DataFormatException e) {
            throw new IOException(e);
        } finally {
            inflater.end();
        }
    }

    private int readVarIntFromStream() throws IOException {
        int value = 0;
        int position = 0;
        while (true) {
            int b = readBytePlain();
            value |= (b & 0x7F) << position;
            if ((b & 0x80) == 0) {
                return value;
            }
            position += 7;
            if (position >= 32) {
                throw new IOException("VarInt too big");
            }
        }
    }

    private int readBytePlain() throws IOException {
        if (decryptCipher == null) {
            int b = in.read();
            if (b < 0) throw new EOFException("connection closed");
            return b;
        }
        byte[] one = new byte[1];
        int n = in.read(one, 0, 1);
        if (n <= 0) throw new EOFException("connection closed");
        byte[] plain = decryptCipher.update(one);
        if (plain == null || plain.length == 0) {
            throw new IOException("cipher produced no output");
        }
        return plain[0] & 0xFF;
    }

    private byte[] readFully(int length) throws IOException {
        byte[] buf = new byte[length];
        int read = 0;
        while (read < length) {
            int n;
            if (decryptCipher == null) {
                n = in.read(buf, read, length - read);
            } else {
                byte[] chunk = new byte[length - read];
                n = in.read(chunk, 0, chunk.length);
                if (n > 0) {
                    byte[] plain = decryptCipher.update(chunk, 0, n);
                    if (plain != null && plain.length > 0) {
                        System.arraycopy(plain, 0, buf, read, plain.length);
                        n = plain.length;
                    } else {
                        n = 0;
                    }
                }
            }
            if (n < 0) throw new EOFException("connection closed while reading " + length + " bytes");
            read += n;
        }
        return buf;
    }

    private void close() {
        try {
            if (socket != null) socket.close();
        } catch (IOException ignored) {
        }
    }

    // ------------------------------------------------------------------
    // 编解码工具
    // ------------------------------------------------------------------

    private static void writeVarInt(ByteArrayOutputStream out, int value) {
        while (true) {
            if ((value & ~0x7F) == 0) {
                out.write(value);
                return;
            }
            out.write((value & 0x7F) | 0x80);
            value >>>= 7;
        }
    }

    private static int varIntSize(int value) {
        int size = 1;
        while ((value & ~0x7F) != 0) {
            size++;
            value >>>= 7;
        }
        return size;
    }

    private static int readVarIntFrom(byte[] data, int[] offset) throws IOException {
        int value = 0;
        int position = 0;
        while (true) {
            if (offset[0] >= data.length) throw new EOFException("VarInt past end");
            int b = data[offset[0]++] & 0xFF;
            value |= (b & 0x7F) << position;
            if ((b & 0x80) == 0) return value;
            position += 7;
            if (position >= 32) throw new IOException("VarInt too big");
        }
    }

    private static void writeString(ByteArrayOutputStream out, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        writeVarInt(out, bytes.length);
        out.write(bytes, 0, bytes.length);
    }

    private static String readString(byte[] data, int[] offset) throws IOException {
        int length = readVarIntFrom(data, offset);
        if (length < 0 || offset[0] + length > data.length) {
            throw new IOException("Illegal string length " + length);
        }
        String value = new String(data, offset[0], length, StandardCharsets.UTF_8);
        offset[0] += length;
        return value;
    }

    private static void writeByteArray(ByteArrayOutputStream out, byte[] data) {
        writeVarInt(out, data.length);
        out.write(data, 0, data.length);
    }

    private static byte[] readByteArray(byte[] data, int[] offset) throws IOException {
        int length = readVarIntFrom(data, offset);
        if (length < 0 || offset[0] + length > data.length) {
            throw new IOException("Illegal byte array length " + length);
        }
        byte[] value = new byte[length];
        System.arraycopy(data, offset[0], value, 0, length);
        offset[0] += length;
        return value;
    }

    private static void writeUuid(ByteArrayOutputStream out, UUID uuid) {
        for (byte b : uuidToBytes(uuid)) {
            out.write(b);
        }
    }

    private static String readUuidString(byte[] data, int[] offset) throws IOException {
        if (offset[0] + 16 > data.length) throw new EOFException("UUID past end");
        byte[] bytes = new byte[16];
        System.arraycopy(data, offset[0], bytes, 0, 16);
        offset[0] += 16;
        return new UUID(bytesToLong(bytes, 0), bytesToLong(bytes, 8)).toString();
    }

    private static long bytesToLong(byte[] bytes, int offset) {
        long value = 0;
        for (int i = 0; i < 8; i++) {
            value = (value << 8) | (bytes[offset + i] & 0xFFL);
        }
        return value;
    }

    private static byte[] uuidToBytes(UUID uuid) {
        byte[] out = new byte[16];
        long msb = uuid.getMostSignificantBits();
        long lsb = uuid.getLeastSignificantBits();
        for (int i = 7; i >= 0; i--) {
            out[i] = (byte) (msb & 0xFF);
            msb >>>= 8;
            out[8 + i] = (byte) (lsb & 0xFF);
            lsb >>>= 8;
        }
        return out;
    }
}
