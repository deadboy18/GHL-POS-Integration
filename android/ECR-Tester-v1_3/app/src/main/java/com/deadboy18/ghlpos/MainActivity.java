package com.deadboy18.ghlpos;

import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.net.Uri;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.text.Spannable;
import android.text.SpannableString;
import android.text.style.ForegroundColorSpan;
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
import androidx.appcompat.app.AppCompatDelegate;

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
    private TextView tvStatus, tvLog, tvDeviceInfo, tvTitle;
    private EditText etAmount, etInvoice, etCashier;
    private Button btnConnect, btnSale, btnVoid, btnSettle, btnRefund, btnStop, btnDemo, btnTheme, btnLang, btnInfo;
    private CheckBox cbAutoInc;
    private ScrollView logScroll;

    // State
    private long rawAmt = 1;
    private boolean connected;
    private boolean demoMode;
    private boolean txBusy;
    private boolean darkTheme;
    private String pendingCmd = "020";
    private String lastReceipt = "";
    private final Random rng = new Random();
    private int titleTapCount = 0;
    private long lastTitleTap = 0;
    private static final String[] LANGS = {"en", "ms", "zh"};
    private static final String[] LANG_LABELS = {"EN", "BM", "中"};
    private int langIndex = 0;

    // Colors for log
    private int colTx, colRx, colErr, colInfo, colOk, colEgg;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        // Apply saved settings before super.onCreate
        prefs = getSharedPreferences("ghl", MODE_PRIVATE);

        // Restore dark mode
        darkTheme = prefs.getBoolean("dark", false);
        AppCompatDelegate.setDefaultNightMode(
            darkTheme ? AppCompatDelegate.MODE_NIGHT_YES : AppCompatDelegate.MODE_NIGHT_NO
        );

        // Restore saved locale so language survives theme toggle & vice versa
        langIndex = prefs.getInt("lang", 0);
        Locale locale = new Locale(LANGS[langIndex]);
        Locale.setDefault(locale);
        Configuration config = new Configuration(getResources().getConfiguration());
        config.setLocale(locale);
        getResources().updateConfiguration(config, getResources().getDisplayMetrics());

        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setContentView(R.layout.activity_main);

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
        tvTitle = findViewById(R.id.tvTitle);
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
        btnLang = findViewById(R.id.btnLang);
        btnInfo = findViewById(R.id.btnInfo);
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
        btnLang.setOnClickListener(v -> cycleLang());
        btnInfo.setOnClickListener(v -> showAbout());

        findViewById(R.id.btnCopyLog).setOnClickListener(v -> copyLog());
        findViewById(R.id.btnSaveLog).setOnClickListener(v -> shareLog());
        findViewById(R.id.btnClearLog).setOnClickListener(v -> tvLog.setText(""));

        // Easter egg: tap title 7 times
        tvTitle.setOnClickListener(v -> {
            long now = System.currentTimeMillis();
            if (now - lastTitleTap > 2000) titleTapCount = 0;
            lastTitleTap = now;
            titleTapCount++;
            if (titleTapCount == 7) {
                titleTapCount = 0;
                haptic(200);
                log(getString(R.string.easter_egg), colEgg);
                new AlertDialog.Builder(this)
                    .setTitle(getString(R.string.about_title))
                    .setMessage(getString(R.string.about_text))
                    .setPositiveButton("Nice! 🔥", null)
                    .show();
            } else if (titleTapCount >= 4) {
                toast((7 - titleTapCount) + " more...");
            }
        });

        // Status dot
        GradientDrawable dot = new GradientDrawable();
        dot.setShape(GradientDrawable.OVAL);
        dot.setColor(0xFFCBD0D8);
        dot.setSize(dp(10), dp(10));
        statusDot.setBackground(dot);

        // Theme button icon
        btnTheme.setText(darkTheme ? "\uD83C\uDF19" : "☀");
    }

    // ========== ATM-STYLE INPUT ==========

    private boolean updatingAmount = false;

    private void setupATMInput() {
        etAmount.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override
            public void afterTextChanged(android.text.Editable s) {
                if (updatingAmount) return;
                // Extract only digits from what user typed
                String digits = s.toString().replaceAll("[^0-9]", "");
                if (digits.isEmpty()) digits = "0";
                // Cap at 10 digits to prevent overflow
                if (digits.length() > 10) digits = digits.substring(0, 10);
                rawAmt = Long.parseLong(digits);
                updateAmtDisplay();
                haptic(15);
            }
        });
        // Also keep hardware key support
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
        updatingAmount = true;
        etAmount.setText(String.format(Locale.US, "%.2f", rawAmt / 100.0));
        etAmount.setSelection(etAmount.getText().length());
        updatingAmount = false;
    }

    // ========== LANGUAGE ==========

    private void cycleLang() {
        langIndex = (langIndex + 1) % LANGS.length;
        prefs.edit().putInt("lang", langIndex).apply();
        btnLang.setText(LANG_LABELS[langIndex]);
        applyLocale(LANGS[langIndex]);
        haptic(20);
    }

    private void applyLocale(String langCode) {
        Locale locale = new Locale(langCode);
        Locale.setDefault(locale);
        Resources res = getResources();
        Configuration config = new Configuration(res.getConfiguration());
        config.setLocale(locale);
        res.updateConfiguration(config, res.getDisplayMetrics());
        recreate();
    }

    // ========== CONNECTION ==========

    private void toggleConnection() {
        if (demoMode) { toggleDemo(); return; }
        if (connected) {
            serial.disconnect();
            setConnected(false, getString(R.string.not_connected));
            log("Disconnected", colInfo);
        } else {
            serial.connect();
            log("Requesting USB device...", colInfo);
        }
    }

    private void setConnected(boolean state, String label) {
        connected = state;
        tvStatus.setText(label);
        btnConnect.setText(state ? getString(R.string.disconnect) : getString(R.string.connect_usb));

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
        if (txBusy) { toast(getString(R.string.tx_in_progress)); return; }
        if (!connected) { toast(getString(R.string.connect_first)); return; }

        pendingCmd = cmd;
        double amt = cmd.equals("050") || cmd.equals("022") ? 0 : rawAmt / 100.0;
        int inv = cmd.equals("020") || cmd.equals("050") ? 0 : parseInt(etInvoice.getText().toString());
        String cshr = etCashier.getText().toString();

        if (amt == 0 && cmd.equals("020")) {
            toast(getString(R.string.zero_amount));
            return;
        }

        haptic(40);
        byte[] pkt = GHLProtocol.buildPacket(cmd, amt, inv, cshr);
        String txName = GHLProtocol.CMD_NAMES.getOrDefault(cmd, "SALE");
        log("TX > " + GHLProtocol.toHex(pkt), colTx);
        log(txName + " | RM " + String.format(Locale.US, "%.2f", amt) + " | Inv: " + inv, colInfo);

        if (cmd.equals("020")) toast(getString(R.string.swipe_card));
        else if (cmd.equals("022")) toast(getString(R.string.void_active));
        else if (cmd.equals("026")) toast(getString(R.string.refund_active));
        else if (cmd.equals("050")) toast(getString(R.string.settle_active));

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
        log(getString(R.string.waiting), colInfo);
        serial.readUntilETX(65000);
    }

    private void cancelWait() {
        serial.cancelRead();
        txBusy = false;
        btnStop.setEnabled(false);
        setTxButtonsEnabled(connected || demoMode);
        log(getString(R.string.cancelled), colErr);
        toast(getString(R.string.cancelled));
        haptic(100);
    }

    private void setTxButtonsEnabled(boolean e) {
        btnSale.setEnabled(e);
        btnVoid.setEnabled(e);
        btnSettle.setEnabled(e);
        btnRefund.setEnabled(e);
    }

    // ========== SERIAL CALLBACKS ==========

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
                log("STATUS: " + getString(R.string.approved), colOk);
                toast(getString(R.string.approved));
                haptic(200);
                showReceipt(r);
                autoIncrement();
            } else {
                log("STATUS: " + getString(R.string.declined) + " (" + r.errorCode + ")", colErr);
                toast(getString(R.string.declined) + ": " + r.errorCode);
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
            log(getString(R.string.timeout) + " - no response", colErr);
            toast(getString(R.string.timeout));
        });
    }

    @Override
    public void onReadCancelled() {
        ui.post(() -> {
            txBusy = false;
            btnStop.setEnabled(false);
            setTxButtonsEnabled(connected || demoMode);
            log(getString(R.string.cancelled), colErr);
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
            setConnected(true, getString(R.string.demo_mode));
            log(getString(R.string.demo_enabled), colEgg);
            toast(getString(R.string.demo_mode));
        } else {
            setConnected(false, getString(R.string.not_connected));
            log(getString(R.string.demo_disabled), colInfo);
        }
    }

    private void demoTransaction(String cmd, double amt) {
        txBusy = true;
        btnStop.setEnabled(true);
        setTxButtonsEnabled(false);
        log(getString(R.string.waiting), colInfo);

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
            log("STATUS: " + getString(R.string.approved), colOk);
            toast(getString(R.string.approved));
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
        b.setTitle(getString(R.string.receipt_title));
        b.setMessage(text);
        b.setPositiveButton(getString(R.string.close), null);
        b.setNeutralButton(getString(R.string.copy), (d, w) -> {
            ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            cm.setPrimaryClip(ClipData.newPlainText("receipt", getString(R.string.receipt_title) + "\n" + text));
            toast(getString(R.string.receipt_copied));
        });
        b.setNegativeButton(getString(R.string.share), (d, w) -> {
            Intent share = new Intent(Intent.ACTION_SEND);
            share.setType("text/plain");
            share.putExtra(Intent.EXTRA_TEXT, getString(R.string.receipt_title) + "\n" + text);
            startActivity(Intent.createChooser(share, getString(R.string.share)));
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
        toast(getString(R.string.log_copied));
    }

    private void shareLog() {
        Intent share = new Intent(Intent.ACTION_SEND);
        share.setType("text/plain");
        share.putExtra(Intent.EXTRA_TEXT, tvLog.getText().toString());
        startActivity(Intent.createChooser(share, getString(R.string.save)));
    }

    // ========== THEME ==========

    private void toggleTheme() {
        darkTheme = !darkTheme;
        prefs.edit().putBoolean("dark", darkTheme).apply();
        // DayNight handles it properly - no janky restart
        AppCompatDelegate.setDefaultNightMode(
            darkTheme ? AppCompatDelegate.MODE_NIGHT_YES : AppCompatDelegate.MODE_NIGHT_NO
        );
    }

    // ========== ABOUT ==========

    private void showAbout() {
        new AlertDialog.Builder(this)
            .setTitle(getString(R.string.about_title))
            .setMessage(getString(R.string.about_text))
            .setPositiveButton(getString(R.string.close), null)
            .setNeutralButton(getString(R.string.github_label), (d, w) -> {
                Intent browser = new Intent(Intent.ACTION_VIEW,
                    Uri.parse("https://github.com/deadboy18/GHL-POS-Integration"));
                startActivity(browser);
            })
            .show();
    }

    private void updateLogColors() {
        int nightMode = getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
        boolean isNight = nightMode == Configuration.UI_MODE_NIGHT_YES;
        if (isNight) {
            colTx = 0xFF60A5FA; colRx = 0xFF34D399; colErr = 0xFFEF4444;
            colInfo = 0xFF6B7280; colOk = 0xFF34D399; colEgg = 0xFFFB923C;
        } else {
            colTx = 0xFF2563EB; colRx = 0xFF059669; colErr = 0xFFDC2626;
            colInfo = 0xFF8E95A5; colOk = 0xFF059669; colEgg = 0xFFF97316;
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
        langIndex = prefs.getInt("lang", 0);
        updateAmtDisplay();
        btnLang.setText(LANG_LABELS[langIndex]);
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
