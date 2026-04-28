# GHL POS Terminal Integration Toolkit

A complete, open-source toolkit for integrating with **GHL payment terminals** (PAX A920 / L920) via RS232 serial communication. Includes working implementations in **C**, **Python**, **C#**, and **browser-based HTML** (Web Serial API), plus full protocol documentation, hardware wiring guides, and debugging tools.

Built and tested in production with GHL Malaysia (UOB acquiring). The ECR protocol used here is standard across all GHL-configured PAX terminals regardless of region or acquiring bank.

---

## What is GHL?

**GHL (Global Hardcore Leisure)** is one of the largest payment terminal providers in Southeast Asia. They supply and manage POS terminals for banks and merchants across Malaysia, Thailand, Philippines, Indonesia, and other ASEAN markets. If you're integrating a POS system with a card payment terminal in this region, you're very likely dealing with a GHL terminal.

This toolkit handles the **ECR (Electronic Cash Register) protocol** -- the serial communication standard that lets your POS software talk to the physical terminal to trigger card payments, voids, refunds, and settlements.

**Important:** While this was built and tested with GHL terminals in Malaysia, the ECR protocol and packet structure is the same across all GHL-configured PAX terminals. The card type codes may vary by region (e.g., MyDebit is Malaysia-specific), but the framing, checksums, and command structure are universal.

---

## What's in this repo

```
GHL-POS-Integration/
|
|-- c/                      Pure C implementation (Windows, for embedding in agents)
|-- python/                 Python GUI simulator (tkinter, for testing/prototyping)
|-- csharp/                 C# / .NET implementation (Visual Studio project)
|-- web/                    Browser-based tool using Web Serial API (zero install)
|-- tools/                  Hex payload translator / decoder
|
|-- docs/
|   |-- PROTOCOL.md         Complete ECR protocol reference
|   |-- HARDWARE.md          Cable wiring, hardware setup, serial settings
|   |-- ERROR_CODES.md       Full response/error code table
|   |-- CARD_CODES.md        Card type code mapping
|   |-- SAMPLE_TRANSACTION.md  Step-by-step transaction walkthrough with hex
|   |-- diagrams/            Wiring diagrams (SVG)
|   |-- datasheets/          L920 datasheet, A920 guide (add your own PDFs)
|
|-- examples/
|   |-- sample_communication.log   Real TX/RX captures from live terminal
|
|-- README.md               You are here
```

---

## Quick start

### 1. Get the hardware

You need a PAX A920 terminal on an L920-BE base station, connected to your PC via a custom RS232 cable. See [docs/HARDWARE.md](docs/HARDWARE.md) for the complete wiring guide, or the short version:

- **USB-to-Serial adapter**: Must be **Prolific PL2303** chipset. FTDI does not work.
- **Custom cable**: Cat5e/Cat6 with RJ45 on one end, DB9 female on the other. Only 3 wires are used.
- **Wiring**: RJ45 pin 5 (White/Blue) to DB9 pin 3 (TXD), RJ45 pin 6 (Green) to DB9 pin 4 (DTR), RJ45 pin 7 (White/Brown) to DB9 pin 1 (DCD).

### 2. Pick your implementation

| Implementation | Best for | Requirements |
|---|---|---|
| **C** (`c/`) | Embedding into native agents (e.g., Sentec PMS) | Windows, GCC/MinGW |
| **Python** (`python/`) | Rapid testing, GUI-based prototyping | Python 3, pyserial |
| **C#** (`csharp/`) | .NET desktop POS applications | Visual Studio, .NET |
| **Web** (`web/`) | Zero-install browser testing tool | Chrome/Edge (Web Serial API) |
| **Tools** (`tools/`) | Debugging hex payloads from logs | Python 3 |

### 3. Configure serial port

All implementations use the same settings:

| Setting | Value |
|---|---|
| Baud Rate | 9600 |
| Data Bits | 8 |
| Parity | None |
| Stop Bits | 1 |
| Flow Control | None |

Set this in **Device Manager > Ports (COM & LPT) > your COM port > Properties > Port Settings**.

### 4. Run a test transaction

