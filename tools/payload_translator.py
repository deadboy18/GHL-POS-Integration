import tkinter as tk
from tkinter import ttk, scrolledtext, messagebox
import json
from datetime import datetime

# --- THEME CONSTANTS (MATCHING SIMULATOR) ---
COL_BG_MAIN = "#F4F6F9"
COL_CARD = "#FFFFFF"
COL_TEXT = "#2C3E50"
COL_HEADER_TXT = "#00ACC1"

COL_BTN_BG = "#546E7A"
COL_JSON_KEY = "#E91E63"
COL_JSON_STR = "#2E7D32"
COL_JSON_NUM = "#1565C0"
COL_BG_LOG = "#263238"
COL_FG_LOG = "#ECEFF1"

# Fonts
FONT_HEADER = ("Segoe UI", 14, "bold")
FONT_LABEL = ("Segoe UI", 9, "bold")
FONT_INPUT = ("Consolas", 11)
FONT_BTN = ("Segoe UI", 10, "bold")

class GHLParser:
    CARD_TYPES = {
        "04": "VISA",
        "05": "MASTERCARD",
        "06": "DINERS",
        "07": "AMEX",
        "08": "MYDEBIT",
        "09": "JCB",
        "10": "UNIONPAY",
        "11": "E-WALLET"
    }

    @staticmethod
    def parse_hex_string(raw_input):
        # 1. Cleanup Input (Remove timestamps, brackets, "TX >", "RX <")
        # Try to find the start of the hex string (starts with 02, ends with 03 usually)
        clean_hex = "".join(c for c in raw_input if c in "0123456789ABCDEFabcdef")
        
        # Locate STX (02)
        stx_index = clean_hex.find("02")
        if stx_index == -1:
            return {"error": "No STX (02) marker found in input."}
            
        # Locate ETX (03) - search from end
        etx_index = clean_hex.rfind("03")
        if etx_index == -1:
            return {"error": "No ETX (03) marker found in input."}

        if etx_index < stx_index:
             return {"error": "Malformed packet: ETX before STX."}

        # Extract the full packet hex
        full_packet_hex = clean_hex[stx_index : etx_index + 2]
        
        try:
            # Convert to bytes
            packet_bytes = bytes.fromhex(full_packet_hex)
        except Exception as e:
            return {"error": f"Hex Conversion Error: {str(e)}"}

        # Breakdown
        # Packet Structure: STX (1) + Payload (N) + LRC (8) + ETX (1)
        if len(packet_bytes) < 11:
            return {"error": "Packet too short to be valid."}

        stx = packet_bytes[0:1]
        payload = packet_bytes[1:-9] # Strip STX, LRC, ETX
        lrc = packet_bytes[-9:-1]
        etx = packet_bytes[-1:]
        
        try:
            cmd = payload[0:3].decode('ascii')
        except:
            cmd = "???"

        result = {
            "meta": {
                "raw_hex": full_packet_hex,
                "total_bytes": len(packet_bytes),
                "payload_bytes": len(payload),
                "valid_structure": True
            },
            "protocol": {
                "stx": "0x02",
                "command": cmd,
                "lrc_check_digit": lrc.hex().upper(),
                "etx": "0x03"
            },
            "decoded": {}
        }

        # Determine TX or RX based on Command
        # TX: 020 (Sale), 022 (Void), 050 (Settle), 026 (Refund)
        # RX: 021 (Sale), 023 (Void), 051 (Settle), 027 (Refund)
        
        if cmd in ["020", "022", "050", "026"]:
            result["type"] = "REQUEST (POS -> TERMINAL)"
            result["decoded"] = GHLParser.parse_request(payload)
        elif cmd in ["021", "023", "051", "027"]:
            result["type"] = "RESPONSE (TERMINAL -> POS)"
            result["decoded"] = GHLParser.parse_response(payload)
        else:
            result["type"] = "UNKNOWN / CUSTOM"
            result["decoded"] = {"payload_ascii": payload.decode('ascii', errors='replace')}

        return result

    @staticmethod
    def parse_request(payload):
        # Format: Cmd(3) + Amt(12) + Inv(6) + Cshr(4)
        def get_val(start, length):
            if len(payload) < start + length: return "N/A"
            return payload[start:start+length].decode('ascii', errors='ignore').strip()

        amount_str = get_val(3, 12)
        try:
            amt_fmt = "{:.2f}".format(int(amount_str)/100)
        except: amt_fmt = "0.00"

        return {
            "command": get_val(0, 3),
            "amount": amt_fmt,
            "invoice_no": get_val(15, 6),
            "cashier_id": get_val(21, 4)
        }

    @staticmethod
    def parse_response(payload):
        # Format based on previous Simulator Code
        p_len = len(payload)
        
        def get_val(start, length):
            if p_len < start + length: return "N/A"
            return payload[start:start+length].decode('ascii', errors='ignore').strip()

        # Money Helper
        def get_money(start, length):
            try:
                val = int(get_val(start, length))
                return "{:.2f}".format(val / 100)
            except: return "0.00"

        # Card Type Helper
        raw_type = "11"
        if p_len >= 33:
            raw_type = payload[31:33].decode('ascii', errors='ignore')
        card_desc = GHLParser.CARD_TYPES.get(raw_type, "UNKNOWN")

        # Expiry Helper
        raw_exp = get_val(27, 4)
        try:
            dt = datetime.strptime(raw_exp, "%y%m")
            exp_fmt = dt.strftime("%Y %B")
        except:
            exp_fmt = "Invalid"

        data = {
            "command": get_val(0, 3),
            "error_code": get_val(3, 2),
            "card_number": get_val(5, 22),
            "expiry_raw": raw_exp,
            "expiry_fmt": exp_fmt,
            "card_type_code": raw_type,
            "card_type_desc": card_desc,
            "auth_code": get_val(33, 8),
            "gross_amount": get_money(41, 12),
            "net_amount": get_money(53, 12),
            
            # Mapped according to "Bank Simulator" reality
            "stan_trace": get_val(65, 6),     # Byte 65
            "invoice_trace": get_val(71, 6),  # Byte 71
            
            "cashier_id": get_val(77, 4),
            "card_label": get_val(81, 15),
            
            # Firmware Version Check
            "firmware_status": "OLD (< v1.0.17)" if p_len < 125 else "NEW (v1.0.17+)"
        }

        if p_len >= 125:
            data["terminal_id"] = get_val(96, 8)
            data["merchant_id"] = get_val(104, 15)
            data["batch_number"] = get_val(119, 6)
        
        return data

