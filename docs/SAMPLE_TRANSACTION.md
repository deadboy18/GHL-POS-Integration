# Sample Transaction Walkthrough

This document walks through a complete Sale transaction with real captured hex data from a live GHL terminal.

---

## Scenario

- **Transaction type**: Sale
- **Amount**: RM 0.01 (1 cent -- minimum test amount)
- **Invoice number**: 000001
- **Cashier ID**: 99
- **Card used**: MyDebit card
- **Result**: Approved

---

## Step 1: Build the TX payload

### Fields

| Field | Value | ASCII | Hex bytes |
|---|---|---|---|
| Command (3) | Sale | `020` | `30 32 30` |
| Amount (12) | RM 0.01 = 1 cent | `000000000001` | `30 30 30 30 30 30 30 30 30 30 30 31` |
| Invoice (6) | 1 | `000001` | `30 30 30 30 30 31` |
| Cashier (4) | 99, right-justified | `  99` | `20 20 39 39` |

### Complete payload (25 bytes)

ASCII: `020000000000001000001  99`

Hex: `30 32 30 30 30 30 30 30 30 30 30 30 30 30 31 30 30 30 30 30 31 20 20 39 39`

### Checksum calculation

Split into 8-byte blocks:
```
Block 1: 30 32 30 30 30 30 30 30
Block 2: 30 30 30 30 30 31 30 30
Block 3: 30 30 30 31 20 20 39 39
Block 4: FF FF FF FF FF FF FF FF  (padding -- only 1 byte was needed but pad to 8)
```

XOR result: `09 CD CF CF CE DF DE C6`

### Final TX packet (35 bytes)

```
02 30 32 30 30 30 30 30 30 30 30 30 30 30 30 31
30 30 30 30 30 31 20 20 39 39 09 CD CF CF CE DF
DE C6 03
```

Breakdown:
```
02                                          = STX
30 32 30                                    = "020" (Sale command)
30 30 30 30 30 30 30 30 30 30 30 31         = "000000000001" (RM 0.01)
30 30 30 30 30 31                           = "000001" (Invoice)
20 20 39 39                                 = "  99" (Cashier)
09 CD CF CF CE DF DE C6                     = Checksum (8 bytes)
03                                          = ETX
```

---

## Step 2: Terminal interaction

After the TX packet is sent over the serial port:

1. The terminal displays "Please present card" (or similar).
2. The cardholder taps their card on the NFC reader (or inserts/swipes).
3. If the card requires a PIN, the terminal prompts for PIN entry.
4. The terminal communicates with the bank over its network connection.
5. The bank approves or declines.
6. The terminal sends the RX packet back over the serial port.

This entire process typically takes 5-15 seconds for a contactless tap.

---

## Step 3: Parse the RX response

### Raw RX packet (106 bytes)

```
02 30 32 31 30 30 34 30 30 30 31 32 33 34 58 58
58 58 58 58 39 38 37 36 30 30 30 30 33 32 30 32
30 38 31 34 32 32 30 33 20 20 30 30 30 30 30 30
30 30 30 30 30 31 30 30 30 30 30 30 30 30 30 30
30 30 30 30 30 30 35 33 30 30 30 30 33 30 20 20
39 39 20 20 20 20 20 20 20 20 4D 59 44 45 42 49
54 92 F0 EA 91 9B E5 EB FF 03
```

### Field-by-field breakdown

Strip STX (first byte) and Checksum+ETX (last 9 bytes) to get the payload:

```
Payload (96 bytes):
30 32 31 30 30 34 30 30 30 31 32 33 34 58 58 58
58 58 58 39 38 37 36 30 30 30 30 33 32 30 32 30
38 31 34 32 32 30 33 20 20 30 30 30 30 30 30 30
30 30 30 30 31 30 30 30 30 30 30 30 30 30 30 30
30 30 30 30 30 35 33 30 30 30 30 33 30 20 20 39
39 20 20 20 20 20 20 20 20 4D 59 44 45 42 49 54
```

| Offset | Hex | ASCII | Field | Interpretation |
|---|---|---|---|---|
| 0-2 | `30 32 31` | `021` | Response code | Sale response |
| 3-4 | `30 30` | `00` | Error code | **APPROVED** |
| 5-26 | `34 30...` | `40001234XXXXXX9876...` | Card number | First 2 bytes = digit count, then masked PAN, then zero-padding |
| 27-30 | `33 32 30 32` | `3202` | Card expiry | February 2032 |
| 31-32 | `30 38` | `08` | Card type | **MyDebit** |
| 33-40 | `31 34 32 32 30 33 20 20` | `142203  ` | Auth code | Bank authorization code |
| 41-52 | `30 30...30 31` | `000000000001` | Gross amount | RM 0.01 |
| 53-64 | `30 30...30 30` | `000000000000` | Net amount | RM 0.00 |
| 65-70 | `30 30 30 30 35 33` | `000053` | Trace (STAN) | System trace number |
| 71-76 | `30 30 30 30 33 30` | `000030` | Invoice | Invoice number |
| 77-80 | `20 20 39 39` | `  99` | Cashier | Cashier ID |
| 81-95 | `20...4D 59 44 45 42 49 54` | `       MYDEBIT` | Card name | Card brand string |

Note: This response is 96 bytes (older firmware), so TID, MID, and Batch Number fields are not present. With firmware v1.0.17+, the response would be 125+ bytes and include those additional fields.

---

## Step 4: What a declined transaction looks like

Here is a second transaction that was **cancelled** by the user on the terminal:

### RX response (declined)

Error code at offset 3-4: `43 54` = ASCII `CT` = **Cancelled/Timeout**

```
Offset 3-4: CT (Cancelled)
Card number: 0000000000000000000000 (all zeros -- no card was read)
Card expiry: 0000
Card type: 00 (unknown)
Auth code: (empty)
```

When the error code is anything other than `00`, the card fields will typically be empty or zeroed out, since no card transaction actually occurred.

---

## Receipt example

On a successful transaction, your POS should display/print a receipt like:

```
=========================================
            *** APPROVED ***
=========================================
MERCHANT ID:  [from response or config]
TERMINAL ID:  [from response or config]
TIME:         2026-03-19 11:52:15
BATCH NO:     [from response]
-----------------------------------------
STAN:         000053
INVOICE:      000030
-----------------------------------------
TRANS TYPE:   SALE
CASHIER ID:   99

CARD NO:      **** **** **** 9876 (16 digits)
EXPIRY:       02/32
CARD TYPE:    08 (MYDEBIT)
AUTH CODE:    142203
-----------------------------------------
GROSS AMT:    RM 0.01
NET AMT:      RM 0.00
-----------------------------------------
               THANK YOU!
=========================================
```
