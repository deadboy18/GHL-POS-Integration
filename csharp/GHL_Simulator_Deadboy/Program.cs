using System;
using System.Collections.Generic;
using System.Drawing;
using System.IO.Ports;
using System.Text;
using System.Threading;
using System.Threading.Tasks;
using System.Windows.Forms;

namespace GHL_Simulator_Simple_v12
{
    static class Program
    {
        [STAThread]
        static void Main()
        {
            Application.EnableVisualStyles();
            Application.SetCompatibleTextRenderingDefault(false);
            Application.Run(new MainForm());
        }
    }

    // --- PROTOCOL LOGIC (Unchanged) ---
    public class GHLProtocol
    {
        private SerialPort _serialPort;
        private bool _cancelFlag = false;
        private const byte STX = 0x02;
        private const byte ETX = 0x03;

        public bool IsConnected => _serialPort != null && _serialPort.IsOpen;

        public string Connect(string portName)
        {
            try
            {
                Disconnect();
                Thread.Sleep(200);
                _serialPort = new SerialPort(portName, 9600, Parity.None, 8, StopBits.One);
                _serialPort.ReadTimeout = 500;
                _serialPort.Open();
                return "Success";
            }
            catch (Exception ex) { return ex.Message; }
        }

        public void Disconnect()
        {
            try { if (_serialPort != null && _serialPort.IsOpen) { _serialPort.Close(); _serialPort.Dispose(); _serialPort = null; } } catch { }
        }

        public void CancelWait() => _cancelFlag = true;

        private byte[] CalculateCheckDigit(byte[] data)
        {
            List<byte> workingData = new List<byte>(data);
            int remainder = workingData.Count % 8;
            if (remainder != 0) { for (int i = 0; i < (8 - remainder); i++) workingData.Add(0xFF); }

            byte[] checkDigit = new byte[8];
            for (int i = 0; i < workingData.Count; i += 8)
                for (int j = 0; j < 8; j++) checkDigit[j] ^= workingData[i + j];
            return checkDigit;
        }

        public byte[] BuildPacket(string cmd, double amount, int invoice, string cashier)
        {
            string amtStr = ((long)(amount * 100)).ToString("D12");
            string invStr = invoice.ToString("D6");
            string cashStr = cashier.PadLeft(4, ' ');
            if (cashStr.Length > 4) cashStr = cashStr.Substring(0, 4);

            string payload = $"{cmd}{amtStr}{invStr}{cashStr}";
            byte[] payloadBytes = Encoding.ASCII.GetBytes(payload);
            byte[] checkDigit = CalculateCheckDigit(payloadBytes);

            List<byte> packet = new List<byte> { STX };
            packet.AddRange(payloadBytes);
            packet.AddRange(checkDigit);
            packet.Add(ETX);
            return packet.ToArray();
        }

        public async Task SendAndReceive(byte[] packet, Action<string, byte[]> logCallback)
        {
            _cancelFlag = false;
            await Task.Run(() =>
            {
                try
                {
                    if (!IsConnected) { logCallback("Error: Not Connected", null); return; }
                    logCallback($"TX > {BitConverter.ToString(packet).Replace("-", "")}", null);
                    _serialPort.Write(packet, 0, packet.Length);

                    List<byte> buffer = new List<byte>();
                    DateTime start = DateTime.Now;

                    while ((DateTime.Now - start).TotalSeconds < 60)
                    {
                        if (_cancelFlag) { logCallback("Cancelled by User", null); return; }
                        try
                        {
                            int byteRead = _serialPort.ReadByte();
                            if (byteRead != -1)
                            {
                                buffer.Add((byte)byteRead);
                                if (byteRead == ETX)
                                {
                                    logCallback($"RX < {BitConverter.ToString(buffer.ToArray()).Replace("-", "")}", buffer.ToArray());
                                    return;
                                }
                            }
                        }
                        catch (TimeoutException) { }
                    }
                    logCallback("Error: Timeout", null);
                }
                catch (Exception ex) { logCallback($"Error: {ex.Message}", null); }
            });
        }
    }

    // --- GUI ---
    public class MainForm : Form
    {
        GHLProtocol proto = new GHLProtocol();

        // UI Controls
        ComboBox cbPorts;
        Button btnConnect;
        TextBox txtAmount, txtInvoice, txtCashier;
        CheckBox chkAutoInc;
        RichTextBox rtbLog;
        Button btnCancel;

        // ATM Style Logic Variable
        private long _amountCents = 0; // Stores 1234 for 12.34

