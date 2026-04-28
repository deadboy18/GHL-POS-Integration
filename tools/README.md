# Debugging Tools

## Payload Translator

Paste any raw hex log line (TX or RX) and get a structured JSON breakdown.

```bash
python payload_translator.py
```

Accepts input like:
```
[11:50] RX < 02 30 32 31 30 30 ...
```

Or raw hex:
```
023032313030...
```

Outputs decoded JSON with all fields labeled.
