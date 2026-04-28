using System;
using System.Collections.Generic;
using System.IO.Ports;
using System.Text;
using System.Threading;
using System.Threading.Tasks;

namespace GHL_Simulator_Deadboy
{
    public class GHLProtocol
    {
        private SerialPort _serialPort;
        private bool _cancelFlag = false;

        // Protocol Constants
        private const byte STX = 0x02;
        private const byte ETX = 0x03;

        // Property to check connection status
        public bool IsConnected => _serialPort != null && _serialPort.IsOpen;

        /// <summary>
        /// Opens the connection to the specified COM port.
        /// </summary>
        public string Connect(string portName)
        {
            try
            {
                Disconnect();
                Thread.Sleep(100); // Brief pause to ensure OS releases handle

                // Standard GHL Settings: 9600 Baud, 8 Data Bits, No Parity, 1 Stop Bit
                _serialPort = new SerialPort(portName, 9600, Parity.None, 8, StopBits.One);
                _serialPort.ReadTimeout = 500;
                _serialPort.Open();

                return "Success";
            }
            catch (Exception ex)
            {
                return ex.Message;
            }
        }

        /// <summary>
        /// Closes the connection and disposes resources.
        /// </summary>
        public void Disconnect()
        {
            try
            {
                if (_serialPort != null && _serialPort.IsOpen)
                {
                    _serialPort.Close();
                    _serialPort.Dispose();
                    _serialPort = null;
                }
            }
            catch { }
        }

        /// <summary>
        /// Signals the receiving loop to stop waiting for data.
        /// </summary>
        public void CancelWait() => _cancelFlag = true;

        /// <summary>
        /// Calculates the 8-byte XOR Check Digit required by the protocol.
        /// </summary>
        private byte[] CalculateCheckDigit(byte[] data)
        {
            List<byte> workingData = new List<byte>(data);

            // Pad with 0xFF if not divisible by 8
            int remainder = workingData.Count % 8;
            if (remainder != 0)
            {
                for (int i = 0; i < (8 - remainder); i++)
                    workingData.Add(0xFF);
            }

            byte[] checkDigit = new byte[8];

            // XOR each 8-byte block against the check digit
            for (int i = 0; i < workingData.Count; i += 8)
                for (int j = 0; j < 8; j++)
                    checkDigit[j] ^= workingData[i + j];

            return checkDigit;
        }

        /// <summary>
        /// Formats a command packet with STX, Payload, Check Digit, and ETX.
        /// </summary>
        public byte[] BuildPacket(string cmd, double amount, int invoice, string cashier)
        {
            // Amount: 12 digits, implied 2 decimal places (e.g. 1.00 -> "000000000100")
            string amtStr = ((long)(amount * 100)).ToString("D12");

            // Invoice: 6 digits padded