        public MainForm()
        {
            // 1. FORM SETUP
            this.Text = "GHL_Simulator_Deadboy";
            this.Size = new Size(600, 650);
            this.StartPosition = FormStartPosition.CenterScreen;
            this.FormBorderStyle = FormBorderStyle.FixedSingle;
            this.MaximizeBox = false;
            this.FormClosing += (s, e) => proto.Disconnect();

            int startX = 20;
            int startY = 20;
            int gap = 40;

            // 2. CONNECTION ROW
            Label lblPort = new Label() { Text = "COM Port:", Location = new Point(startX, startY), AutoSize = true };
            cbPorts = new ComboBox() { Location = new Point(startX + 80, startY - 3), Width = 100, DropDownStyle = ComboBoxStyle.DropDownList };
            RefreshPorts();

            btnConnect = new Button() { Text = "Connect", Location = new Point(startX + 200, startY - 5), Width = 100, Height = 25 };
            btnConnect.Click += BtnConnect_Click;

            this.Controls.Add(lblPort);
            this.Controls.Add(cbPorts);
            this.Controls.Add(btnConnect);

            startY += gap + 10;

            // 3. INPUTS GROUP
            GroupBox grpData = new GroupBox() { Text = "Transaction Data", Location = new Point(startX, startY), Size = new Size(540, 160) };

            // Amount (ATM Style)
            Label lblAmt = new Label() { Text = "Amount (RM):", Location = new Point(20, 30), AutoSize = true };
            txtAmount = new TextBox() { Location = new Point(120, 27), Width = 150, Text = "0.00", BackColor = Color.White };

            // ATM LOGIC BINDINGS
            txtAmount.KeyDown += TxtAmount_KeyDown;
            txtAmount.KeyPress += (s, e) => e.Handled = true; // Block normal typing

            // Invoice (Numbers Only)
            Label lblInv = new Label() { Text = "Invoice No:", Location = new Point(20, 70), AutoSize = true };
            txtInvoice = new TextBox() { Location = new Point(120, 67), Width = 150, Text = "000001" };
            txtInvoice.KeyPress += OnlyNumbers_KeyPress;

            // Cashier (Numbers Only)
            Label lblCash = new Label() { Text = "Cashier ID:", Location = new Point(20, 110), AutoSize = true };
            txtCashier = new TextBox() { Location = new Point(120, 107), Width = 150, Text = "99" };
            txtCashier.KeyPress += OnlyNumbers_KeyPress;

            // Auto Inc Checkbox
            chkAutoInc = new CheckBox() { Text = "Auto-Increment Invoice", Location = new Point(300, 70), AutoSize = true, Checked = true };

            grpData.Controls.Add(lblAmt); grpData.Controls.Add(txtAmount);
            grpData.Controls.Add(lblInv); grpData.Controls.Add(txtInvoice);
            grpData.Controls.Add(lblCash); grpData.Controls.Add(txtCashier);
            grpData.Controls.Add(chkAutoInc);
            this.Controls.Add(grpData);

            startY += 170;

            // 4. ACTION BUTTONS
            int btnW = 125;
            int btnH = 40;

            Button btnSale = new Button() { Text = "SALE", Location = new Point(startX, startY), Size = new Size(btnW, btnH), BackColor = Color.LightGreen };
            btnSale.Click += (s, e) => DoTx("020");

            Button btnVoid = new Button() { Text = "VOID", Location = new Point(startX + 135, startY), Size = new Size(btnW, btnH), BackColor = Color.Bisque };
            btnVoid.Click += (s, e) => DoTx("022");

            Button btnSetl = new Button() { Text = "SETTLEMENT", Location = new Point(startX + 270, startY), Size = new Size(btnW, btnH), BackColor = Color.LightBlue };
            btnSetl.Click += (s, e) => DoTx("050");

            Button btnRef = new Button() { Text = "REFUND", Location = new Point(startX + 405, startY), Size = new Size(btnW, btnH), BackColor = Color.LightPink };
            btnRef.Click += (s, e) => DoTx("026");

            this.Controls.Add(btnSale);
            this.Controls.Add(btnVoid);
            this.Controls.Add(btnSetl);
            this.Controls.Add(btnRef);

            startY += 50;

            // 5. CANCEL BUTTON
            btnCancel = new Button() { Text = "STOP / CANCEL WAIT", Location = new Point(startX, startY), Size = new Size(530, 30), BackColor = Color.IndianRed, ForeColor = Color.White, Enabled = false };
            btnCancel.Click += (s, e) => proto.CancelWait();
            this.Controls.Add(btnCancel);

            startY += 40;

            // 6. LOG BOX
            rtbLog = new RichTextBox() { Location = new Point(startX, startY), Size = new Size(530, 200), ReadOnly = true, BackColor = Color.WhiteSmoke };
            this.Controls.Add(rtbLog);
        }

