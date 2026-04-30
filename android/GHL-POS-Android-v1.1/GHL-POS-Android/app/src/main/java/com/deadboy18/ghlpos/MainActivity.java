package com.deadboy18.ghlpos;

import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.text.Spannable;
import android.text.SpannableString;
import android.text.SpannableStringBuilder;
import android.text.style.ForegroundColorSpan;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.Random;

public class MainActivity extends AppCompatActivity implements SerialManager.Callback {

    private SerialManager serial;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private SharedPreferences prefs;

    // UI refs
    private View statusDot;
    private TextView tvStatus, tvLog, tvDeviceInfo;
    private EditText etAmount, etInvoice, etCashier;
    private Button btnConnect, btnSale, btnVoid, btnSettle, btnRefund, btnStop, btnDemo, btnTheme;
    private CheckBox cbAutoInc;
    private ScrollView logScroll;

    // State
    private long rawAmt = 1;       // Amount in cents
    private boolean connected;
    private boolean demoMode;
    private boolean txBusy;
    private boolean darkTheme;
    private String pendingCmd = "020";
    private String lastReceipt = "";
    private final Random rng = new Random();

    // Colors for log
    private int colTx, colRx, colErr, colInfo, colOk, colEgg;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setContentView(R.layout.activity_main);

        prefs = getSharedPreferences("ghl", MODE_PRIVATE);
        serial = new SerialManager(this);
        serial.setCallback(this);