class TranslatorApp:
    def __init__(self, root):
        self.root = root
        self.root.title("GHL Protocol Translator")
        self.root.geometry("700x800")
        self.root.configure(bg=COL_BG_MAIN)
        
        self.setup_styles()
        self.build_ui()

    def setup_styles(self):
        s = ttk.Style()
        s.theme_use('clam')
        s.configure("Main.TFrame", background=COL_BG_MAIN)
        s.configure("Header.TLabel", background=COL_BG_MAIN, foreground=COL_HEADER_TXT, font=FONT_HEADER)
        s.configure("Sub.TLabel", background=COL_BG_MAIN, foreground=COL_TEXT, font=FONT_LABEL)
        s.configure("Action.TButton", background=COL_BTN_BG, foreground="white", font=FONT_BTN, borderwidth=0)
        s.map("Action.TButton", background=[('active', '#78909C')])

    def build_ui(self):
        # Header
        header = ttk.Frame(self.root, style="Main.TFrame", padding=20)
        header.pack(fill="x")
        ttk.Label(header, text="HEX LOG TRANSLATOR", style="Header.TLabel").pack(side="left")
        ttk.Label(header, text="GHL PROTOCOL", style="Sub.TLabel").pack(side="right", pady=5)

        # Input Area
        input_frame = ttk.Frame(self.root, style="Main.TFrame", padding=(20, 0))
        input_frame.pack(fill="x")
        ttk.Label(input_frame, text="PASTE RAW LOG LINE (TX or RX):", style="Sub.TLabel").pack(anchor="w")
        
        self.txt_input = scrolledtext.ScrolledText(input_frame, height=4, font=FONT_INPUT, 
                                                   bg=COL_BG_LOG, fg=COL_FG_LOG, insertbackground="white")
        self.txt_input.pack(fill="x", pady=5)
        
        # Helper text
        ttk.Label(input_frame, text="Example: [11:50] RX < 023032...", font=("Segoe UI", 8), foreground="#78909C", background=COL_BG_MAIN).pack(anchor="w")

        # Action Button
        btn_frame = ttk.Frame(self.root, style="Main.TFrame", padding=(20, 15))
        btn_frame.pack(fill="x")
        ttk.Button(btn_frame, text="TRANSLATE PAYLOAD", style="Action.TButton", command=self.do_translate).pack(fill="x", ipady=5)

        # Output Area
        output_frame = ttk.Frame(self.root, style="Main.TFrame", padding=20)
        output_frame.pack(fill="both", expand=True)
        ttk.Label(output_frame, text="DECODED JSON OUTPUT:", style="Sub.TLabel").pack(anchor="w")
        
        self.txt_output = scrolledtext.ScrolledText(output_frame, font=FONT_INPUT, 
                                                    bg=COL_CARD, fg=COL_TEXT)
        self.txt_output.pack(fill="both", expand=True, pady=5)
        
        # Tag configuration for syntax highlighting
        self.txt_output.tag_config("key", foreground=COL_JSON_KEY, font=("Consolas", 11, "bold"))
        self.txt_output.tag_config("str", foreground=COL_JSON_STR)
        self.txt_output.tag_config("num", foreground=COL_JSON_NUM)
        self.txt_output.tag_config("err", foreground="red", font=("Consolas", 11, "bold"))

    def do_translate(self):
        raw_text = self.txt_input.get("1.0", tk.END).strip()
        if not raw_text:
            return

        result = GHLParser.parse_hex_string(raw_text)
        
        self.txt_output.delete("1.0", tk.END)
        self.pretty_print_json(result)

    def pretty_print_json(self, data, indent=0):
        # Custom JSON printer to apply tkinter tags
        space = " " * indent
        
        for k, v in data.items():
            # Print Key
            self.txt_output.insert(tk.END, f"{space}{k}: ", "key")
            
            if isinstance(v, dict):
                self.txt_output.insert(tk.END, "\n")
                self.pretty_print_json(v, indent + 4)
            else:
                # Print Value
                tag = "str"
                val_str = str(v)
                if isinstance(v, (int, float)) or (isinstance(v, str) and v.replace('.', '', 1).isdigit()):
                    tag = "num"
                if "error" in k:
                    tag = "err"
                
                self.txt_output.insert(tk.END, f"{val_str}\n", tag)

if __name__ == "__main__":
    root = tk.Tk()
    app = TranslatorApp(root)
    root.mainloop()