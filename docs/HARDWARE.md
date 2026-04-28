# Hardware Setup and Cable Wiring Guide

This guide covers everything you need to physically connect a PAX A920 terminal to a PC for serial communication.

---

## Required hardware

1. **PAX A920 payment terminal** -- the handheld Android-based terminal that processes card payments.
2. **PAX L920-BE base station** -- the docking station that provides power, internet, and serial connectivity to the A920.
3. **Prolific PL2303 USB-to-Serial adapter** -- converts USB (on your PC) to RS232 serial (for the cable).
   - **CRITICAL: Must be Prolific chipset (PL2303 or PL2303GT).** FTDI chipsets are confirmed incompatible -- they were tested and do not work with this setup.
4. **Custom RS232 cable** -- you build this yourself (see wiring section below).
5. **Crimping tool + RJ45 connectors** -- for making the cable.
6. **DB9 female solder connector** -- for the serial end of the cable.
7. **Cat5e or Cat6 network cable** -- the raw cable you cut and rewire.

---

## Hardware chain

The full connection path from PC to terminal:

```
PC (USB port)
    |
    v
Prolific PL2303 USB-to-Serial Adapter
    |
    v
DB9 Female Connector (your custom cable)
    |
    v  (custom 3-wire cable)
    |
RJ45 Connector (your custom cable)
    |
    v
L920-BE Base Station (RS232 port)
    |
    v  (pogo pins / Bluetooth / WiFi)
    |
PAX A920 Terminal
```

---

## Building the custom cable

The L920-BE base station has an RJ45 port for serial communication, but it is NOT standard Ethernet. You need a custom cable that connects specific RJ45 pins to specific DB9 pins.

**Only 3 wires are needed.** The other 5 wires in the Cat5e cable are not used.

### Wiring table (confirmed working)

| RJ45 Pin | Wire Color (T568B) | DB9 Pin | Signal | Function |
|---|---|---|---|---|
| Pin 5 | White/Blue | Pin 3 | TXD | Data transmission |
| Pin 6 | Green | Pin 4 | DTR | Handshake signal |
| Pin 7 | White/Brown | Pin 1 | DCD | Carrier detect |

All other pins on both connectors are left unconnected.

### Step-by-step build instructions

**Step 1: Cut the cable**

Take a Cat5e or Cat6 network cable and cut it to your desired length (1-2 meters is typical). Strip about 3-4 cm of the outer jacket on both ends to expose the 8 colored wires inside.

**Step 2: Identify the three wires**

Using the T568B color standard, identify:
- **White/Blue** (white wire with blue stripe) -- this is pin 5
- **Green** (solid green wire) -- this is pin 6
- **White/Brown** (white wire with brown stripe) -- this is pin 7

You can cut the other 5 wires short since they are not used. However, on the RJ45 end, all 8 wires must still be inserted into the RJ45 plug for a proper crimp.

**Step 3: Crimp the RJ45 end**

On one end of the cable, crimp a standard T568B RJ45 plug. All 8 wires go into the plug in the standard T568B order (the same way you would make a normal network cable):

```
Pin 1: White/Orange
Pin 2: Orange
Pin 3: White/Green
Pin 4: Blue
Pin 5: White/Blue      <-- ACTIVE
Pin 6: Green           <-- ACTIVE
Pin 7: White/Brown     <-- ACTIVE
Pin 8: Brown
```

Clip facing away from you, pin 1 is on the left.

**Step 4: Solder the DB9 end**

On the other end of the cable, solder only the three active wires to a DB9 female connector:

- **White/Blue** wire to **DB9 Pin 3** (TXD)
- **Green** wire to **DB9 Pin 4** (DTR)
- **White/Brown** wire to **DB9 Pin 1** (DCD)

Leave all other DB9 pins empty. Do not connect ground or any other signal.

**Step 5: Test continuity**

Using a multimeter in continuity mode, verify:
- RJ45 Pin 5 has continuity with DB9 Pin 3
- RJ45 Pin 6 has continuity with DB9 Pin 4
- RJ45 Pin 7 has continuity with DB9 Pin 1
- No other pins have continuity with each other (shorts)

---

## L920-BE base station setup

1. **Power**: Connect power via the Micro-USB port on the back of the base.
2. **Internet**: Connect an Ethernet cable to the Network (LAN) port on the base. The base creates its own local WiFi hotspot.
3. **Serial**: Plug your custom cable's RJ45 end into the RS232 port on the base.
4. **Terminal**: Place the A920 terminal onto the L920-BE base. Ensure the pogo pins on the base make contact with the terminal (the terminal should start charging).

### WiFi configuration (important)

The L920-BE base broadcasts a WiFi SSID for the A920 terminal to connect to. The PAX A920 will **NOT** detect or connect to open (no password) WiFi networks. You **must** set a WiFi password on the base station.

The A920 connects to the base via Bluetooth or the base's WiFi -- this is separate from the RS232 serial connection to your PC.

---

## Serial port settings

Configure your COM port with these exact settings:

| Setting | Value |
|---|---|
| Baud Rate | 9600 |
| Data Bits | 8 |
| Parity | None |
| Stop Bits | 1 |
| Flow Control | None |

To set this on Windows: **Device Manager > Ports (COM & LPT) > [your COM port] > Properties > Port Settings**.

---

## USB driver installation

### Prolific PL2303

The Prolific PL2303 USB-to-Serial adapter requires a driver. On Windows 10/11, the driver usually installs automatically when you plug in the adapter. If it does not, download the driver from the Prolific website.

After installation, the adapter should appear in Device Manager under **Ports (COM & LPT)** as something like "Prolific USB-to-Serial Comm Port (COM3)".

### PAX USB driver (optional)

If you are connecting the A920 directly via USB (for ADB debugging or app deployment, not for ECR communication), you need the PAX USB driver. This is separate from the serial adapter driver and is not needed for ECR serial communication.

---

## Wiring diagram

A detailed SVG wiring diagram is included in this repository at `docs/diagrams/PAX_L920BE_Wiring_Diagram.svg`. It shows both connectors, all pin positions, the three active wires with their colors, and step-by-step build instructions.
