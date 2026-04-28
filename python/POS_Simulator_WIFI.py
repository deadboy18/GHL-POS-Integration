import tkinter as tk
from tkinter import ttk, scrolledtext, messagebox, filedialog
import serial
import serial.tools.list_ports
import threading
import time
import socket
from datetime import datetime
import json
import os

# --- THEME CONSTANTS ---
COL_BG_MAIN = "#F4F6F9"        
COL_CARD = "#FFFFFF"          
COL_TEXT = "#2C3E50"          
COL_HEADER_TXT = "#00ACC1"     

COL_SALE = "#43A047"          
COL_VOID = "#FB8C00"          
COL_SETTLE = "#1E88E5"        
COL_REFUND = "#D81B60"        
COL_CANCEL = "#E53935"        
COL_CONN = "#546E7A"          
COL_WIFI = "#673AB7"          
COL_RECEIPT_BG = "#FFF8E1"    

# Fonts
FONT_HEADER = ("Segoe UI", 16, "bold")
FONT_LABEL = ("Segoe UI", 9, "bold")
FONT_INPUT = ("Consolas", 12)
FONT_BTN = ("Segoe UI", 10, "bold")
FONT_LOG = ("Consolas", 9)
FONT_RECEIPT = ("Courier New", 10)

# Constants
STX = b'\x02'
ETX = b'\x03'
CONFIG_FILE = "simulator_config.json"

# --- HELPER: GET LOCAL IP ---
def get_lan_ip():
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    try:
        s.connect(('8.8.8.8', 80))
        IP = s.getsockname()[0]
    except Exception:
        IP = '127.0.0.1'
    finally:
        s.close()
    return IP

