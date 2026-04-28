# Card Type Codes

Bytes at offset 31-32 of the RX payload contain a 2-character card type code. This identifies the card scheme used for the transaction.

## Card type mapping

| Code | Card Brand | Notes |
|---|---|---|
| `04` | Visa | International |
| `05` | Mastercard | International |
| `06` | Diners Club | International |
| `07` | American Express (Amex) | International |
| `08` | MyDebit | Malaysia domestic debit scheme |
| `09` | JCB | Japan Credit Bureau, accepted internationally |
| `10` | UnionPay | China-based, growing international acceptance |
| `11` | E-Wallet | QR/NFC-based wallet payments (GrabPay, Touch 'n Go, Boost, etc.) |
| `00` | Unknown / Not identified | Returned when the terminal cannot identify the card scheme |

## Regional notes

- **Code `08` (MyDebit)** is specific to Malaysia's domestic debit network. Terminals in other countries may use this code for a different local debit scheme, or it may not be used at all.
- **Code `11` (E-Wallet)** is a GHL-specific catch-all for wallet-based payments. The specific wallet provider (GrabPay, Boost, TNG eWallet, etc.) is not differentiated at the protocol level.
- Card type codes are assigned by GHL in their terminal configuration. If you encounter a code not listed here, contact GHL support for your region's specific mapping.

## Card brand name field

In addition to the 2-character code, the RX payload also contains a 15-character card brand name field at offset 81-95. This is a human-readable ASCII string like `MYDEBIT        ` or `VISA           ` (space-padded to 15 characters). Use this field for display purposes and the numeric code for programmatic logic.

## Usage in code

```python
CARD_TYPES = {
    "04": "VISA",
    "05": "MASTERCARD",
    "06": "DINERS",
    "07": "AMEX",
    "08": "MYDEBIT",
    "09": "JCB",
    "10": "UNIONPAY",
    "11": "E-WALLET",
}

card_code = payload[31:33]  # e.g., "08"
card_name = CARD_TYPES.get(card_code, "UNKNOWN")
```
