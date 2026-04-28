# Error and Response Codes

When the terminal responds to a transaction command, bytes at offset 3-4 of the payload contain a 2-character error/approval code. This table lists all known codes.

## Approval

| Code | Meaning | Action |
|---|---|---|
| `00` | Approved | Transaction successful. Print receipt and update your records. |

## Terminal-level errors

These errors originate from the terminal itself, before reaching the bank.

| Code | Meaning | Action |
|---|---|---|
| `CT` | Cancelled / Timeout | User cancelled on the terminal, or the terminal timed out waiting for a card. Not a connection issue. Retry if needed. |
| `CE` | Communication error | Terminal could not reach the bank network. Check terminal's internet connection. |
| `TO` | Terminal timeout | Terminal timed out waiting for a bank response. May indicate network issues. |
| `IC` | Invalid card | Card could not be read. Ask the cardholder to try again or use a different card. |
| `NR` | No response | No response from the bank host. Check terminal's network connectivity. |
| `SE` | System error | Internal terminal error. Restart the terminal and try again. |

## Bank/issuer decline codes

These codes come from the card-issuing bank. The terminal passes them through.

| Code | Meaning | Action |
|---|---|---|
| `01` | Refer to issuer | Cardholder should contact their bank. |
| `02` | Refer to issuer (special) | Cardholder should contact their bank. |
| `03` | Invalid merchant | Terminal/merchant configuration issue. Contact GHL support. |
| `04` | Pick up card | Card has been flagged. Do not return to cardholder. |
| `05` | Do not honor | Bank declined without specific reason. Try a different card. |
| `06` | Error | General bank error. Retry. |
| `07` | Pick up card (special) | Card flagged for fraud. Do not return to cardholder. |
| `12` | Invalid transaction | The transaction type is not supported for this card. |
| `13` | Invalid amount | Amount is outside allowed range. Check the amount and retry. |
| `14` | Invalid card number | Card number failed validation. Check the card or try another. |
| `19` | Re-enter transaction | Temporary bank error. Retry the transaction. |
| `25` | Unable to locate record | For void/refund: the original transaction was not found. Check the invoice number. |
| `30` | Format error | Packet format issue. Check your payload construction. |
| `31` | Bank not supported | The card's issuing bank is not configured on this terminal. |
| `33` | Expired card (pick up) | Card is expired. Do not return to cardholder. |
| `36` | Restricted card | Card has restrictions. Try a different card. |
| `38` | Allowable PIN tries exceeded | Too many wrong PIN attempts. Card is locked. |
| `41` | Lost card (pick up) | Card reported lost. Do not return to cardholder. |
| `43` | Stolen card (pick up) | Card reported stolen. Do not return to cardholder. |
| `51` | Insufficient funds | Not enough balance. Try a smaller amount or a different card. |
| `54` | Expired card | Card is past its expiry date. Use a different card. |
| `55` | Incorrect PIN | Cardholder entered the wrong PIN. Retry with correct PIN. |
| `58` | Transaction not permitted | This transaction type is not allowed on this terminal. |
| `61` | Exceeds withdrawal limit | Amount exceeds the card's daily limit. Try a smaller amount. |
| `62` | Restricted card | Card has restrictions that prevent this transaction. |
| `65` | Exceeds withdrawal frequency | Too many transactions in a short period. Wait and retry. |
| `75` | Allowable PIN tries exceeded | PIN locked due to too many attempts. |
| `76` | Invalid previous message | Protocol error. Check your implementation. |
| `77` | Inconsistent data | Data mismatch between POS and bank. Check your payload. |
| `78` | No account found | The card's account does not exist at the bank. |
| `89` | Terminal ID error | Terminal not properly registered. Contact GHL support. |
| `91` | Issuer unavailable | The cardholder's bank is offline. Retry later. |
| `94` | Duplicate transaction | This transaction was already processed. Check for double-sends. |
| `96` | System malfunction | Bank system error. Retry later. |

## Hardware errors (not returned in payload)

These errors appear on the terminal screen itself, not in the serial response payload.

### PED TAMPERED

**This is a critical, permanent hardware error. There is no software fix.**

If the PAX A920 terminal displays **"PED TAMPERED"** on its screen, the internal tamper detection sensor has been triggered. The terminal is permanently disabled and must be physically replaced with a new unit from GHL.

**What triggers it:**

- **Unscrewing the terminal casing.** Some screws on the A920 are deliberately connected to tamper detection circuits. Removing even one of these screws triggers an irreversible hardware lockout. There is no way to know which screws are booby-trapped without triggering them.
- **Dropping the terminal.** A hard enough impact can shift the internal tamper switches, triggering the same lockout.
- **Physical damage** to the casing that moves internal components out of alignment.

**What happens:**

- The terminal displays "PED TAMPERED" and refuses to boot into the payment application.
- All payment functionality is permanently disabled.
- The terminal cannot be reset, reflashed, re-keyed, or repaired.
- The secure encryption keys stored inside the terminal are automatically wiped.

**What to do:**

- Contact GHL support to arrange a hardware replacement.
- Do not attempt to open the terminal further -- this will not help and may void any remaining warranty.
- The replacement process typically involves swapping the physical terminal unit. Your merchant configuration (TID/MID) will be re-provisioned onto the new unit by GHL.

**How to avoid it:**

- Never open the terminal casing for any reason. There are no user-serviceable parts inside.
- Use a protective case or silicone sleeve if the terminal is in a high-risk environment (kitchens, outdoor stalls, etc.).
- Train staff to handle the terminal carefully -- treat it like a phone, not a tool.
- If you suspect internal hardware issues, contact GHL support directly rather than attempting self-repair.

### BATTERY / CHARGING ERRORS

If the A920 does not charge when placed on the L920-BE base:

- Check the pogo pins on the base -- they must make clean contact with the terminal's rear contacts.
- Clean the pogo pins with isopropyl alcohol if they appear dirty or oxidized.
- Ensure the L920-BE base is receiving power via its Micro-USB port.

## Connection troubleshooting (not error codes)

These are common issues during serial communication setup, not terminal error codes.

| Symptom | Cause | Fix |
|---|---|---|
| "Access Denied" / Error 5 | COM port in use by another program | Close PuTTY, TeraTerm, the Python simulator, or any other program using the same COM port |
| "Not Found" / Error 2 | Cable not detected by PC | Check USB cable is plugged in. Check Device Manager for the COM port. Try a different USB port. |
| No Prolific port in Device Manager | Wrong driver or FTDI cable | Install PL2303 driver. If you see "FTDI" in Device Manager, you have the wrong adapter -- FTDI does not work. |
| Port appears but no response | Wrong COM port or terminal not ready | Verify correct COM port number. Ensure terminal is powered on, docked on the base, and the pogo pins are making contact. |
| Timeout after TX (code CT) | Terminal waiting for card | This is normal -- tap/insert a card on the terminal. If no card is available, the terminal will timeout and return CT. |
| "Port already open" in browser | Previous session didn't disconnect cleanly | Click the "Reset port" button, or close and reopen the browser tab. |
| Web Serial popup is empty | No serial devices connected | Plug in the PL2303 adapter. Make sure the driver is installed. |

## Notes

- Not all terminals or banks support all codes. Some banks return `05` (Do not honor) as a catch-all for various decline reasons.
- The error code is always 2 ASCII characters at payload offset 3-4.
- If you receive an unrecognized code, log it and display a generic "Transaction declined" message to the user.
- GHL technical support can help decode bank-specific error codes if you encounter persistent issues.
