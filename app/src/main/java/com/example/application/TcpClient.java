package com.example.application;

import android.os.Handler;
import android.os.Looper;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 和 ESP8266 之间的 TCP 长连接。
 *
 * 协议（和 51 固件一一对应）：
 *   收  "sXY"   X=灯 Y=风扇，'1'开 '0'关，例如 s10 = 灯开风扇关
 *   发  "i1"    切换灯    （注意是"翻转"，不是"打开"）
 *       "i2"    切换风扇
 *       "i0"    心跳空包。51 收到 'i' 后只认 '1' 和 '2'，'0' 会被丢弃，
 *               所以这是个天然的空操作，单片机固件不用做任何改动。
 *
 * 断线后自动重连，不需要外部干预。
 */
public class TcpClient {

    public interface Listener {
        /** 连接状态变化。已在主线程回调。 */
        void onConnectionChanged(boolean connected);

        /** 收到设备状态。已在主线程回调。 */
        void onDeviceStatus(boolean light, boolean fan);
    }

    private static final int CONNECT_TIMEOUT_MS = 5000;
    private static final int RECONNECT_DELAY_MS = 3000;
    private static final int HEARTBEAT_PERIOD_MS = 5000;

    /**
     * 读超时看门狗。
     *
     * 我们每 5 秒发一次 i0，51 收到后会立刻回一条 sXX。所以正常情况下
     * 这条连接上不可能连续 20 秒一个字节都没有。一旦超时就说明链路已经
     * 死了（ESP 断电、WiFi 掉线、路由器把连接回收了），直接重连。
     *
     * 光靠"写失败"是测不出来的：对方拔电时本地 socket 看上去还是好的，
     * 数据只是悄悄进了发送缓冲区然后石沉大海。
     */
    private static final int READ_TIMEOUT_MS = 20000;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Listener listener;

    /** 发送统一走这个单线程，保证不会在主线程做网络操作 */
    private final ExecutorService sendExecutor = Executors.newSingleThreadExecutor();

    private ScheduledExecutorService heartbeatExecutor;
    private Thread workerThread;

    private volatile String host;
    private volatile int port;
    private volatile boolean running = false;
    private volatile boolean connected = false;
    private volatile Socket socket;

    public TcpClient(Listener listener) {
        this.listener = listener;
    }

    public boolean isConnected() {
        return connected;
    }

    /** 启动（或用新地址重启）。重复调用是安全的。 */
    public synchronized void start(String host, int port) {
        stop();

        this.host = host;
        this.port = port;
        this.running = true;

        workerThread = new Thread(this::connectLoop, "tcp-worker");
        workerThread.start();

        heartbeatExecutor = Executors.newSingleThreadScheduledExecutor();
        heartbeatExecutor.scheduleWithFixedDelay(
                () -> send("i0"),
                HEARTBEAT_PERIOD_MS,
                HEARTBEAT_PERIOD_MS,
                TimeUnit.MILLISECONDS);
    }

    public synchronized void stop() {
        running = false;

        if (heartbeatExecutor != null) {
            heartbeatExecutor.shutdownNow();
            heartbeatExecutor = null;
        }

        closeQuietly(socket);
        socket = null;

        if (workerThread != null) {
            workerThread.interrupt();
            workerThread = null;
        }

        setConnected(false);
    }

    /** 彻底释放。Activity 销毁时调用，调用后这个对象不能再用。 */
    public synchronized void release() {
        stop();
        sendExecutor.shutdownNow();
    }

    /** 发送一条指令。可以在主线程调用，内部会切到后台线程。 */
    public void send(final String text) {
        sendExecutor.execute(() -> {
            Socket s = socket;
            if (s == null || s.isClosed()) {
                return;
            }
            try {
                OutputStream out = s.getOutputStream();
                out.write(text.getBytes(StandardCharsets.US_ASCII));
                out.flush();
            } catch (IOException e) {
                // 写失败说明链路已经死了。把 socket 关掉，
                // 读线程会立刻感知到并走重连流程。
                closeQuietly(s);
            }
        });
    }

    // ------------------------------------------------------------------

    private void connectLoop() {
        while (running) {
            Socket s = null;
            try {
                s = new Socket();
                s.connect(new InetSocketAddress(host, port), CONNECT_TIMEOUT_MS);
                s.setKeepAlive(true);
                s.setTcpNoDelay(true);
                s.setSoTimeout(READ_TIMEOUT_MS);   // 超时会抛 SocketTimeoutException，走重连

                socket = s;
                setConnected(true);

                readLoop(s);        // 阻塞，直到对方断开或出错
            } catch (IOException ignored) {
                // 连不上或读出错，下面统一走重连
            } finally {
                closeQuietly(s);
                socket = null;
                setConnected(false);
            }

            if (!running) {
                break;
            }
            try {
                Thread.sleep(RECONNECT_DELAY_MS);
            } catch (InterruptedException e) {
                break;
            }
        }
    }

    /**
     * 逐字节解析 "sXY"。
     *
     * TCP 是字节流，不保证一次 read 刚好拿到完整的 3 个字节——可能拆成
     * "s1" + "0"，也可能两条状态粘成 "s10s01"。所以这里用状态机逐字节走，
     * 不用 readLine 之类按包处理的写法。遇到脏数据会自动重新对齐。
     */
    private void readLoop(Socket s) throws IOException {
        InputStream in = s.getInputStream();

        int stage = 0;              // 0=等's'  1=等灯状态  2=等风扇状态
        boolean light = false;
        int b;

        while ((b = in.read()) != -1) {
            char c = (char) b;

            switch (stage) {
                case 0:
                    if (c == 's') stage = 1;
                    break;

                case 1:
                    if (c == '0' || c == '1') {
                        light = (c == '1');
                        stage = 2;
                    } else {
                        stage = (c == 's') ? 1 : 0;
                    }
                    break;

                case 2:
                    if (c == '0' || c == '1') {
                        notifyStatus(light, c == '1');
                        stage = 0;
                    } else {
                        stage = (c == 's') ? 1 : 0;
                    }
                    break;
            }
        }
        // read 返回 -1 = 对方正常关闭了连接
    }

    private void setConnected(final boolean value) {
        if (connected == value) {
            return;
        }
        connected = value;
        mainHandler.post(() -> listener.onConnectionChanged(value));
    }

    private void notifyStatus(final boolean light, final boolean fan) {
        mainHandler.post(() -> listener.onDeviceStatus(light, fan));
    }

    private static void closeQuietly(Socket s) {
        if (s != null) {
            try {
                s.close();
            } catch (IOException ignored) {
            }
        }
    }
}
