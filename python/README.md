# Python Implementation

GUI application built with tkinter. Two variants included:

- `POS_Simulator.py` - Standard RS232 serial version
- `POS_Simulator_WIFI.py` - Adds TCP/WiFi support for network-connected terminals

## Install

```bash
pip install pyserial
```

## Run

```bash
python POS_Simulator.py
```

## Features

- COM port dropdown with auto-detection
- ATM-style amount entry
- Sale, Void, Settlement, Refund buttons
- Real-time hex log with color coding
- Digital receipt popup on approval
- Auto-increment invoice numbers
- Settings persistence (JSON config)
- Card type legend popup
- Copy/save/clear log functions