1. Power on the A920 terminal and dock it on the L920-BE base.
2. Connect the RS232 cable to your PC's USB adapter.
3. Run any of the implementations (see individual README files in each folder).
4. Send a Sale command for RM 0.01 (or your currency's minimum).
5. Tap/insert a card on the terminal when prompted.
6. Check the response: error code `00` = approved.

---

## Protocol overview

Every message between POS and terminal follows this structure:

```
[STX (0x02)] [Payload] [8-byte XOR Checksum] [ETX (0x03)]
```

### Sending a transaction (TX)

The payload is always **25 bytes**:

| Offset | Length | Field | Example |
|---|---|---|---|
| 0-2 | 3 | Command code | `020` = Sale |
| 3-14 | 12 | Amount in cents | `000000000100` = 1.00 |
| 15-20 | 6 | Invoice number | `000001` |
| 21-24 | 4 | Cashier ID | `  99` (right-padded) |

### Receiving a response (RX)

The response payload is **96-125+ bytes** depending on terminal firmware:

| Offset | Length | Field |
|---|---|---|
| 0-2 | 3 | Response code (e.g., `021` = Sale response) |
| 3-4 | 2 | Error/approval code (`00` = success) |
| 5-26 | 22 | Masked card number |
| 27-30 | 4 | Card expiry (YYMM) |
| 31-32 | 2 | Card type code |
| 33-40 | 8 | Bank authorization code |
| 41-52 | 12 | Gross amount |
| 53-64 | 12 | Net amount |
| 65-70 | 6 | Trace number (STAN) |
| 71-76 | 6 | Invoice number |
| 77-80 | 4 | Cashier ID |
| 81-95 | 15 | Card brand name |
| 96-103 | 8 | Terminal ID (firmware v1.0.17+) |
| 104-118 | 15 | Merchant ID (firmware v1.0.17+) |
| 119-124 | 6 | Batch number (firmware v1.0.17+) |

For the complete protocol specification including checksum calculation, see [docs/PROTOCOL.md](docs/PROTOCOL.md).

### Command codes

| TX Code | RX Code | Transaction Type |
|---|---|---|
| `020` | `021` | Sale |
| `022` | `023` | Void |
| `026` | `027` | Refund |
| `050` | `051` | Settlement |

### Common error codes

| Code | Meaning |
|---|---|
| `00` | Approved / Success |
| `CT` | Cancelled by user or timeout on terminal |
| `51` | Insufficient funds |
| `54` | Expired card |
| `55` | Incorrect PIN |
| `91` | Issuer/bank unavailable |

Full list: [docs/ERROR_CODES.md](docs/ERROR_CODES.md)

### Card type codes

| Code | Card Brand |
|---|---|
| `04` | Visa |
| `05` | Mastercard |
| `06` | Diners Club |
| `07` | American Express |
| `08` | MyDebit (Malaysia) |
| `09` | JCB |
| `10` | UnionPay |
| `11` | E-Wallet |

Full list with regional notes: [docs/CARD_CODES.md](docs/CARD_CODES.md)

---

## Sample transaction walkthrough

Here is a complete Sale transaction for **RM 1.00** with invoice number **000001** and cashier ID **99**:

### Building the TX packet

**Payload (25 bytes ASCII):**
```
020000000000100000001  99
```

Broken down:
- `020` = Sale command
- `000000000100` = 100 cents = RM 1.00
- `000001` = Invoice number
- `  99` = Cashier ID (right-justified, space-padded)

**Checksum calculation (8-byte XOR):**

Split the payload into 8-byte blocks, pad the last block with `0xFF`:
```
Block 1: 30 32 30 30 30 30 30 30   ("02000000")
Block 2: 30 30 30 31 30 30 30 30   ("00010000")
Block 3: 30 30 30 31 20 20 39 39   ("0001  99")
Block 4: FF FF FF FF FF FF FF FF   (padding)
```

XOR all blocks together to get the 8-byte checksum.

**Final packet:**
```
[02] [payload 25 bytes] [checksum 8 bytes] [03]
= 35 bytes total
```

**Raw hex (what goes on the wire):**
```
02 30 32 30 30 30 30 30 30 30 30 30 31 30 30 30
30 30 30 30 31 20 20 39 39 [8 checksum bytes] 03
```

### Terminal response (RX)

After the cardholder taps/inserts their card and the bank approves:

```
02 30 32 31 30 30 ... [payload] ... [checksum] 03
```

Key fields extracted:
- Bytes 3-4: `30 30` = ASCII "00" = **Approved**
- Bytes 5-26: Masked card number
- Bytes 33-40: Authorization code from the bank
- Bytes 41-52: Gross amount (should match what you sent)

For a complete hex breakdown with a real captured transaction, see [docs/SAMPLE_TRANSACTION.md](docs/SAMPLE_TRANSACTION.md) and [examples/sample_communication.log](examples/sample_communication.log).

---

## Implementation details

### C (`c/`)

Pure C, zero dependencies beyond Windows API. Single source file. Auto-detects Prolific USB-to-Serial adapters via Windows registry. Compiles with one command:

```bash
gcc c/ghl_simulator.c -o ghl_simulator.exe -lsetupapi
```

Best for: embedding into native Windows agents (e.g., Sentec PMS integration) where you cannot ship a Python runtime or .NET framework.

### Python (`python/`)

Full GUI application built with tkinter. Features:
- COM port dropdown with auto-detection
- ATM-style amount entry (type digits, decimal auto-placed)
- Sale, Void, Settlement, Refund buttons
- Real-time hex log with color coding (TX=cyan, RX=green, errors=red)
- Digital receipt popup on approval
- Auto-increment invoice numbers
- Settings persistence (JSON config file)
- WiFi/TCP variant for terminals with network connectivity

Requires: `pip install pyserial`

### C# (`csharp/`)

Visual Studio solution with clean separation between protocol logic (`GHLProtocol.cs`) and UI (`Program.cs`). Uses `System.IO.Ports.SerialPort`. Publishable as a single-file .exe.

### Web (`web/`)

**Zero-install browser tool.** Open `index.html` in Chrome or Edge, select your COM port, and run transactions. Uses the Web Serial API -- no drivers, no installs, no compiling. Works on any machine with a Chromium browser and a USB serial cable.

Limitations: Web Serial API is only supported in Chromium-based browsers (Chrome, Edge, Opera). Not available in Firefox or Safari.

### Tools (`tools/`)

**Payload Translator**: Paste any raw hex log line (TX or RX) and get a structured JSON breakdown of every field. Essential for debugging failed transactions. Run with `python tools/payload_translator.py`.

---

## Troubleshooting

**Terminal not responding:**
- Check the cable: is the RJ45 fully seated in the L920 base? Are the pogo pins making contact with the A920?
- Check Device Manager: is your COM port showing? Is the Prolific driver installed?
- Check serial settings: must be 9600/8N1/no flow control.
- Try auto-detect (C version) or manually select the COM port.

**Error code CT (Cancel/Timeout):**
- The user cancelled on the terminal, or the terminal timed out waiting for a card.
- This is not a cable/connection issue -- the terminal received your command and responded.

**Error code 51 (Insufficient Funds):**
- The bank declined the transaction. Try a smaller amount or a different card.

**FTDI adapter not working:**
- FTDI chipsets are confirmed incompatible with this setup. You must use a Prolific PL2303 or PL2303GT adapter.
- Check the adapter label or Device Manager: it should show "Prolific" in the device name.

**WiFi not connecting on A920:**
- The L920 base broadcasts a WiFi SSID. The A920 will NOT connect to open (no password) networks. You must set a WiFi password on the base.

**Payload shorter than expected (no TID/MID/Batch):**
- Older terminal firmware (pre-v1.0.17) sends a shorter response that omits Terminal ID, Merchant ID, and Batch Number. The code handles this gracefully by showing "N/A" for those fields.

---

## Contributing

Pull requests welcome. If you're integrating with GHL terminals in a different country or with a different acquiring bank and find regional differences in card codes or error codes, please submit a PR to update the documentation.

---

## License

MIT License. See [LICENSE](LICENSE) for details.

---

**Developed by [Deadboy](https://github.com/deadboy18)** | Based on GHL POS Integration Spec v1.0.17