        bindViews();
        setupATMInput();
        loadSettings();
        updateLogColors();
    }

    private void bindViews() {
        statusDot = findViewById(R.id.statusDot);
        tvStatus = findViewById(R.id.tvStatus);
        tvLog = findViewById(R.id.tvLog);
        tvDeviceInfo = findViewById(R.id.tvDeviceInfo);
        etAmount = findViewById(R.id.etAmount);
        etInvoice = findViewById(R.id.etInvoice);
        etCashier = findViewById(R.id.etCashier);
        btnConnect = findViewById(R.id.btnConnect);
        btnSale = findViewById(R.id.btnSale);
        btnVoid = findViewById(R.id.btnVoid);
        btnSettle = findViewById(R.id.btnSettle);
        btnRefund = findViewById(R.id.btnRefund);
        btnStop = findViewById(R.id.btnStop);
        btnDemo = findViewById(R.id.btnDemo);
        btnTheme = findViewById(R.id.btnTheme);
        cbAutoInc = findViewById(R.id.cbAutoInc);
        logScroll = findViewById(R.id.logScroll);

        btnConnect.setOnClickListener(v -> toggleConnection());
        btnSale.setOnClickListener(v -> doTx("020"));
        btnVoid.setOnClickListener(v -> doTx("022"));
        btnSettle.setOnClickListener(v -> doTx("050"));
        btnRefund.setOnClickListener(v -> doTx("026"));
        btnStop.setOnClickListener(v -> cancelWait());
        btnDemo.setOnClickListener(v -> toggleDemo());
        btnTheme.setOnClickListener(v -> toggleTheme());

        findViewById(R.id.btnCopyLog).setOnClickListener(v -> copyLog());
        findViewById(R.id.btnSaveLog).setOnClickListener(v -> shareLog());
        findViewById(R.id.btnClearLog).setOnClickListener(v -> tvLog.setText(""));

        // Make status dot round
        GradientDrawable dot = new GradientDrawable();
        dot.setShape(GradientDrawable.OVAL);
        dot.setColor(0xFFCBD0D8);
        dot.setSize(dp(10), dp(10));
        statusDot.setBackground(dot);
    }

    // ========== ATM-STYLE INPUT ==========

    private void setupATMInput() {
        etAmount.setShowSoftInputOnFocus(false);
        etAmount.setOnKeyListener((v, keyCode, event) -> {
            if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
            if (keyCode >= KeyEvent.KEYCODE_0 && keyCode <= KeyEvent.KEYCODE_9) {
                if (String.valueOf(rawAmt).length() < 10) {
                    rawAmt = rawAmt * 10 + (keyCode - KeyEvent.KEYCODE_0);
                    updateAmtDisplay();
                    haptic(15);
                }
                return true;
            } else if (keyCode == KeyEvent.KEYCODE_DEL) {
                rawAmt = rawAmt / 10;
                updateAmtDisplay();
                return true;
            }
            return false;
        });

        etAmount.setOnClickListener(v -> etAmount.setSelection(etAmount.getText().length()));
    }

    public void onQuickAmount(View v) {
        try {
            rawAmt = Long.parseLong(v.getTag().toString());
            updateAmtDisplay();
            haptic(20);
        } catch (Exception ignored) {}
    }

    private void updateAmtDisplay() {
        etAmount.setText(String.format(Locale.US, "%.2f", rawAmt / 100.0));
    }

    // ========== CONNECTION ==========

    private void toggleConnection() {
        if (demoMode) { toggleDemo(); return; }
        if (connected) {
            serial.disconnect();
            setConnected(false, "Not connected");
            log("Disconnected", colInfo);
        } else {
            serial.connect();
            log("Requesting USB device...", colInfo);
        }
    }

    private void setConnected(boolean state, String label) {
        connected = state;
        tvStatus.setText(label);
        btnConnect.setText(state ? "Disconnect" : "Connect USB");

        GradientDrawable dot = new GradientDrawable();
        dot.setShape(GradientDrawable.OVAL);
        dot.setColor(state ? 0xFF059669 : 0xFFCBD0D8);
        dot.setSize(dp(10), dp(10));
        statusDot.setBackground(dot);

        btnSale.setEnabled(state);
        btnVoid.setEnabled(state);
        btnSettle.setEnabled(state);
        btnRefund.setEnabled(state);
    }

    // ========== TRANSACTIONS ==========

    private void doTx(String cmd) {
        if (txBusy) { toast("Transaction in progress..."); return; }
        if (!connected) { toast("Connect USB first"); return; }

        pendingCmd = cmd;
        double amt = cmd.equals("050") || cmd.equals("022") ? 0 : rawAmt / 100.0;
        int inv = cmd.equals("020") || cmd.equals("050") ? 0 : parseInt(etInvoice.getText().toString());
        String cshr = etCashier.getText().toString();

        if (amt == 0 && cmd.equals("020")) {
            toast("RM 0.00? Invisible air??");
            return;
        }

        haptic(40);
        byte[] pkt = GHLProtocol.buildPacket(cmd, amt, inv, cshr);
        String txName = GHLProtocol.CMD_NAMES.getOrDefault(cmd, "SALE");
        log("TX > " + GHLProtocol.toHex(pkt), colTx);
        log(txName + " | RM " + String.format(Locale.US, "%.2f", amt) + " | Inv: " + inv, colInfo);

        if (cmd.equals("020")) toast("SWIPE/INSERT CARD on Terminal");
        else if (cmd.equals("022")) toast("Void Transaction");
        else if (cmd.equals("026")) toast("Refund Mode Active");
        else if (cmd.equals("050")) toast("Settlement In Progress...");

        saveSettings();

        if (demoMode) {
            demoTransaction(cmd, amt);
            return;
        }

        try {
            serial.write(pkt);
        } catch (IOException e) {
            log("Write error: " + e.getMessage(), colErr);
            return;
        }

        txBusy = true;
        btnStop.setEnabled(true);
        setTxButtonsEnabled(false);
        log("Waiting for terminal response...", colInfo);
        serial.readUntilETX(65000);
    }

    private void cancelWait() {
        serial.cancelRead();
        txBusy = false;
        btnStop.setEnabled(false);
        setTxButtonsEnabled(connected || demoMode);
        log("Cancelled", colErr);
        toast("Cancelled");
        haptic(100);
    }

    private void setTxButtonsEnabled(boolean e) {
        btnSale.setEnabled(e);
        btnVoid.setEnabled(e);
        btnSettle.setEnabled(e);
        btnRefund.setEnabled(e);
    }

    // ========== SERIAL CALLBACKS (called from background thread) ==========

    @Override
    public void onConnected(String label, boolean isProlific) {
        ui.post(() -> {
            setConnected(true, label);
            log("Connected: " + label, colOk);
            haptic(100);
            if (!isProlific) log("WARNING: Non-Prolific adapter. May not work with GHL.", colErr);
            tvDeviceInfo.setText(Build.MANUFACTURER + " " + Build.MODEL + " | Android " + Build.VERSION.RELEASE + " | " + label);
            tvDeviceInfo.setVisibility(View.VISIBLE);
        });
    }

    @Override
    public void onConnectionFailed(String reason) {
        ui.post(() -> {
            log("Connection failed: " + reason, colErr);
            // Show dialog instead of toast so user can read the full message
            new AlertDialog.Builder(this)
                .setTitle("Connection Failed")
                .setMessage(reason)
                .setPositiveButton("OK", null)
                .show();
        });
    }

    @Override
    public void onDisconnected() {
        ui.post(() -> {
            setConnected(false, "USB Detached");
            log("USB device detached", colErr);
            haptic(200);
        });
    }

    @Override
    public void onDataReceived(byte[] data) {
        ui.post(() -> {
            txBusy = false;
            btnStop.setEnabled(false);
            setTxButtonsEnabled(true);

            log("RX < " + GHLProtocol.toHex(data), colRx);
            int payloadLen = data.length - 10;
            log("RX Payload: " + payloadLen + " bytes", colInfo);
            if (payloadLen < 125) log("WARN: Payload < 125. Pre-v1.0.17 firmware?", colErr);

            GHLProtocol.ParsedResponse r = GHLProtocol.parse(data, pendingCmd);
            if (r.approved) {
                log("STATUS: APPROVED", colOk);
                toast("TRANSACTION APPROVED");
                haptic(200);
                showReceipt(r);
                autoIncrement();
            } else {
                log("STATUS: DECLINED (" + r.errorCode + ")", colErr);
                toast("DECLINED: " + r.errorCode);
                haptic(300);
            }
        });
    }

    @Override
    public void onReadTimeout() {
        ui.post(() -> {
            txBusy = false;
            btnStop.setEnabled(false);
            setTxButtonsEnabled(connected);
            log("Timeout - no response", colErr);
            toast("Timeout");
        });
    }

    @Override
    public void onReadCancelled() {
        ui.post(() -> {
            txBusy = false;
            btnStop.setEnabled(false);
            setTxButtonsEnabled(connected || demoMode);
            log("Cancelled", colErr);
        });
    }

    @Override
    public void onError(String msg) {
        ui.post(() -> {
            txBusy = false;
            btnStop.setEnabled(false);
            setTxButtonsEnabled(connected);
            log("Error: " + msg, colErr);
        });
    }

    // ========== DEMO MODE ==========

    private void toggleDemo() {
        demoMode = !demoMode;
        if (demoMode) {
            setConnected(true, "DEMO");
            log("Demo mode enabled", colEgg);
            toast("DEMO MODE");
        } else {
            setConnected(false, "Not connected");
            log("Demo mode disabled", colInfo);
        }
    }

    private void demoTransaction(String cmd, double amt) {
        txBusy = true;
        btnStop.setEnabled(true);
        setTxButtonsEnabled(false);
        log("Waiting for terminal response...", colInfo);

        ui.postDelayed(() -> {
            if (!demoMode) return;
            txBusy = false;
            btnStop.setEnabled(false);
            setTxButtonsEnabled(true);

            String[] cards = {"04", "05", "08", "10", "11"};
            String tc = cards[rng.nextInt(cards.length)];
            String tn = GHLProtocol.CARD_TYPES.getOrDefault(tc, "UNKNOWN");
            String auth = String.valueOf(100000 + rng.nextInt(900000));
            String stan = String.format(Locale.US, "%06d", 1 + rng.nextInt(999999));
            String ts = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date());

            log("RX < 02 [DEMO DATA] 03", colRx);
            log("STATUS: APPROVED", colOk);
            toast("TRANSACTION APPROVED");
            haptic(200);

            String txType = GHLProtocol.CMD_NAMES.getOrDefault(cmd, "SALE");
            lastReceipt = "MERCHANT ID:  000000000012345\nTERMINAL ID:  20991234\nTIME:         " + ts +
                    "\nBATCH NO:     000001\n\n------------------------------\nSTAN:         " + stan +
                    "\nINVOICE:      " + etInvoice.getText().toString() +
                    "\n------------------------------\n\nTRANS TYPE:   " + txType +
                    "\nCASHIER ID:   " + etCashier.getText().toString() +
                    "\n\nCARD NO:      1619************2345\nCARD TYPE:    " + tc + " (" + tn + ")" +
                    "\nAUTH CODE:    " + auth +
                    "\n\n------------------------------\nGROSS AMT:    RM " + String.format(Locale.US, "%.2f", amt) +
                    "\nNET AMT:      RM " + String.format(Locale.US, "%.2f", amt) +
                    "\n------------------------------\n\n        THANK YOU!\n\n   *** DEMO ***\n";

            showReceiptDialog(lastReceipt);
            autoIncrement();
        }, 2000 + rng.nextInt(1500));
    }

    // ========== RECEIPT ==========

    private void showReceipt(GHLProtocol.ParsedResponse r) {
        lastReceipt = GHLProtocol.buildReceipt(r);
        showReceiptDialog(lastReceipt);
    }

    private void showReceiptDialog(String text) {
        AlertDialog.Builder b = new AlertDialog.Builder(this);
        b.setTitle("*** APPROVED ***");
        b.setMessage(text);
        b.setPositiveButton("Close", null);
        b.setNeutralButton("Copy", (d, w) -> {
            ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            cm.setPrimaryClip(ClipData.newPlainText("receipt", "*** APPROVED ***\n" + text));
            toast("Receipt copied");
        });
        b.setNegativeButton("Share", (d, w) -> {
            Intent share = new Intent(Intent.ACTION_SEND);
            share.setType("text/plain");
            share.putExtra(Intent.EXTRA_TEXT, "*** APPROVED ***\n" + text);
            startActivity(Intent.createChooser(share, "Share Receipt"));
        });
        b.show();
    }

    private void autoIncrement() {
        if (cbAutoInc.isChecked()) {
            int cur = parseInt(etInvoice.getText().toString());
            etInvoice.setText(String.format(Locale.US, "%06d", cur + 1));
            saveSettings();
        }
    }

    // ========== LOG ==========

    private void log(String msg, int color) {
        String ts = new SimpleDateFormat("[HH:mm:ss] ", Locale.US).format(new Date());
        SpannableString line = new SpannableString(ts + msg + "\n");
        line.setSpan(new ForegroundColorSpan(color), 0, line.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        tvLog.append(line);
        logScroll.post(() -> logScroll.fullScroll(View.FOCUS_DOWN));
    }

    private void copyLog() {
        ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        cm.setPrimaryClip(ClipData.newPlainText("log", tvLog.getText()));
        toast("Log copied");
    }

    private void shareLog() {
        Intent share = new Intent(Intent.ACTION_SEND);
        share.setType("text/plain");
        share.putExtra(Intent.EXTRA_TEXT, tvLog.getText().toString());
        startActivity(Intent.createChooser(share, "Save Log"));
    }

    // ========== THEME ==========

    private void toggleTheme() {
        darkTheme = !darkTheme;
        prefs.edit().putBoolean("dark", darkTheme).apply();
        btnTheme.setText(darkTheme ? "🌙" : "☀");
        updateLogColors();
        // Full theme change would require recreate(), but for a lightweight approach
        // we just update the log colors. Full dark theme can be added via DayNight.
        toast(darkTheme ? "Dark mode (restart for full effect)" : "Light mode");
    }

    private void updateLogColors() {
        if (darkTheme) {
            colTx = 0xFF60A5FA; colRx = 0xFF34D399; colErr = 0xFFEF4444;
            colInfo = 0xFF6B7280; colOk = 0xFF34D399; colEgg = 0xFFF59E0B;
        } else {
            colTx = 0xFF2563EB; colRx = 0xFF059669; colErr = 0xFFDC2626;
            colInfo = 0xFF8E95A5; colOk = 0xFF059669; colEgg = 0xFFD97706;
        }
    }

    // ========== SETTINGS ==========

    private void saveSettings() {
        prefs.edit()
                .putString("invoice", etInvoice.getText().toString())
                .putString("cashier", etCashier.getText().toString())
                .putBoolean("autoInc", cbAutoInc.isChecked())
                .putLong("rawAmt", rawAmt)
                .apply();
    }

    private void loadSettings() {
        etInvoice.setText(prefs.getString("invoice", "000001"));
        etCashier.setText(prefs.getString("cashier", "99"));
        cbAutoInc.setChecked(prefs.getBoolean("autoInc", true));
        rawAmt = prefs.getLong("rawAmt", 1);
        darkTheme = prefs.getBoolean("dark", false);
        updateAmtDisplay();
        btnTheme.setText(darkTheme ? "🌙" : "☀");
    }

    // ========== UTILS ==========

    private void haptic(int ms) {
        try {
            Vibrator v = (Vibrator) getSystemService(VIBRATOR_SERVICE);
            if (v != null && v.hasVibrator()) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    v.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE));
                }
            }
        } catch (Exception ignored) {}
    }

    private void toast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
    }

    private int dp(int val) { return (int) (val * getResources().getDisplayMetrics().density); }

    private int parseInt(String s) {
        try { return Integer.parseInt(s.trim()); }
        catch (Exception e) { return 0; }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        saveSettings();
        serial.destroy();
    }
}
