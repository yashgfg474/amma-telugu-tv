package com.yash.ammatv;

import android.content.Context;
import android.util.Base64;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.spec.PKCS8EncodedKeySpec;

import javax.crypto.Cipher;

public class AdbClient {
    private static final int A_CNXN = 0x4e584e43;
    private static final int A_AUTH = 0x48545541;
    private static final int A_OPEN = 0x4e45504f;
    private static final int A_OKAY = 0x59414b4f;
    private static final int A_CLSE = 0x45534c43;
    private static final int A_WRTE = 0x45545257;
    private static final int VERSION = 0x01000000;
    private static final int MAX_PAYLOAD = 4096;
    private static final int MAX_INBOUND_PAYLOAD = 1024 * 1024;

    private static final int ADB_AUTH_TOKEN = 1;
    private static final int ADB_AUTH_SIGNATURE = 2;
    private static final int ADB_AUTH_RSAPUBLICKEY = 3;

    // DigestInfo header for SHA-1 (15 bytes) required by ADB AUTH_SIGNATURE
    private static final byte[] SHA1_DIGEST_INFO = new byte[]{
            0x30, 0x21, 0x30, 0x09, 0x06, 0x05, 0x2b, 0x0e,
            0x03, 0x02, 0x1a, 0x05, 0x00, 0x04, 0x14
    };

    private final PrivateKey privateKey;
    private final byte[] publicKeyBytes;
    private final Object connLock = new Object();

    private Socket socket;
    private DataInputStream in;
    private DataOutputStream out;
    private String connectedHost;
    private int connectedPort = -1;
    private int nextLocalId = 1;

    public AdbClient(Context context) throws Exception {
        String privPem = readAssetText(context, "adbkey");
        String pubText = readAssetText(context, "adbkey.pub").trim();

        String cleanPriv = privPem
                .replace("-----BEGIN PRIVATE KEY-----", "")
                .replace("-----END PRIVATE KEY-----", "")
                .replaceAll("\\s+", "");
        byte[] pkcs8 = Base64.decode(cleanPriv, Base64.DEFAULT);
        KeyFactory kf = KeyFactory.getInstance("RSA");
        this.privateKey = kf.generatePrivate(new PKCS8EncodedKeySpec(pkcs8));

        ByteArrayOutputStream pubOut = new ByteArrayOutputStream();
        pubOut.write(pubText.getBytes(StandardCharsets.UTF_8));
        pubOut.write(0);
        this.publicKeyBytes = pubOut.toByteArray();
    }

