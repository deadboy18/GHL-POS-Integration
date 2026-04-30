# GHL POS Android

Native Android app for GHL ECR terminal integration via USB serial (Prolific PL2303).

## How to Build (Step by Step)

### Prerequisites
- [Android Studio](https://developer.android.com/studio) (latest version)
- A USB cable to connect your phone to your PC

### Steps

1. **Extract this folder** somewhere on your PC (e.g. `C:\Projects\GHL-POS-Android`)

2. **IMPORTANT: Run `clean_before_build.bat`** first!
   - Double-click `clean_before_build.bat` in the project folder
   - This removes Windows `desktop.ini` files that break Android builds

3. **Open Android Studio**
   - Click **"Open"** (not "New Project")
   - Navigate to the extracted `GHL-POS-Android` folder
   - Click **OK**

4. **Wait for Gradle Sync**
   - A progress bar will appear at the bottom
   - If it asks to upgrade AGP — click **"Don't remind me again"** or **"Cancel"**
   - Wait until the bottom bar says "Gradle sync finished"

5. **Build the APK**
   - Menu: **Build > Build Bundle(s) / APK(s) > Build APK(s)**
   - Wait for it to finish (1-2 minutes first time)
   - A popup appears: click **"locate"** to find the APK file

6. **Install on your phone**
   - Transfer the APK to your phone (USB cable, WhatsApp, Telegram, Google Drive, etc.)
   - Open the APK on your phone and tap **Install**
   - You may need to enable "Install from unknown sources" in Settings

### If You Get Errors

**"desktop.ini" error:**
- Run `clean_before_build.bat` and rebuild

**"AGP upgrade" popup:**
- Click "Don't remind me again" — the project works fine as-is

**"SDK not found":**
- Android Studio will auto-download the required SDK. Just wait.

## Features
- Native USB OTG serial (Prolific PL2303, CH340, CP210x, FTDI)
- Sale, Void, Settlement, Refund
- ATM-style amount entry
- Color-coded hex communication log
- Digital receipt with copy/share
- Auto-increment invoice numbers
- Demo mode for testing without terminal
- Dark/Light theme toggle
- Haptic feedback
- Auto-launch when USB adapter plugged in
- ~15-20MB RAM, <2MB APK

## Hardware Setup
Phone (USB-C) → OTG adapter → Prolific PL2303 USB-to-Serial → Custom RJ45 cable → GHL L920 terminal