        // --- INPUT LOGIC START ---

        // 1. ATM Style Amount Logic
        private void TxtAmount_KeyDown(object sender, KeyEventArgs e)
        {
            e.SuppressKeyPress = true; // Stop standard character entry

            // Handle Numbers (Top row & Numpad)
            if (e.KeyCode >= Keys.D0 && e.KeyCode <= Keys.D9)
            {
                if (_amountCents.ToString().Length < 10)
                    _amountCents = _amountCents * 10 + (e.KeyCode - Keys.D0);
            }
            else if (e.KeyCode >= Keys.NumPad0 && e.KeyCode <= Keys.NumPad9)
            {
                if (_amountCents.ToString().Length < 10)
                    _amountCents = _amountCents * 10 + (e.KeyCode - Keys.NumPad0);
            }
            // Handle Backspace
            else if (e.KeyCode == Keys.Back)
            {
                _amountCents = _amountCents / 10;
            }

            // Update Text Box
            txtAmount.Text = (_amountCents / 100.0).ToString("N2");
        }

        // 2. Strict Number Filter (No Alphabets)
        private void OnlyNumbers_KeyPress(object sender, KeyPressEventArgs e)
        {
            // Allow Control keys (Backspace) and Digits. Block everything else.
            if (!char.IsControl(e.KeyChar) && !char.IsDigit(e.KeyChar))
            {
                e.Handled = true;
            }
        }

        // --- INPUT LOGIC END ---

        private void RefreshPorts()
        {
            cbPorts.Items.Clear();
            cbPorts.Items.AddRange(SerialPort.GetPortNames());
            if (cbPorts.Items.Count > 0) cbPorts.SelectedIndex = 0;
        }

        private void BtnConnect_Click(object sender, EventArgs e)
        {
            if (btnConnect.Text == "Connect")
            {
                if (cbPorts.SelectedItem == null) { MessageBox.Show("Select a port"); return; }
                string res = proto.Connect(cbPorts.SelectedItem.ToString());
                if (res == "Success")
                {
                    Log("Connected to " + cbPorts.SelectedItem);
                    btnConnect.Text = "Disconnect";
                    cbPorts.Enabled = false;
                }
                else MessageBox.Show(res);
            }
            else
            {
                proto.Disconnect();
                Log("Disconnected");
                btnConnect.Text = "Connect";
                cbPorts.Enabled = true;
            }
        }

        private async void DoTx(string cmd)
        {
            if (!proto.IsConnected) { MessageBox.Show("Connect first!"); return; }

            try
            {
                double amt = (_amountCents / 100.0);
                int inv = 0;
                int.TryParse(txtInvoice.Text, out inv);

                // Protocol Rules: Sale needs 0 invoice, Void needs 0 amount
                if (cmd == "020") inv = 0;
                if (cmd == "022" || cmd == "050") amt = 0;

                byte[] packet = proto.BuildPacket(cmd, amt, inv, txtCashier.Text);

                btnCancel.Enabled = true;
                Log("Sending Command: " + cmd + "...");

                await proto.SendAndReceive(packet, (msg, data) =>
                {
                    this.Invoke((MethodInvoker)delegate {
                        Log(msg);
                        if (data != null && msg.Contains("RX"))
                        {
                            ParseResponse(data);
                        }
                    });
                });

                btnCancel.Enabled = false;
            }
            catch (Exception ex) { MessageBox.Show("Error: " + ex.Message); }
        }

        private void ParseResponse(byte[] data)
        {
            try
            {
                if (data.Length < 10) return;
                string ascii = Encoding.ASCII.GetString(data);
                ascii = ascii.Replace("\x02", "").Replace("\x03", "");

                if (ascii.Length < 5) return;
                string errCode = ascii.Substring(3, 2);

                if (errCode == "00")
                {
                    MessageBox.Show("TRANSACTION APPROVED!", "Success", MessageBoxButtons.OK, MessageBoxIcon.Information);
                    if (chkAutoInc.Checked)
                    {
                        int current = int.Parse(txtInvoice.Text);
                        txtInvoice.Text = (current + 1).ToString("D6");
                    }
                }
                else
                {
                    MessageBox.Show("DECLINED. Error Code: " + errCode, "Failed", MessageBoxButtons.OK, MessageBoxIcon.Error);
                }
            }
            catch (Exception ex)
            {
                Log("Parse Error: " + ex.Message);
            }
        }

        private void Log(string msg)
        {
            rtbLog.AppendText($"[{DateTime.Now:HH:mm:ss}] {msg}\n");
            rtbLog.ScrollToCaret();
        }
    }
}