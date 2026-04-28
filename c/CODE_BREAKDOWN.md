# Deadboy POS Terminal Simulator (CLI) - Code Breakdown

This document provides a detailed architectural and functional breakdown of the C-based command-line interface (CLI) application designed to bridge communication between a computer and a physical Point of Sale (POS) terminal.

## 1. Architecture Overview
The application relies strictly on the **Windows API** (`windows.h`, `setupapi.h`) for hardware interaction.

* **State Management:** It runs an infinite `while(1)` loop, maintaining global variables for the serial handle (`hComm`), connection state (`is_connected`), and the current COM port.
* **Logging:** Everything sent and received is appended to `communication.log` with a timestamp to assist with debugging.
* **Hardware:** It connects using standard serial parameters: `9600 Baud`, `8 Data Bits`, `1 Stop Bit`, `No Parity` (8N1).

## 2. Function-by-Function Breakdown

### Core Utilities & Data Extraction
These functions handle the UI, logging, and slicing the byte arrays.

* **`set_color()` / `reset_color()`:** Uses `SetConsoleTextAttribute` to change terminal text colors for easier reading (Red for errors, Green for success, Cyan for data).
* **`write_log()`:** A variadic function (accepts unlimited arguments like `printf`). It generates a timestamp and appends formatted strings directly to `communication.log`.
* **`get_input()` / `pause_console()`:** Wrappers for `fgets`. They safely read user input while preventing buffer overflows, stripping out trailing newline `\n` characters.
* **`extract_field()`:** The workhorse for parsing. It takes a start index and length, safely copies that exact chunk of the payload array into a new string buffer, and null-terminates it.
* **`format_money()`:** Converts raw payload strings (like `000000000150`) into human-readable currency (e.g., `1.50`).
* **`get_card_name()` / `show_card_codes()`:** A simple lookup dictionary mapping standard 2-digit POS card codes to their real-world brand names (VISA, MASTERCARD, etc.).

### Data Integrity
* **`calculate_check_digit()`:** Generates the 8-byte MAC (Message Authentication Code) dynamically required at the end of the packet.
  1. It takes the payload.
  2. If the payload length isn't a multiple of 8, it pads the end with `0xFF` bytes until it is.
  3. It performs a block-wise XOR operation across the entire padded string, compressing it down to exactly 8 bytes.

### Hardware Interfacing (Serial/COM)
* **`connect_serial(port_name)`:**
  Uses `CreateFileA` to open a handle to `\\.\COMx`. Configures the `DCB` (Device Control Block) for 9600 baud rate and applies `COMMTIMEOUTS` to prevent the app from freezing forever if the terminal stops responding.
* **`disconnect_serial()`:**
  Safely closes the `hComm` Windows handle and resets the connection state flags.
* **`auto_detect_and_connect()`:**
  A clever hardware scanner. Instead of making the user guess their COM port, it uses the Windows SetupAPI (`SetupDiGetClassDevs`) to query the registry for all local ports. It searches the "Friendly Names" for "Prolific" or "PL2303" (common USB-to-Serial chips). If it finds one, it extracts the `COM` number and connects automatically.

### Transaction Handling & Logging
* **`log_tx_hex_breakdown()` & `log_hex_breakdown()`:**
  Takes the raw byte arrays (either outgoing or incoming), skips the `STX` byte, and feeds the payload into `extract_field()`. It formats the data into a highly readable vertical list, logging the Command, Amount, Error Codes, and MAC chunks to the log file.
* **`print_receipt()`:**
  Triggers only on a successful transaction. Extracts all relevant transaction data (MID, TID, Auth Code, Amounts, Masked Card) and prints a formatted, customer-facing ASCII receipt to the screen.
* **`send_transaction(cmd, amt, inv, cshr, human_desc)`:**
  The core protocol engine.
  1. **Build:** Formats the 25-byte payload using `sprintf`, calculates the MAC, and stitches `STX`, Payload, MAC, and `ETX` together.
  2. **Send:** Uses `WriteFile` to push the packet over the COM port.
  3. **Wait:** Enters a `while` loop, using `ReadFile` to read exactly 1 byte at a time until it encounters the `ETX (0x03)` byte, signifying the terminal has finished talking.
  4. **Evaluate:** Looks at bytes 4 and 5 of the received buffer (the Error Code). If `00`, it triggers `print_receipt()`. If anything else (like `CT` or `51`), it prints a DECLINED warning.

### The Main Event Loop
* **`main()`:** Initializes variables (starting Amount, starting Invoice = 1, Cashier = 99). It runs the primary CLI loop.
  * Captures user input using a `switch/case` block.
  * Manages the `auto_inc` (Auto-increment) logic. If toggled ON, a successful Sale (Option 4) will automatically bump the internal invoice counter by 1 to prevent duplicate transaction rejections.

---

### Implementation Notes for Future Development
* **Endianness/Architecture:** Because it relies heavily on Windows APIs and data types (`HANDLE`, `DWORD`), this code is strictly for Windows environments. Porting to Linux/macOS would require swapping out `<windows.h>` for POSIX `<termios.h>` serial handling.
* **Buffer Sizes:** The `rx_buf` is set to 1024 bytes. Standard POS responses rarely exceed 250 bytes, providing ample safety margin for longer firmwares.