    private static String readAssetText(Context ctx, String name) throws Exception {
        try (InputStream is = ctx.getAssets().open(name)) {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int r;
            while ((r = is.read(buf)) != -1) {
                bos.write(buf, 0, r);
            }
            return new String(bos.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    public String runShellCommand(String host, int port, String command, int timeoutMs) throws Exception {
        if (host == null || host.trim().isEmpty()) {
            throw new IOException("TV IP not set");
        }
        synchronized (connLock) {
            boolean wasReused = isConnectedToLocked(host, port);
            if (!wasReused) {
                connectLocked(host, port, timeoutMs);
            }
            try {
                return executeShellLocked(command, timeoutMs);
            } catch (Exception firstError) {
                closeLocked();
                if (!wasReused) {
                    throw firstError;
                }
                // Reused TCP socket may have gone stale (e.g., TV slept/restarted); reconnect once
                connectLocked(host, port, timeoutMs);
                try {
                    return executeShellLocked(command, timeoutMs);
                } catch (Exception retryError) {
                    closeLocked();
                    throw retryError;
                }
            }
        }
    }

    public void close() {
        synchronized (connLock) {
            closeLocked();
        }
    }

    private boolean isConnectedToLocked(String host, int port) {
        return socket != null
                && !socket.isClosed()
                && socket.isConnected()
                && host.equals(connectedHost)
                && connectedPort == port;
    }

    private void connectLocked(String host, int port, int timeoutMs) throws Exception {
        closeLocked();
        Socket s = new Socket();
        try {
            s.connect(new InetSocketAddress(host, port), 2500);
            s.setSoTimeout(Math.max(timeoutMs, 4000));
            s.setTcpNoDelay(true);

            DataInputStream dis = new DataInputStream(s.getInputStream());
            DataOutputStream dos = new DataOutputStream(s.getOutputStream());

            byte[] banner = "host::\0".getBytes(StandardCharsets.UTF_8);
            sendPacket(dos, A_CNXN, VERSION, MAX_PAYLOAD, banner);

            boolean sentSignature = false;
            boolean sentPublicKey = false;
            for (int step = 0; step < 6; step++) {
                AdbPacket pkt = readPacket(dis);
                if (pkt.cmd == A_CNXN) {
                    this.socket = s;
                    this.in = dis;
                    this.out = dos;
                    this.connectedHost = host;
                    this.connectedPort = port;
                    this.nextLocalId = 1;
                    return;
                } else if (pkt.cmd == A_AUTH && pkt.arg0 == ADB_AUTH_TOKEN) {
                    if (!sentSignature) {
                        byte[] sig = signToken(pkt.payload);
                        sendPacket(dos, A_AUTH, ADB_AUTH_SIGNATURE, 0, sig);
                        sentSignature = true;
                    } else if (!sentPublicKey) {
                        sendPacket(dos, A_AUTH, ADB_AUTH_RSAPUBLICKEY, 0, publicKeyBytes);
                        sentPublicKey = true;
                    } else {
                        throw new IOException("TV rejected ADB authorization");
                    }
                } else {
                    throw new IOException("Unexpected ADB handshake packet: 0x" + Integer.toHexString(pkt.cmd));
                }
            }
            throw new IOException("ADB authentication did not complete");
        } catch (Exception e) {
            try {
                s.close();
            } catch (Exception ignored) {
            }
            throw e;
        }
    }

    private String executeShellLocked(String command, int timeoutMs) throws Exception {
        socket.setSoTimeout(Math.max(timeoutMs, 1500));

        int localId = nextLocalId++;
        if (nextLocalId > 1_000_000) {
            nextLocalId = 1;
        }

        byte[] openData = ("shell:" + command + "\0").getBytes(StandardCharsets.UTF_8);
        sendPacket(out, A_OPEN, localId, 0, openData);

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        int remoteId = 0;

        while (true) {
            AdbPacket pkt = readPacket(in);
            if ((pkt.cmd == A_OKAY || pkt.cmd == A_WRTE || pkt.cmd == A_CLSE) && pkt.arg1 != localId) {
                continue;
            }
            if (pkt.cmd == A_OKAY) {
                remoteId = pkt.arg0;
            } else if (pkt.cmd == A_WRTE) {
                remoteId = pkt.arg0;
                if (pkt.payload != null && pkt.payload.length > 0) {
                    output.write(pkt.payload);
                }
                sendPacket(out, A_OKAY, localId, remoteId, new byte[0]);
            } else if (pkt.cmd == A_CLSE) {
                if (remoteId != 0) {
                    sendPacket(out, A_CLSE, localId, remoteId, new byte[0]);
                }
                break;
            }
        }

        return new String(output.toByteArray(), StandardCharsets.UTF_8);
    }

    private void closeLocked() {
        if (socket != null) {
            try {
                socket.close();
            } catch (Exception ignored) {
            }
        }
        socket = null;
        in = null;
        out = null;
        connectedHost = null;
        connectedPort = -1;
    }

    private byte[] signToken(byte[] token) throws Exception {
        byte[] toSign = new byte[SHA1_DIGEST_INFO.length + token.length];
        System.arraycopy(SHA1_DIGEST_INFO, 0, toSign, 0, SHA1_DIGEST_INFO.length);
        System.arraycopy(token, 0, toSign, SHA1_DIGEST_INFO.length, token.length);

        Cipher cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding");
        cipher.init(Cipher.ENCRYPT_MODE, privateKey);
        return cipher.doFinal(toSign);
    }

    private static void sendPacket(DataOutputStream out, int cmd, int arg0, int arg1, byte[] payload) throws Exception {
        int len = payload == null ? 0 : payload.length;
        int checksum = 0;
        if (payload != null) {
            for (byte b : payload) {
                checksum += (b & 0xFF);
            }
        }
        ByteBuffer hdr = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN);
        hdr.putInt(cmd);
        hdr.putInt(arg0);
        hdr.putInt(arg1);
        hdr.putInt(len);
        hdr.putInt(checksum);
        hdr.putInt(~cmd);
        out.write(hdr.array());
        if (len > 0) {
            out.write(payload);
        }
        out.flush();
    }

    private static AdbPacket readPacket(DataInputStream in) throws Exception {
        byte[] hdrBytes = new byte[24];
        in.readFully(hdrBytes);
        ByteBuffer hdr = ByteBuffer.wrap(hdrBytes).order(ByteOrder.LITTLE_ENDIAN);
        int cmd = hdr.getInt();
        int arg0 = hdr.getInt();
        int arg1 = hdr.getInt();
        int len = hdr.getInt();
        hdr.getInt(); // checksum
        int magic = hdr.getInt();

        if (magic != ~cmd) {
            throw new IOException("Invalid ADB packet magic");
        }
        if (len < 0 || len > MAX_INBOUND_PAYLOAD) {
            throw new IOException("Invalid ADB payload size: " + len);
        }

        byte[] payload = new byte[len];
        if (len > 0) {
            in.readFully(payload);
        }
        return new AdbPacket(cmd, arg0, arg1, payload);
    }

    private static class AdbPacket {
        final int cmd;
        final int arg0;
        final int arg1;
        final byte[] payload;

        AdbPacket(int cmd, int arg0, int arg1, byte[] payload) {
            this.cmd = cmd;
            this.arg0 = arg0;
            this.arg1 = arg1;
            this.payload = payload;
        }
    }
}