# --- HELPER: TOAST NOTIFICATION ---
class ToastNotification(tk.Toplevel):
    def __init__(self, parent, message, duration=3000, color="#333333"):
        super().__init__(parent)
        self.overrideredirect(True)
        self.attributes("-topmost", True)
        label = tk.Label(self, text=message, bg=color, fg="#FFFFFF", 
                         font=("Segoe UI", 11), padx=20, pady=10)
        label.pack()
        self.update_idletasks()
        x = parent.winfo_rootx() + (parent.winfo_width() // 2) - (self.winfo_width() // 2)
        y = parent.winfo_rooty() + parent.winfo_height() - 120
        self.geometry(f"+{x}+{y}")
        self.after(duration, self.destroy)

# --- HELPER: CARD LEGEND POPUP ---
class CardLegendPopup(tk.Toplevel):
    def __init__(self, parent):
        super().__init__(parent)
        self.title("Card Type Codes")
        self.geometry("250x300")
        self.configure(bg=COL_BG_MAIN)
        self.attributes("-topmost", True)
        x = parent.winfo_rootx() + parent.winfo_width() + 10
        y = parent.winfo_rooty()
        self.geometry(f"+{x}+{y}")
        lbl = tk.Label(self, text="CARD TYPE MAPPING", bg=COL_BG_MAIN, fg=COL_HEADER_TXT, font=("Segoe UI", 10, "bold"))
        lbl.pack(pady=10)
        content = """04 = VISA\n05 = MasterCard\n06 = Diners\n07 = Amex\n08 = MyDebit\n09 = JCB\n10 = UnionPay\n11 = eWallet"""
        txt = tk.Label(self, text=content, bg=COL_BG_MAIN, fg="#333", font=("Consolas", 11), justify="left")
        txt.pack(padx=20, pady=5)
        ttk.Button(self, text="CLOSE", command=self.destroy).pack(pady=10)

# --- HELPER: DIGITAL RECEIPT POPUP ---
class ReceiptPopup(tk.Toplevel):
    def __init__(self, parent, receipt_text):
        super().__init__(parent)
        self.title("Transaction Receipt")
        self.geometry("420x780") 
        self.configure(bg=COL_RECEIPT_BG)
        self.attributes("-topmost", True)
        x = parent.winfo_rootx() + (parent.winfo_width() // 2) - 210
        y = parent.winfo_rooty() + (parent.winfo_height() // 2) - 390
        self.geometry(f"+{x}+{y}")
        
        lbl_title = tk.Label(self, text="*** APPROVED ***", bg=COL_RECEIPT_BG, fg="black", font=("Courier New", 14, "bold"))
        lbl_title.pack(pady=(20, 10))

        self.receipt_content = receipt_text

        lbl_info = tk.Label(self, text=self.receipt_content, bg=COL_RECEIPT_BG, fg="black", font=FONT_RECEIPT, justify="left")
        lbl_info.pack(padx=20, pady=5)
        btn_frame = tk.Frame(self, bg=COL_RECEIPT_BG)
        btn_frame.pack(pady=20)
        ttk.Button(btn_frame, text="COPY TEXT", command=self.copy_text).pack(side="left", padx=5)
        ttk.Button(btn_frame, text="CLOSE", command=self.destroy).pack(side="left", padx=5)

    def copy_text(self):
        self.clipboard_clear()
        self.clipboard_append(self.receipt_content)
        messagebox.showinfo("Copied", "Receipt text has been copied to clipboard!")

# --- CUSTOM WIDGET: ATM INPUT ---
class CurrencyEntry(tk.Entry):
    def __init__(self, master=None, **kwargs):
        self.var = tk.StringVar()
        kwargs['textvariable'] = self.var
        super().__init__(master, **kwargs)
        self.raw_value = 0
        self.var.set("0.00")
        self.bind("<Key>", self.handle_keypress)
        self.bind("<BackSpace>", self.handle_backspace)

    def handle_keypress(self, event):
        if event.char.isdigit():
            if len(str(self.raw_value)) < 10: 
                self.raw_value = self.raw_value * 10 + int(event.char)
                self.update_display()
        return "break"

    def handle_backspace(self, event):
        self.raw_value = self.raw_value // 10
        self.update_display()
        return "break"

    def update_display(self):
        self.var.set("{:.2f}".format(self.raw_value / 100))

    def get_amount(self):
        return self.raw_value / 100.0

    def set_amount(self, float_val):
        self.raw_value = int(float_val * 100)
        self.update_display()

# --- BACKEND LOGIC ---
class GHLProtocol:
    def __init__(self):
        self.ser = None
        self.stop_flag = False
        self.is_busy = False # Lock to prevent collision between WiFi and GUI

    def connect(self, port):
        self.disconnect()
        time.sleep(0.1) 
        try:
            self.ser = serial.Serial(
                port=port, baudrate=9600, bytesize=8,
                parity='N', stopbits=1, timeout=1
            )
            return True, f"Connected to {port}"
        except Exception as e:
            return False, str(e)

    def disconnect(self):
        try:
            if self.ser and self.ser.is_open:
                self.ser.close()
        except: pass

    def cancel_wait(self):
        self.stop_flag = True

    def calculate_chk(self, data):
        d = bytearray(data)
        rem = len(d) % 8
        if rem: d += b'\xFF' * (8 - rem)
        chk = bytearray(8)
        for i in range(0, len(d), 8):
            chunk = d[i:i+8]
            for j in range(8): chk[j] ^= chunk[j]
        return bytes(chk)

    def build_packet(self, cmd, amt, inv, cshr):
        payload = f"{cmd}{int(amt*100):012d}{int(inv):06d}{str(cshr):>4}".encode('ascii')
        return STX + payload + self.calculate_chk(payload) + ETX

    def send_recv(self, packet, cb):
        if not self.ser or not self.ser.is_open:
            cb("Err: Disconnected", None)
            return
        
        if self.is_busy:
            cb("Err: Terminal Busy", None)
            return

        self.stop_flag = False
        self.is_busy = True # Lock
        
        def t():
            try:
                cb(f"TX > {packet.hex().upper()}", None)
                self.ser.write(packet)
                buff = bytearray()
                start = time.time()
                while True:
                    if self.stop_flag: 
                        cb("User Cancelled (Software Side)", None); break
                    if time.time() - start > 60: 
                        cb("Err: Timeout", None); break
                    
                    try:
                        b = self.ser.read(1)
                        if b:
                            buff.extend(b)
                            if b == ETX:
                                cb(f"RX < {buff.hex().upper()}", bytes(buff))
                                break
                    except Exception as e:
                        cb(f"Err: Serial Read Error {e}", None); break
            except Exception as e:
                cb(f"Err: {e}", None)
            finally:
                self.is_busy = False # Release Lock
        
        threading.Thread(target=t, daemon=True).start()

# --- GUI ---
class POSApp:
    CARD_TYPES = {
        "04": "VISA", "05": "MASTERCARD", "06": "DINERS", "07": "AMEX",
        "08": "MYDEBIT", "09": "JCB", "10": "UNIONPAY", "11": "E-WALLET"
    }

    def __init__(self, root):
        self.root = root
        self.root.title("GHL Simulator + WiFi Relay // KESH v1022")
        self.root.geometry("950x850") 
        self.root.configure(bg=COL_BG_MAIN)
        self.proto = GHLProtocol()
        
        # WiFi Server Variables
        self.server_socket = None
        self.server_running = False
        self.wifi_thread = None
        self.local_ip = get_lan_ip()
        
        self.setup_styles()
        self.build_layout()
        self.load_settings() 

        import atexit
        atexit.register(self.shutdown)

    def shutdown(self):
        self.stop_server()
        self.proto.disconnect()

    def setup_styles(self):
        s = ttk.Style()
        s.theme_use('clam')
        s.configure("Card.TFrame", background=COL_CARD, relief="flat", borderwidth=0)
        s.configure("Main.TFrame", background=COL_BG_MAIN)
        s.configure("Header.TLabel", background=COL_CARD, foreground=COL_HEADER_TXT, font=FONT_HEADER)
        s.configure("Std.TLabel", background=COL_CARD, foreground=COL_TEXT, font=FONT_LABEL)
        s.configure("TEntry", fieldbackground="#F0F0F0", foreground="black", borderwidth=1, relief="solid")
        s.configure("TCheckbutton", background=COL_CARD, foreground=COL_TEXT, font=("Segoe UI", 9))
        
        def cfg_btn(name, bg):
            s.configure(name, background=bg, foreground="white", font=FONT_BTN, borderwidth=0)
            s.map(name, background=[('active', '#90A4AE'), ('disabled', '#CFD8DC')])

        cfg_btn("Sale.TButton", COL_SALE)
        cfg_btn("Void.TButton", COL_VOID)
        cfg_btn("Settle.TButton", COL_SETTLE)
        cfg_btn("Refund.TButton", COL_REFUND)
        cfg_btn("Cancel.TButton", COL_CANCEL)
        cfg_btn("Conn.TButton", COL_CONN)
        cfg_btn("WiFi.TButton", COL_WIFI) 
        s.configure("Small.TButton", background="#ECEFF1", foreground="#455A64", font=("Segoe UI", 8))

    def build_layout(self):
        # --- HEADER ---
        header = ttk.Frame(self.root, style="Card.TFrame", padding=(20, 10))
        header.pack(fill="x", side="top")
        
        # Title Left
        title_frame = ttk.Frame(header, style="Card.TFrame")
        title_frame.pack(side="left")
        ttk.Label(title_frame, text="POS - Terminal Simulator", style="Header.TLabel").pack(anchor="w")
        ttk.Label(title_frame, text="INTEGRATED WIFI RELAY", background=COL_CARD, foreground=COL_WIFI, font=("Segoe UI", 8, "bold")).pack(anchor="w")

        # Serial Connection Right
        conn_box = ttk.Frame(header, style="Card.TFrame")
        conn_box.pack(side="right")
        self.cv_status = tk.Canvas(conn_box, width=15, height=15, bg=COL_CARD, highlightthickness=0)
        self.status_dot = self.cv_status.create_oval(2, 2, 13, 13, fill="#B0BEC5", outline="")
        self.cv_status.pack(side="left", padx=5)
        ports = [p.device for p in serial.tools.list_ports.comports()] or ["COM1"]
        self.port_var = tk.StringVar(value=ports[0])
        self.cb_port = ttk.Combobox(conn_box, textvariable=self.port_var, values=ports, width=10, state="readonly")
        self.cb_port.pack(side="left", padx=5)
        self.btn_conn = ttk.Button(conn_box, text="CONNECT", style="Conn.TButton", width=12, command=self.toggle_conn)
        self.btn_conn.pack(side="left")

        # --- WIFI CONTROL BAR ---
        wifi_bar = ttk.Frame(self.root, style="Main.TFrame", padding=(20, 5))
        wifi_bar.pack(fill="x")
        
        wf_card = ttk.Frame(wifi_bar, style="Card.TFrame", padding=10)
        wf_card.pack(fill="x")
        
        ttk.Label(wf_card, text="WiFi Server:", style="Std.TLabel").pack(side="left", padx=5)
        ttk.Label(wf_card, text=f"IP: {self.local_ip}", background="#E8EAF6", foreground="#3F51B5", padding=5).pack(side="left", padx=10)
        
        ttk.Label(wf_card, text="Port:", style="Std.TLabel").pack(side="left", padx=5)
        self.ent_wifi_port = ttk.Entry(wf_card, width=6, font=("Consolas", 10))
        self.ent_wifi_port.insert(0, "8888")
        self.ent_wifi_port.pack(side="left")
        
        self.btn_wifi = ttk.Button(wf_card, text="START SERVER", style="WiFi.TButton", command=self.toggle_server)
        self.btn_wifi.pack(side="left", padx=15)
        
        self.lbl_wifi_status = ttk.Label(wf_card, text="[Stopped]", style="Std.TLabel", foreground="gray")
        self.lbl_wifi_status.pack(side="left", padx=5)

        # --- BODY ---
        container = ttk.Frame(self.root, style="Main.TFrame", padding=20)
        container.pack(fill="both", expand=True)
        card = ttk.Frame(container, style="Card.TFrame", padding=25)
        card.pack(fill="both", expand=True)

        # 1. Inputs
        input_grid = ttk.Frame(card, style="Card.TFrame")
        input_grid.pack(fill="x", pady=(0, 15))
        input_grid.columnconfigure(1, weight=1); input_grid.columnconfigure(3, weight=1)

        ttk.Label(input_grid, text="AMOUNT (RM)", style="Std.TLabel").grid(row=0, column=0, sticky="e", padx=10)
        self.ent_amt = CurrencyEntry(input_grid, font=FONT_INPUT, width=15, justify="right")
        self.ent_amt.set_amount(0.01)
        self.ent_amt.grid(row=0, column=1, sticky="ew", padx=10)
        
        qf = ttk.Frame(input_grid, style="Card.TFrame")
        qf.grid(row=1, column=1, sticky="w", padx=10, pady=(2, 0))
        for amt in [0.01, 1, 5, 10, 50]:
            ttk.Button(qf, text=f"{amt}", style="Small.TButton", width=5,
                       command=lambda a=amt: self.ent_amt.set_amount(a)).pack(side="left", padx=1)

        ttk.Label(input_grid, text="INVOICE NO.", style="Std.TLabel").grid(row=0, column=2, sticky="e", padx=10)
        self.ent_inv = ttk.Entry(input_grid, font=FONT_INPUT, width=15, justify="right")
        self.ent_inv.insert(0, "000001")
        self.ent_inv.grid(row=0, column=3, sticky="ew", padx=10)

        self.var_autoincrement = tk.BooleanVar(value=True)
        ttk.Checkbutton(input_grid, text="Auto-Increment on Success", variable=self.var_autoincrement, 
                        style="TCheckbutton").grid(row=1, column=3, sticky="w", padx=10)

        ttk.Label(input_grid, text="CASHIER ID", style="Std.TLabel").grid(row=2, column=0, sticky="e", padx=10, pady=(15,0))
        self.ent_csh = ttk.Entry(input_grid, font=FONT_INPUT, width=15, justify="right")
        self.ent_csh.insert(0, "99")
        self.ent_csh.grid(row=2, column=1, sticky="ew", padx=10, pady=(15,0))

        ttk.Separator(card, orient="horizontal").pack(fill="x", pady=20)

        # 2. Buttons
        btn_grid = ttk.Frame(card, style="Card.TFrame")
        btn_grid.pack(fill="x", pady=5)
        btn_grid.columnconfigure((0,1), weight=1)

        ttk.Button(btn_grid, text="SALE", style="Sale.TButton", padding=15,
                   command=lambda: self.tx("020")).grid(row=0, column=0, padx=5, pady=5, sticky="ew")
        ttk.Button(btn_grid, text="VOID", style="Void.TButton", padding=15,
                   command=lambda: self.tx("022")).grid(row=0, column=1, padx=5, pady=5, sticky="ew")
        ttk.Button(btn_grid, text="SETTLEMENT", style="Settle.TButton", padding=15,
                   command=lambda: self.tx("050")).grid(row=1, column=0, padx=5, pady=5, sticky="ew")
        ttk.Button(btn_grid, text="REFUND", style="Refund.TButton", padding=15,
                   command=lambda: self.tx("026")).grid(row=1, column=1, padx=5, pady=5, sticky="ew")

        self.btn_cancel = ttk.Button(card, text="STOP WAITING (SOFTWARE RESET)", style="Cancel.TButton", 
                                     state="disabled", padding=10, command=self.stop_wait)
        self.btn_cancel.pack(fill="x", pady=(15, 10), padx=5)

        # 3. Logs
        log_lbl = ttk.Frame(card, style="Card.TFrame")
        log_lbl.pack(fill="x", pady=(10, 5))
        ttk.Label(log_lbl, text="COMMUNICATION LOG", style="Std.TLabel").pack(side="left", padx=5)
        
        tools = ttk.Frame(log_lbl, style="Card.TFrame")
        tools.pack(side="right")
        ttk.Button(tools, text="CODES", style="Small.TButton", width=8, command=self.show_legend).pack(side="left", padx=2)
        ttk.Button(tools, text="COPY", style="Small.TButton", width=8, command=self.copy_log).pack(side="left", padx=2)
        ttk.Button(tools, text="SAVE", style="Small.TButton", width=8, command=self.save_log).pack(side="left", padx=2)
        ttk.Button(tools, text="CLEAR", style="Small.TButton", width=8, command=self.clr_log).pack(side="left", padx=2)

        self.log_box = scrolledtext.ScrolledText(card, height=8, font=FONT_LOG, 
                                                 bg="#263238", fg="#ECEFF1", bd=0, state="disabled")
        self.log_box.pack(fill="both", expand=True, padx=5, pady=5)
        self.log_box.tag_config("tx", foreground="#4FC3F7")
        self.log_box.tag_config("rx", foreground="#69F0AE")
        self.log_box.tag_config("err", foreground="#FF8A80")
        self.log_box.tag_config("wifi", foreground="#B39DDB") 

    # --- WIFI SERVER LOGIC ---
    def toggle_server(self):
        if not self.server_running:
            try:
                port = int(self.ent_wifi_port.get())
                self.start_server(port)
            except ValueError:
                messagebox.showerror("Error", "Invalid Port Number")
        else:
            self.stop_server()

    def start_server(self, port):
        try:
            self.server_socket = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
            self.server_socket.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
            self.server_socket.bind(('0.0.0.0', port))
            self.server_socket.listen(5)
            
            self.server_running = True
            self.btn_wifi.config(text="STOP SERVER", style="Cancel.TButton")
            self.ent_wifi_port.config(state="disabled")
            self.lbl_wifi_status.config(text="[Running]", foreground=COL_WIFI)
            self.log(f"[WiFi] Server Started on {self.local_ip}:{port}", "wifi")
            
            self.wifi_thread = threading.Thread(target=self.wifi_listener, daemon=True)
            self.wifi_thread.start()
        except Exception as e:
            messagebox.showerror("Server Error", str(e))

    def stop_server(self):
        self.server_running = False
        try:
            if self.server_socket: self.server_socket.close()
        except: pass
        self.btn_wifi.config(text="START SERVER", style="WiFi.TButton")
        self.ent_wifi_port.config(state="normal")
        self.lbl_wifi_status.config(text="[Stopped]", foreground="gray")
        self.log("[WiFi] Server Stopped", "wifi")

    def wifi_listener(self):
        while self.server_running:
            try:
                client, addr = self.server_socket.accept()
                self.log(f"[WiFi] Connection from {addr[0]}", "wifi")
                self.handle_wifi_client(client)
            except OSError:
                break 
            except Exception as e:
                self.log(f"[WiFi Error] {e}", "err")

    def handle_wifi_client(self, client):
        """
        Modified in v1022 to loop continuously, keeping the Telnet session alive
        until the client disconnects or network fails.
        """
        try:
            while True:
                data = client.recv(1024)
                if not data: break # Client disconnected
                
                req = data.decode('utf-8', errors='ignore').strip()
                if not req: continue

                self.log(f"[WiFi] Request: {req}", "wifi")
                
                if self.proto.is_busy:
                    client.send(b"Error: Terminal Busy\n")
                    continue # Try again later, don't close connection

                resp_msg = "Unknown Command\n"
                
                if req.upper().startswith("SALE"):
                    # Parse Amount
                    parts = req.split(" ")
                    amt_str = parts[1] if len(parts) > 1 else "0.01"
                    try: amt_val = float(amt_str)
                    except: amt_val = 0.01
                    
                    self.root.after(0, lambda: self.ent_amt.set_amount(amt_val))
                    resp_msg = self.tx_blocking("020", override_amt=amt_val)
                    
                elif req.upper().startswith("VOID"):
                    resp_msg = self.tx_blocking("022")
                elif req.upper().startswith("SETTLE"):
                    resp_msg = self.tx_blocking("050")
                elif req.upper().startswith("REFUND"):
                    resp_msg = self.tx_blocking("026")
                
                # Send response + Newline for Telnet readability
                client.send((resp_msg + "\n").encode('utf-8'))
                
        except ConnectionResetError:
            self.log("[WiFi] Client Disconnected", "wifi")
        except Exception as e:
            self.log(f"[WiFi Client Error] {e}", "err")
        finally:
            client.close()

    # --- BLOCKING TX FOR WIFI ---
    def tx_blocking(self, cmd, override_amt=None):
        if not self.proto.ser or not self.proto.ser.is_open:
            return "Error: Port Not Connected"

        done_event = threading.Event()
        result_container = {"msg": "Error: Timeout"}

        def wifi_cb(msg, data):
            tag = "tx" if "TX" in msg else ("err" if "Err" in msg else "rx")
            self.root.after(0, lambda: self.log(msg, tag))
            
            if data and len(data) > 10:
                hex_resp = data.hex().upper()
                try:
                    payload = data[1:-9]
                    err = payload[3:5].decode(errors='ignore')
                    if err == "00":
                        # v1022 Feature: Send FULL TEXT RECEIPT to Phone
                        receipt_text = self.parse_packet_to_text(data)
                        result_container["msg"] = receipt_text
                        
                        # Trigger GUI Popup too
                        self.root.after(0, lambda: self.show_receipt(receipt_text))
                    else:
                        result_container["msg"] = f"DECLINED ({err}): {hex_resp}"
                except:
                    result_container["msg"] = f"RX: {hex_resp}"
                done_event.set()
            elif "Err" in msg or "User Cancelled" in msg:
                 result_container["msg"] = msg
                 done_event.set()

        try:
            if override_amt is not None:
                amt = override_amt
            else:
                amt = 0.0 if cmd in ["050", "022"] else self.ent_amt.get_amount()
            
            inv = 0 if cmd in ["020", "050"] else int(self.ent_inv.get())
            cshr = self.ent_csh.get()
            
            pkt = self.proto.build_packet(cmd, amt, inv, cshr)
            self.proto.send_recv(pkt, wifi_cb)
            
            done_event.wait(timeout=65)
            return result_container["msg"]
            
        except Exception as e:
            return f"Error: {e}"

    # --- PARSING LOGIC EXTRACTED (v1022) ---
    def parse_packet_to_text(self, data):
        try:
            payload = data[1:-9] 
            p_len = len(payload)
            
            def get_val(start, length, is_numeric=False):
                if p_len < start + length: return "N/A"
                raw = payload[start:start+length].decode(errors='ignore')
                if is_numeric: return raw.strip() 
                return raw.strip()

            def get_money(start, length):
                try:
                    val_str = get_val(start, length, True)
                    if "N/A" in val_str: return "0.00"
                    val = int(val_str)
                    return "{:.2f}".format(val / 100)
                except: return "0.00"
            
            def format_card(raw):
                if raw == "N/A" or len(raw) < 2: return raw
                try:
                    c_len = int(raw[:2])
                    return raw[2:2+c_len] 
                except: return raw

            raw_type_code = "11"
            if p_len >= 33: raw_type_code = payload[31:33].decode(errors='ignore')
            card_name_str = self.CARD_TYPES.get(raw_type_code, "UNKNOWN")
            display_card_type = f"{raw_type_code} ({card_name_str})"
            
            raw_exp = get_val(27, 4)
            readable_exp = "INVALID"
            try:
                dt = datetime.strptime(raw_exp, "%y%m")
                readable_exp = dt.strftime("%Y %B")
            except: readable_exp = "Unknown Format"

            # Construct Receipt Text
            txt = f"""
MERCHANT ID:  {get_val(104, 15)}
TERMINAL ID:  {get_val(96, 8)}
TIME:         {datetime.now().strftime("%Y-%m-%d %H:%M:%S")}
BATCH NO:     {get_val(119, 6, True)}
------------------------------
STAN:         {get_val(65, 6)}
INVOICE:      {get_val(71, 6)}
------------------------------
TRANS TYPE:   SALE
CASHIER ID:   {get_val(77, 4)}

CARD NO:      {format_card(get_val(5, 22).replace('X', '*'))}
EXPIRY:       {readable_exp}
CARD TYPE:    {display_card_type}
AUTH CODE:    {get_val(33, 8)}
------------------------------
GROSS AMT:    RM {get_money(41, 12)}
NET AMT:      RM {get_money(53, 12)}
------------------------------
      THANK YOU!
"""
            return txt
        except Exception as e:
            return f"Receipt Parse Error: {e}"

    # --- STANDARD GUI ACTIONS ---
    def save_settings(self):
        data = {
            "port": self.port_var.get(),
            "invoice": self.ent_inv.get(),
            "cashier": self.ent_csh.get(),
            "auto_inc": self.var_autoincrement.get(),
            "wifi_port": self.ent_wifi_port.get() 
        }
        try:
            with open(CONFIG_FILE, "w") as f: json.dump(data, f)
        except: pass

    def load_settings(self):
        if os.path.exists(CONFIG_FILE):
            try:
                with open(CONFIG_FILE, "r") as f: data = json.load(f)
                if "port" in data: self.port_var.set(data["port"])
                if "invoice" in data: 
                    self.ent_inv.delete(0, tk.END)
                    self.ent_inv.insert(0, data["invoice"])
                if "cashier" in data:
                    self.ent_csh.delete(0, tk.END)
                    self.ent_csh.insert(0, data["cashier"])
                if "auto_inc" in data:
                    self.var_autoincrement.set(data["auto_inc"])
                if "wifi_port" in data:
                    self.ent_wifi_port.delete(0, tk.END)
                    self.ent_wifi_port.insert(0, data["wifi_port"])
            except: pass

    def show_toast(self, message, color="#333333"):
        ToastNotification(self.root, message, color=color)

    def show_legend(self):
        CardLegendPopup(self.root)

    def show_receipt(self, receipt_text_or_data):
        # Handle both raw data (old calls) and pre-formatted text (new calls)
        if isinstance(receipt_text_or_data, bytes):
            # It's raw bytes, parse it first
            txt = self.parse_packet_to_text(receipt_text_or_data)
        else:
            # It's already text
            txt = receipt_text_or_data
            
        ReceiptPopup(self.root, txt)

    def log(self, msg, tag=None):
        ts = datetime.now().strftime("[%H:%M:%S] ")
        self.log_box.config(state="normal")
        self.log_box.insert("end", ts + msg + "\n", tag)
        self.log_box.see("end")
        self.log_box.config(state="disabled")

    def copy_log(self):
        self.root.clipboard_clear()
        self.root.clipboard_append(self.log_box.get("1.0", tk.END))
        self.show_toast("Log copied!")

    def save_log(self):
        data = self.log_box.get("1.0", tk.END)
        f = filedialog.asksaveasfilename(defaultextension=".txt", filetypes=[("Text Files", "*.txt")])
        if f:
            with open(f, "w") as file: file.write(data)
            self.show_toast(f"Saved to {f}")

    def clr_log(self):
        self.log_box.config(state="normal")
        self.log_box.delete("1.0", "end")
        self.log_box.config(state="disabled")

    def toggle_conn(self):
        if "CONNECT" in self.btn_conn['text']:
            ok, msg = self.proto.connect(self.port_var.get())
            if ok:
                self.log(f"[{msg}]")
                self.btn_conn.config(text="DISCONNECT", style="Cancel.TButton")
                self.cb_port.config(state="disabled")
                self.cv_status.itemconfig(self.status_dot, fill="#00C853")
                self.save_settings() 
            else:
                self.log(f"Fail: {msg}", "err")
                messagebox.showerror("Error", msg)
        else:
            self.proto.disconnect()
            self.log("[Disconnected]")
            self.btn_conn.config(text="CONNECT", style="Conn.TButton")
            self.cb_port.config(state="readonly")
            self.cv_status.itemconfig(self.status_dot, fill="#B0BEC5")

    def stop_wait(self):
        self.proto.cancel_wait()
        self.log(">>> STOP SIGNAL SENT <<<", "err")

    def tx(self, cmd):
        if not self.proto.ser or not self.proto.ser.is_open:
            messagebox.showwarning("Error", "Connect port first.")
            return
        
        if self.proto.is_busy:
            messagebox.showwarning("Busy", "Transaction already in progress (WiFi?)")
            return

        try:
            amt = 0.0 if cmd in ["050", "022"] else self.ent_amt.get_amount()
            inv = 0 if cmd in ["020", "050"] else int(self.ent_inv.get())
            cshr = self.ent_csh.get()
            
            pkt = self.proto.build_packet(cmd, amt, inv, cshr)
            self.btn_cancel.config(state="normal")
            
            if cmd == "020": self.show_toast("Please SWIPE/INSERT CARD on Terminal", COL_SALE)
            elif cmd == "026": self.show_toast("Refund Mode Active", COL_REFUND)
            elif cmd == "050": self.show_toast("Settlement In Progress...", COL_SETTLE)
            
            self.save_settings() 
            self.proto.send_recv(pkt, self.on_resp)
        except ValueError:
            messagebox.showerror("Error", "Check inputs.")

    def on_resp(self, msg, data):
        def update():
            self.btn_cancel.config(state="disabled")
            tag = "tx" if "TX" in msg else ("err" if "Err" in msg else "rx")
            self.log(msg, tag)
            
            if data and len(data) > 10:
                try:
                    payload = data[1:-9]
                    err = payload[3:5].decode(errors='ignore')
                    
                    if err == "00":
                        self.show_toast("TRANSACTION APPROVED", COL_SALE)
                        self.show_receipt(data)
                        if self.var_autoincrement.get():
                            curr = int(self.ent_inv.get())
                            self.ent_inv.delete(0, tk.END)
                            self.ent_inv.insert(0, f"{curr + 1:06d}")
                    else:
                        self.show_toast(f"DECLINED: {err}", COL_CANCEL)
                except: pass
        self.root.after(0, update)

if __name__ == "__main__":
    root = tk.Tk()
    app = POSApp(root)
    root.mainloop()