# C Implementation

Pure C implementation using only Windows API. Zero external dependencies.

## Compile

```bash
gcc ghl_simulator.c -o GHL_simulator.exe -lsetupapi
```

## Features

- Auto-detects Prolific USB-to-Serial adapters via Windows registry
- Sale, Void, Settlement, Refund transactions
- Full hex TX/RX logging with field-by-field breakdown
- ASCII receipt on approval
- Auto-increment invoice numbers
- Timestamped communication.log file

## Architecture

See [CODE_BREAKDOWN.md](CODE_BREAKDOWN.md) for a function-by-function walkthrough.
