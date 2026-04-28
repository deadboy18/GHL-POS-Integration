# Web Serial Implementation

Zero-install browser-based POS simulator using the Web Serial API.

## Usage

1. Open `index.html` in Chrome, Edge, or Opera.
2. Click "Connect" and select your COM port.
3. Enter amount and invoice number.
4. Click "Sale" and interact with the terminal.

## Requirements

- Chromium-based browser (Chrome 89+, Edge 89+, Opera 76+)
- Web Serial API is NOT supported in Firefox or Safari

## Features

- No installation, no compilation, no dependencies
- Full transaction support (Sale, Void, Settlement, Refund)
- Real-time hex log
- Receipt display on approval
- Auto-increment invoice numbers
- Card type code reference
