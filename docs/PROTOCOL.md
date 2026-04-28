# ECR Protocol Reference

This document is a complete reference for the GHL ECR (Electronic Cash Register) serial protocol used to communicate between a POS system and a GHL-configured PAX payment terminal.

Source: GHL POS Integration Specification v1.0.17

---

## Protocol basics

Communication happens over an RS232 serial connection at 9600 baud, 8 data bits, no parity, 1 stop bit, no flow control (8N1). The POS system sends a command packet (TX), and the terminal responds with a response packet (RX) after the cardholder completes the transaction.

The protocol is half-duplex and synchronous: you send one packet, then wait for one response. There is no handshake, no ACK/NAK, and no retransmission. If the terminal does not respond within ~60 seconds, assume a timeout.

---

## Packet structure

Every packet (TX and RX) follows this structure:

```
[STX] [Payload] [Check Digit] [ETX]
  1B     N B        8 B         1B
```

- **STX** (Start of Text): Always `0x02` (1 byte)
- **Payload**: Variable length ASCII data
- **Check Digit**: 8-byte XOR checksum of the payload
- **ETX** (End of Text): Always `0x03` (1 byte)

Total packet size = 1 + payload length + 8 + 1 = payload length + 10 bytes.

---

## Check digit calculation (XOR checksum)

The check digit is calculated by XOR-ing the payload in 8-byte blocks:

1. Take the ASCII payload bytes.
2. If the payload length is not a multiple of 8, pad the end with `0xFF` bytes until it is.
3. XOR all 8-byte blocks together. The result is the 8-byte check digit.

### Example

Payload (25 bytes): `020000000000100000001  99`

As hex bytes:
```
30 32 30 30 30 30 30 30 30 30 30 31 30 30 30 30 30 30 30 31 20 20 39 39
```

Pad to 32 bytes (next multiple of 8) with 0xFF:
```
30 32 30 30 30 30 30 30   (block 1)
30 30 30 31 30 30 30 30   (block 2)
30 30 30 31 20 20 39 39   (block 3)
FF FF FF FF FF FF FF FF   (block 4 - padding)
```

XOR all blocks:
```
Result = Block1 XOR Block2 XOR Block3 XOR Block4
       = 8-byte check digit
```

### Pseudocode

```
function calculate_check_digit(payload):
    data = copy of payload bytes
    
    # Pad with 0xFF to multiple of 8
    remainder = length(data) mod 8
    if remainder != 0:
        append (8 - remainder) bytes of 0xFF to data
    
    # XOR all 8-byte blocks
    checksum = [0, 0, 0, 0, 0, 0, 0, 0]
    for i = 0 to length(data) step 8:
        for j = 0 to 7:
            checksum[j] = checksum[j] XOR data[i + j]
    
    return checksum
```

---

## TX packet format (POS to terminal)

The TX payload is always **25 bytes** of ASCII data:

| Offset | Length | Field | Format | Description |
|---|---|---|---|---|
| 0 | 3 | Command | ASCII digits | Transaction type (see command codes below) |
| 3 | 12 | Amount | ASCII digits, zero-padded | Amount in cents (e.g., `000000000100` = 1.00) |
| 15 | 6 | Invoice | ASCII digits, zero-padded | Invoice/trace number (e.g., `000001`) |
| 21 | 4 | Cashier | ASCII, right-justified | Cashier ID, space-padded (e.g., `  99`) |

Total TX packet: STX(1) + Payload(25) + Checksum(8) + ETX(1) = **35 bytes**.

### Command codes (TX)

| Code | Transaction |
|---|---|
| `020` | Sale |
| `022` | Void |
| `026` | Refund |
| `050` | Settlement (end-of-day batch close) |

### Notes on specific commands

- **Sale (`020`)**: Amount is the transaction amount. Invoice should be unique per transaction. The terminal will prompt the cardholder to tap/insert/swipe their card.
- **Void (`022`)**: Amount should be `000000000000` (zero). Invoice must match the original transaction's invoice number. The terminal will look up and reverse the original transaction.
- **Refund (`026`)**: Amount is the refund amount. Invoice can be zero.
- **Settlement (`050`)**: Amount and invoice should both be zero. This closes the current batch and settles all transactions with the bank.

---

## RX packet format (terminal to POS)

The RX payload varies in length depending on firmware version:

- **Older firmware (pre-v1.0.17)**: 96 bytes (no TID/MID/Batch)
- **Current firmware (v1.0.17+)**: 125+ bytes

| Offset | Length | Field | Notes |
|---|---|---|---|
| 0 | 3 | Response code | `021`=Sale, `023`=Void, `027`=Refund, `051`=Settlement |
| 3 | 2 | Error/approval code | `00`=approved, see error code table |
| 5 | 22 | Card number | Masked, includes 2-byte length prefix |
| 27 | 4 | Card expiry | Format: YYMM (e.g., `3202` = Feb 2032) |
| 31 | 2 | Card type code | See card code table |
| 33 | 8 | Authorization code | Bank-issued approval code |
| 41 | 12 | Gross amount | In cents, same format as TX |
| 53 | 12 | Net amount | After fees/adjustments |
| 65 | 6 | Trace number (STAN) | System Trace Audit Number |
| 71 | 6 | Invoice number | Should match what you sent (for Sale) |
| 77 | 4 | Cashier ID | Should match what you sent |
| 81 | 15 | Card brand name | ASCII, space-padded (e.g., `MYDEBIT        `) |
| 96 | 8 | Terminal ID (TID) | Firmware v1.0.17+ only |
| 104 | 15 | Merchant ID (MID) | Firmware v1.0.17+ only |
| 119 | 6 | Batch number | Firmware v1.0.17+ only |

### Response codes (RX)

| TX Command | RX Response |
|---|---|
| `020` (Sale) | `021` |
| `022` (Void) | `023` |
| `026` (Refund) | `027` |
| `050` (Settlement) | `051` |

### Processing the response

1. Strip STX (first byte) and ETX (last byte).
2. The last 8 bytes before ETX are the check digit -- you can optionally verify it.
3. The remaining bytes are the payload.
4. Check bytes at offset 3-4 (error code). If `00`, the transaction was approved.
5. Extract the remaining fields using the offset table above.
6. If the payload is shorter than 125 bytes, TID/MID/Batch fields are not available (older firmware).

---

## Currency handling

The amount field is **always in the smallest currency unit** (cents, sen, satang, etc.). It is **12 digits, zero-padded, with no decimal point**.

| Actual Amount | Amount Field |
|---|---|
| RM 0.01 | `000000000001` |
| RM 1.00 | `000000000100` |
| RM 10.50 | `000000001050` |
| RM 999.99 | `000000099999` |
| RM 1,234.56 | `000000123456` |

The protocol itself is **currency-agnostic**. The terminal and acquiring bank determine the currency based on the terminal's configuration. The amount field is just an integer representing the smallest unit.

---

## Timing

- The terminal typically responds within 5-30 seconds for card-present transactions (depends on card type, network speed, and whether the cardholder needs to enter a PIN).
- Set a read timeout of at least 60 seconds to handle slow bank responses.
- For Settlement, the terminal may take 30-120 seconds depending on the batch size.
- There is no keep-alive or heartbeat mechanism.

---

## Reading the serial response

The recommended approach for reading the RX packet:

1. After sending the TX packet, enter a read loop.
2. Read one byte at a time.
3. Append each byte to a buffer.
4. When you read `0x03` (ETX), stop -- you have the complete response.
5. If no ETX is received within the timeout period, report a timeout error.

Do NOT try to read a fixed number of bytes, because the response length varies by firmware version and transaction type.
