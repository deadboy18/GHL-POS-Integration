package com.deadboy18.ghlpos;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbManager;
import android.os.Build;

import com.hoho.android.usbserial.driver.UsbSerialDriver;
import com.hoho.android.usbserial.driver.UsbSerialPort;
import com.hoho.android.usbserial.driver.UsbSerialProber;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class SerialManager {

    private static final String ACTION_USB_PERM = "com.deadboy18.ghlpos.USB_PERMISSION";
    private static final int BAUD = 9600;

    public interface Callback {
        void onConnected(String label, boolean isProlific);
        void onConnectionFailed(String reason);
        void onDisconnected();
        void onDataReceived(byte[] data);
        void onReadTimeout();
        void onReadCancelled();
        void onError(String msg);
    }

    private final Context ctx;
    private final UsbManager usbManager;
    private final ExecutorService exec = Executors.newSingleThreadExecutor();
    private Callback callback;

    private UsbSerialPort port;
    private UsbDeviceConnection conn;
    private volatile boolean connected;
    private volatile boolean cancelRead;
    private boolean receiverRegistered;

    private final BroadcastReceiver usbReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent intent) {
            try {
                if (ACTION_USB_PERM.equals(intent.getAction())) {
                    UsbDevice dev = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
                    if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false) && dev != null) {
                        openDevice(dev);
                    } else {
                        if (callback != null) callback.onConnectionFailed("USB permission denied.\nTap Allow when prompted.");
                    }
                } else if (UsbManager.ACTION_USB_DEVICE_DETACHED.equals(intent.getAction())) {
                    disconnect();
                    if (callback != null) callback.onDisconnected();
                }
            } catch (Exception e) {
                if (callback != null) callback.onError("USB event error: " + e.getMessage());
            }
        }
    };

    public SerialManager(Context ctx) {
        this.ctx = ctx;
        this.usbManager = (UsbManager) ctx.getSystemService(Context.USB_SERVICE);

        try {
            IntentFilter f = new IntentFilter();
            f.addAction(ACTION_USB_PERM);
            f.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);
            // MUST use RECEIVER_EXPORTED on Android 13+ because USB permission
            // broadcasts come from the system (UsbManager), not from our app
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                ctx.registerReceiver(usbReceiver, f, Context.RECEIVER_EXPORTED);
            } else {
                ctx.registerReceiver(usbReceiver, f);
            }
            receiverRegistered = true;
        } catch (Exception e) {
            receiverRegistered = false;
        }
    }

    public void setCallback(Callback cb) { this.callback = cb; }
    public boolean isConnected() { return connected; }

    public void connect() {
        try {
            if (usbManager == null) {
                if (callback != null) callback.onConnectionFailed("USB not supported on this device.");
                return;
            }

            List<UsbSerialDriver> drivers;
            try {
                drivers = UsbSerialProber.getDefaultProber().findAllDrivers(usbManager);
            } catch (Exception e) {
                if (callback != null) callback.onConnectionFailed("USB scan error: " + e.getMessage());
                return;
            }

            if (drivers == null || drivers.isEmpty()) {
                // Show connected USB devices for debugging
                String devList = listDevices();
                if (callback != null) callback.onConnectionFailed(
                        "No USB serial device found.\n\n" +
                        "Check:\n" +
                        "\u2022 Prolific PL2303 adapter plugged in\n" +
                        "\u2022 USB OTG adapter connected\n" +
                        "\u2022 USB mode: 'This device' (swipe notification)\n\n" +
                        "USB devices detected:\n" + devList
                );
                return;
            }

            UsbSerialDriver driver = drivers.get(0);
            UsbDevice dev = driver.getDevice();

            if (usbManager.hasPermission(dev)) {
                openDevice(dev);
            } else {
                try {
                    int flags = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                            ? PendingIntent.FLAG_MUTABLE : 0;
                    Intent permIntent = new Intent(ACTION_USB_PERM);
                    permIntent.setPackage(ctx.getPackageName());
                    PendingIntent pi = PendingIntent.getBroadcast(ctx, 0, permIntent, flags);
                    usbManager.requestPermission(dev, pi);
                } catch (Exception e) {
                    if (callback != null) callback.onConnectionFailed("Permission error: " + e.getMessage());
                }
            }
        } catch (Exception e) {
            if (callback != null) callback.onConnectionFailed("Connect error: " + e.getMessage());
        }
    }

    public String listDevices() {
        if (usbManager == null) return "USB not available";
        try {
            StringBuilder sb = new StringBuilder();
            for (UsbDevice dev : usbManager.getDeviceList().values()) {
                sb.append(String.format("  %04X:%04X %s\n",
                        dev.getVendorId(), dev.getProductId(),
                        dev.getProductName() != null ? dev.getProductName() : "Unknown"));
            }
            if (sb.length() == 0) sb.append("  (none)");
            return sb.toString().trim();
        } catch (Exception e) {
            return "  Error: " + e.getMessage();
        }
    }

    private void openDevice(UsbDevice dev) {
        exec.execute(() -> {
            try {
                List<UsbSerialDriver> drivers = UsbSerialProber.getDefaultProber().findAllDrivers(usbManager);
                if (drivers == null || drivers.isEmpty()) {
                    if (callback != null) callback.onConnectionFailed("No driver found");
                    return;
                }

                UsbSerialDriver driver = drivers.get(0);
                conn = usbManager.openDevice(driver.getDevice());
                if (conn == null) {
                    if (callback != null) callback.onConnectionFailed("Cannot open USB device.\nTry unplugging and re-plugging.");
                    return;
                }

                List<UsbSerialPort> ports = driver.getPorts();
                if (ports == null || ports.isEmpty()) {
                    if (callback != null) callback.onConnectionFailed("No serial ports on device");
                    return;
                }

                port = ports.get(0);
                port.open(conn);
                port.setParameters(BAUD, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE);
                port.setDTR(true);
                port.setRTS(true);
                connected = true;

                String chip = driver.getClass().getSimpleName().replace("SerialDriver", "");
                boolean prolific = chip.toLowerCase().contains("prolific");
                String label = String.format("USB %04X:%04X (%s)", dev.getVendorId(), dev.getProductId(), chip);

                if (callback != null) callback.onConnected(label, prolific);
            } catch (Exception e) {
                if (callback != null) callback.onConnectionFailed("Open failed: " + e.getMessage());
            }
        });
    }

    public void disconnect() {
        cancelRead = true;
        connected = false;
        try { if (port != null) port.close(); } catch (Exception ignored) {}
        try { if (conn != null) conn.close(); } catch (Exception ignored) {}
        port = null;
        conn = null;
    }

    public void write(byte[] data) throws IOException {
        if (!connected || port == null) throw new IOException("Not connected");
        port.write(data, 2000);
    }

    public void readUntilETX(int timeoutMs) {
        cancelRead = false;
        exec.execute(() -> {
            try {
                byte[] buf = new byte[256];
                byte[] result = new byte[512];
                int total = 0;
                long start = System.currentTimeMillis();
                boolean gotETX = false;

                while (!cancelRead && (System.currentTimeMillis() - start) < timeoutMs) {
                    int len = port.read(buf, 500);
                    if (len > 0) {
                        for (int i = 0; i < len && total < result.length; i++) {
                            result[total++] = buf[i];
                            if ((buf[i] & 0xFF) == GHLProtocol.ETX) { gotETX = true; break; }
                        }
                        if (gotETX) break;
                    }
                }

                if (cancelRead) {
                    if (callback != null) callback.onReadCancelled();
                } else if (gotETX) {
                    byte[] trimmed = new byte[total];
                    System.arraycopy(result, 0, trimmed, 0, total);
                    if (callback != null) callback.onDataReceived(trimmed);
                } else {
                    if (callback != null) callback.onReadTimeout();
                }
            } catch (IOException e) {
                if (callback != null) callback.onError(e.getMessage());
            }
        });
    }

    public void cancelRead() { cancelRead = true; }

    public void destroy() {
        disconnect();
        if (receiverRegistered) {
            try { ctx.unregisterReceiver(usbReceiver); } catch (Exception ignored) {}
        }
        exec.shutdownNow();
    }
}
