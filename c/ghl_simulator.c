#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <windows.h>
#include <time.h>
#include <math.h>
#include <stdarg.h>
#include <setupapi.h>

#define STX 0x02
#define ETX 0x03

#define COLOR_RED 12
#define COLOR_GREEN 10
#define COLOR_CYAN 11
#define COLOR_YELLOW 14
#define COLOR_DEFAULT 7

HANDLE hComm = INVALID_HANDLE_VALUE;
int is_connected = 0;
char current_port[20] = "None";
const char* LOG_FILE = "communication.log";

const GUID GUID_DEVCLASS_PORTS_LOCAL = {0x4D36E978, 0xE325, 0x11CE, {0xBF, 0xC1, 0x08, 0x00, 0x2B, 0xE1, 0x03, 0x18}};

// --- UTILITY: CONSOLE COLORS ---
void set_color(int color_code) {
    HANDLE hConsole = GetStdHandle(STD_OUTPUT_HANDLE);
    SetConsoleTextAttribute(hConsole, color_code);
}

void reset_color() {
    set_color(COLOR_DEFAULT);
}

// --- UTILITY: FILE LOGGING ---
void write_log(const char* format, ...) {
    FILE *f = fopen(LOG_FILE, "a");
    if (!f) return;

    time_t t = time(NULL);
    struct tm tm = *localtime(&t);
    fprintf(f, "[%04d-%02d-%02d %02d:%02d:%02d] ", tm.tm_year + 1900, tm.tm_mon + 1, tm.tm_mday, tm.tm_hour, tm.tm_min, tm.tm_sec);

    va_list args;
    va_start(args, format);
    vfprintf(f, format, args);
    va_end(args);

    fprintf(f, "\n");
    fclose(f);
}

// --- UTILITY: SAFE INPUT READER ---
void get_input(char* buffer, int max_len) {
    if (fgets(buffer, max_len, stdin) != NULL) {
        size_t len = strlen(buffer);
        if (len > 0 && buffer[len-1] == '\n') {
            buffer[len-1] = '\0';
        }
    }
}

void pause_console() {
    printf("\nPress Enter to continue...");
    char buf[10];
    get_input(buf, sizeof(buf));
}

// --- UTILITY: EXTRACT STRING SAFELY (LENGTH AWARE) ---
void extract_field(const unsigned char* payload, int payload_len, int start, int length, char* out_buf) {
    if (start + length <= payload_len) {
        strncpy(out_buf, (const char*)(payload + start), length);
        out_buf[length] = '\0';
    } else {
        strcpy(out_buf, "N/A"); 
    }
}

// --- UTILITY: FORMAT CURRENCY ---
void format_money(const char* raw, char* out_buf) {
    if (strcmp(raw, "N/A") == 0) {
        strcpy(out_buf, "0.00");
        return;
    }
    long val = atol(raw);
    sprintf(out_buf, "%.2f", val / 100.0);
}

// --- UTILITY: CARD TYPE LOOKUP ---
const char* get_card_name(const char* code) {
    if (strcmp(code, "04") == 0) return "VISA";
    if (strcmp(code, "05") == 0) return "MASTERCARD";
    if (strcmp(code, "06") == 0) return "DINERS";
    if (strcmp(code, "07") == 0) return "AMEX";
    if (strcmp(code, "08") == 0) return "MYDEBIT";
    if (strcmp(code, "09") == 0) return "JCB";
    if (strcmp(code, "10") == 0) return "UNIONPAY";
    if (strcmp(code, "11") == 0) return "E-WALLET";
    return "UNKNOWN";
}

void show_card_codes() {
    set_color(COLOR_CYAN);
    printf("\n--- CARD TYPE CODES ---\n");
    printf("04 = VISA\n");
    printf("05 = MASTERCARD\n");
    printf("06 = DINERS\n");
    printf("07 = AMEX\n");
    printf("08 = MYDEBIT\n");
    printf("09 = JCB\n");
    printf("10 = UNIONPAY\n");
    printf("11 = E-WALLET\n");
    printf("-----------------------\n");
    reset_color();
    pause_console();
}

// --- TX HEX LOGGING ---
void log_tx_hex_breakdown(const unsigned char* data, int len) {
    if (len < 35) return; 

    int payload_len = len - 10; 
    const unsigned char* payload = data + 1; 

    write_log("--- TX HEX BREAKDOWN ---");
    write_log("Total Packet Length: %d bytes", len);
    write_log("STX (1 byte)  : 02");

    char buf[50];

    extract_field(payload, payload_len, 0, 3, buf);
    char cmd_name[20] = "UNKNOWN";
    if (strcmp(buf, "020") == 0) strcpy(cmd_name, "SALE");
    else if (strcmp(buf, "022") == 0) strcpy(cmd_name, "VOID");
    else if (strcmp(buf, "050") == 0) strcpy(cmd_name, "SETTLEMENT");
    else if (strcmp(buf, "026") == 0) strcpy(cmd_name, "REFUND");
    write_log("Command (3)   : %s (%s)", buf, cmd_name);

    extract_field(payload, payload_len, 3, 12, buf);
    char fmt_amt[20];
    format_money(buf, fmt_amt);
    write_log("Amount (12)   : %s (RM %s)", buf, fmt_amt);

    extract_field(payload, payload_len, 15, 6, buf);
    write_log("Invoice (6)   : %s", buf);

    extract_field(payload, payload_len, 21, 4, buf);
    write_log("Cashier (4)   : %s", buf);

    char mac_hex[50] = "";
    char temp[4];
    for(int i = len - 9; i < len - 1; i++) {
        sprintf(temp, "%02X ", data[i]);
        strcat(mac_hex, temp);
    }
    write_log("MAC/Pad (8)   : %s", mac_hex);

    write_log("ETX (1 byte)  : 03");
    write_log("------------------------");
}

// --- RX HEX LOGGING ---
void log_hex_breakdown(const unsigned char* data, int len) {
    int payload_len = len - 10;
    const unsigned char* payload = data + 1;
    
    write_log("--- RX HEX BREAKDOWN ---");
    write_log("Total Packet Length: %d bytes", len);
    write_log("STX (1 byte)  : 02");
    
    char buf[50];
    
    extract_field(payload, payload_len, 0, 3, buf);
    write_log("Command (3)   : %s", buf);
    
    extract_field(payload, payload_len, 3, 2, buf);
    write_log("Error Code (2): %s", buf);
    
    extract_field(payload, payload_len, 5, 22, buf);
    write_log("Card No (22)  : %s", buf);
    
    extract_field(payload, payload_len, 27, 4, buf);
    write_log("Expiry (4)    : %s", buf);
    
    extract_field(payload, payload_len, 31, 2, buf);
    write_log("Card Type (2) : %s", buf);
    
    extract_field(payload, payload_len, 33, 8, buf);
    write_log("Auth Code (8) : %s", buf);
    
    extract_field(payload, payload_len, 41, 12, buf);
    write_log("Gross Amt (12): %s", buf);
    
    extract_field(payload, payload_len, 53, 12, buf);
    write_log("Net Amt (12)  : %s", buf);
    
    extract_field(payload, payload_len, 65, 6, buf);
    write_log("Trace No (6)  : %s", buf);
    
    extract_field(payload, payload_len, 71, 6, buf);
    write_log("Invoice (6)   : %s", buf);
    
    extract_field(payload, payload_len, 77, 4, buf);
    write_log("Cashier (4)   : %s", buf);
    
    extract_field(payload, payload_len, 81, 15, buf);
    write_log("Card Name (15): %s", buf);
    
    if (payload_len >= 125) {
        extract_field(payload, payload_len, 96, 8, buf);
        write_log("Term ID (8)   : %s", buf);
        
        extract_field(payload, payload_len, 104, 15, buf);
        write_log("Merch ID (15) : %s", buf);
        
        extract_field(payload, payload_len, 119, 6, buf);
        write_log("Batch No (6)  : %s", buf);
    } else {
        write_log("... (Older Firmware: TID, MID, and Batch No omitted)");
    }
    
    write_log("------------------------");
}

// --- RECEIPT PARSER ---
void print_receipt(const unsigned char* data, int len) {
    int payload_len = len - 10; 
    const unsigned char* payload = data + 1; 

    char tid[9], mid[16], batch[7], stan[7], inv[7], cshr[5];
    char card_no[23], exp[5], c_type[3], auth[9], gross[13], net[13];
    char fmt_gross[20], fmt_net[20];

    extract_field(payload, payload_len, 96, 8, tid);
    extract_field(payload, payload_len, 104, 15, mid);
    extract_field(payload, payload_len, 119, 6, batch);
    extract_field(payload, payload_len, 65, 6, stan);
    extract_field(payload, payload_len, 71, 6, inv);
    extract_field(payload, payload_len, 77, 4, cshr);
    extract_field(payload, payload_len, 5, 22, card_no);
    extract_field(payload, payload_len, 27, 4, exp);
    extract_field(payload, payload_len, 31, 2, c_type);
    extract_field(payload, payload_len, 33, 8, auth);
    extract_field(payload, payload_len, 41, 12, gross);
    extract_field(payload, payload_len, 53, 12, net);

    format_money(gross, fmt_gross);
    format_money(net, fmt_net);

    time_t t = time(NULL);
    struct tm tm = *localtime(&t);
    char time_str[30];
    sprintf(time_str, "%04d-%02d-%02d %02d:%02d:%02d", tm.tm_year + 1900, tm.tm_mon + 1, tm.tm_mday, tm.tm_hour, tm.tm_min, tm.tm_sec);

    char receipt_text[2048];
    sprintf(receipt_text,
        "\n=========================================\n"
        "            *** APPROVED *** \n"
        "=========================================\n"
        "MERCHANT ID:  %s\n"
        "TERMINAL ID:  %s\n"
        "TIME:         %s\n"
        "BATCH NO:     %s\n"
        "-----------------------------------------\n"
        "STAN:         %s\n"
        "INVOICE:      %s\n"
        "-----------------------------------------\n"
        "TRANS TYPE:   SALE\n"
        "CASHIER ID:   %s\n"
        "\n"
        "CARD NO:      %s\n"
        "EXPIRY:       %s\n"
        "CARD TYPE:    %s (%s)\n"
        "AUTH CODE:    %s\n"
        "-----------------------------------------\n"
        "GROSS AMT:    RM %s\n"
        "NET AMT:      RM %s\n"
        "-----------------------------------------\n"
        "               THANK YOU!                \n"
        "=========================================\n",
        mid, tid, time_str, batch, stan, inv, cshr, card_no, exp, c_type, get_card_name(c_type), auth, fmt_gross, fmt_net
    );

    set_color(COLOR_GREEN);
    printf("%s", receipt_text);
    reset_color();
    
    write_log("%s", receipt_text);
}

// --- UTILITY: CALCULATE CHECK DIGIT ---
void calculate_check_digit(const unsigned char* data, int len, unsigned char* out_chk) {
    unsigned char padded_data[256];
    memset(padded_data, 0, sizeof(padded_data));
    memcpy(padded_data, data, len);

    int rem = len % 8;
    int padded_len = len;
    
    if (rem != 0) {
        int pad_size = 8 - rem;
        for (int i = 0; i < pad_size; i++) {
            padded_data[len + i] = 0xFF;
        }
        padded_len += pad_size;
    }

    memset(out_chk, 0, 8);
    for (int i = 0; i < padded_len; i += 8) {
        for (int j = 0; j < 8; j++) {
            out_chk[j] ^= padded_data[i + j];
        }
    }
}

// --- SERIAL COMMUNICATION ---
int connect_serial(const char* port_name) {
    if (hComm != INVALID_HANDLE_VALUE) {
        CloseHandle(hComm);
        hComm = INVALID_HANDLE_VALUE;
        is_connected = 0;
    }

    char port_path[20];
    sprintf(port_path, "\\\\.\\%s", port_name);

    hComm = CreateFileA(port_path, GENERIC_READ | GENERIC_WRITE, 0, NULL, OPEN_EXISTING, 0, NULL);
    
    if (hComm == INVALID_HANDLE_VALUE) {
        DWORD err = GetLastError();
        set_color(COLOR_RED);
        printf("\n[!] Error: Could not open %s (Code: %lu)\n", port_name, err);
        if (err == 5) printf("    -> Access Denied: Another program is using this port.\n");
        if (err == 2) printf("    -> Not Found: Ensure the cable is plugged in.\n");
        reset_color();
        write_log("FAILED CONNECTION to %s (Error %lu)", port_name, err);
        return 0;
    }

    DCB dcbSerialParams = {0};
    dcbSerialParams.DCBlength = sizeof(dcbSerialParams);
    if (!GetCommState(hComm, &dcbSerialParams)) { CloseHandle(hComm); return 0; }

    dcbSerialParams.BaudRate = CBR_9600;
    dcbSerialParams.ByteSize = 8;
    dcbSerialParams.StopBits = ONESTOPBIT;
    dcbSerialParams.Parity   = NOPARITY;
    SetCommState(hComm, &dcbSerialParams);

    COMMTIMEOUTS timeouts = {0};
    timeouts.ReadIntervalTimeout = 50;
    timeouts.ReadTotalTimeoutConstant = 65000;
    timeouts.ReadTotalTimeoutMultiplier = 10;
    timeouts.WriteTotalTimeoutConstant = 50;
    timeouts.WriteTotalTimeoutMultiplier = 10;
    SetCommTimeouts(hComm, &timeouts);

    set_color(COLOR_GREEN);
    printf("\n[+] Successfully connected to %s\n", port_name);
    reset_color();
    
    strcpy(current_port, port_name);
    is_connected = 1;
    write_log("CONNECTED to %s", port_name);
    return 1;
}

void disconnect_serial() {
    if (hComm != INVALID_HANDLE_VALUE) {
        CloseHandle(hComm);
        hComm = INVALID_HANDLE_VALUE;
        is_connected = 0;
        strcpy(current_port, "None");
        printf("\n[-] Disconnected.\n");
        write_log("DISCONNECTED");
    } else {
        printf("\n[-] Already disconnected.\n");
    }
}

// --- AUTO-DETECT PROLIFIC ---
int auto_detect_and_connect() {
    printf("\n[*] Scanning for Prolific USB Serial Port...\n");
    
    HDEVINFO hDevInfo = SetupDiGetClassDevs(&GUID_DEVCLASS_PORTS_LOCAL, NULL, NULL, DIGCF_PRESENT);
    if (hDevInfo == INVALID_HANDLE_VALUE) return 0;

    SP_DEVINFO_DATA deviceInfoData;
    deviceInfoData.cbSize = sizeof(SP_DEVINFO_DATA);
    DWORD i = 0;
    int found = 0;
    char found_port[20];

    while (SetupDiEnumDeviceInfo(hDevInfo, i++, &deviceInfoData)) {
        char friendlyName[256];
        DWORD dataType;
        if (SetupDiGetDeviceRegistryPropertyA(hDevInfo, &deviceInfoData, SPDRP_FRIENDLYNAME, &dataType, (PBYTE)friendlyName, sizeof(friendlyName), NULL)) {
            if (strstr(friendlyName, "Prolific") != NULL || strstr(friendlyName, "PL2303") != NULL) {
                char* comPos = strstr(friendlyName, "(COM");
                if (comPos != NULL) {
                    comPos++; 
                    char* endPos = strchr(comPos, ')');
                    if (endPos != NULL) {
                        int len = endPos - comPos;
                        strncpy(found_port, comPos, len);
                        found_port[len] = '\0';
                        found = 1;
                        break; 
                    }
                }
            }
        }
    }
    SetupDiDestroyDeviceInfoList(hDevInfo);

    if (found) {
        return connect_serial(found_port);
    } else {
        set_color(COLOR_RED);
        printf("[-] Could not find a Prolific cable.\n");
        reset_color();
        return 0;
    }
}

// --- PROTOCOL LOGIC ---
int send_transaction(const char* cmd, double amt, int inv, const char* cshr, const char* human_desc) {
    if (!is_connected) {
        set_color(COLOR_RED);
        printf("\n[!] Please connect to a COM port first.\n");
        reset_color();
        pause_console();
        return 0; 
    }

    unsigned char payload[50];
    unsigned char packet[100];
    unsigned char chk[8];
    
    sprintf((char*)payload, "%3s%012.0f%06d%4s", cmd, round(amt * 100.0), inv, cshr);
    calculate_check_digit(payload, 25, chk);

    int pkt_idx = 0;
    packet[pkt_idx++] = STX;
    memcpy(&packet[pkt_idx], payload, 25);
    pkt_idx += 25;
    memcpy(&packet[pkt_idx], chk, 8);
    pkt_idx += 8;
    packet[pkt_idx++] = ETX;

    char hex_tx[300] = "";
    char temp[4];
    for(int i = 0; i < pkt_idx; i++) {
        sprintf(temp, "%02X ", packet[i]);
        strcat(hex_tx, temp);
    }

    write_log("ACTION: %s", human_desc);
    write_log("TX > %s", hex_tx);
    
    // --> NEW LOG CALL INSERTED HERE <--
    log_tx_hex_breakdown(packet, pkt_idx);

    set_color(COLOR_CYAN);
    printf("\nTX > %s\n", hex_tx);
    reset_color();

    DWORD bytes_written, bytes_read;
    WriteFile(hComm, packet, pkt_idx, &bytes_written, NULL);

    set_color(COLOR_YELLOW);
    printf("Waiting for terminal response (Please interact with the terminal)...\n");
    reset_color();

    unsigned char rx_buf[1024];
    int rx_idx = 0;
    unsigned char byte_read;
    int in_msg = 0;

    while (ReadFile(hComm, &byte_read, 1, &bytes_read, NULL) && bytes_read > 0) {
        rx_buf[rx_idx++] = byte_read;
        if (byte_read == ETX) {
            in_msg = 1;
            break;
        }
    }

    if (!in_msg) {
        set_color(COLOR_RED);
        printf("\n[!] Error: Timeout or connection lost.\n");
        reset_color();
        write_log("ERROR: Timeout or connection lost.");
        pause_console();
        return 0;
    }

    char hex_rx[3072] = "";
    for(int i = 0; i < rx_idx; i++) {
        sprintf(temp, "%02X ", rx_buf[i]);
        strcat(hex_rx, temp);
    }
    
    write_log("RX < %s", hex_rx);
    log_hex_breakdown(rx_buf, rx_idx);
    
    set_color(COLOR_CYAN);
    printf("RX < %s\n", hex_rx);
    reset_color();

    if (rx_idx > 10) {
        char err_code[3];
        err_code[0] = rx_buf[4];
        err_code[1] = rx_buf[5];
        err_code[2] = '\0';
        
        if (strcmp(err_code, "00") == 0) {
            write_log("STATUS: APPROVED");
            print_receipt(rx_buf, rx_idx);
            pause_console();
            return 1; 
        } else {
            write_log("STATUS: DECLINED (Code: %s)", err_code);
            set_color(COLOR_RED);
            printf("\n>>> TRANSACTION DECLINED (Code: %s) <<<\n", err_code);
            reset_color();
            pause_console();
            return 0; 
        }
    }
    pause_console();
    return 0; 
}

// --- MAIN MENU ---
int main() {
    char input_buf[256];
    int choice = 0;
    double amt = 0.01;
    int inv = 1;
    char cshr[5] = "99";
    int auto_inc = 1; 

    write_log("=== APP STARTED ===");

    while (1) {
        system("cls"); // Clear screen for a cleaner menu feel
        
        printf("\n=========================================\n");
        printf("   DEADBOY POS TERMINAL SIMULATOR (CLI)   \n");
        printf("=========================================\n");
        
        if (is_connected) {
            set_color(COLOR_GREEN);
            printf(" STATUS: [ CONNECTED to %s ]\n", current_port);
            reset_color();
        } else {
            set_color(COLOR_RED);
            printf(" STATUS: [ DISCONNECTED ]\n");
            reset_color();
        }
        
        printf("-----------------------------------------\n");
        printf(" [ CONNECTION ]\n");
        printf(" 1. Auto-Detect Prolific USB\n");
        printf(" 2. Connect to specific COM port\n");
        printf(" 3. Disconnect\n\n");
        
        printf(" [ TRANSACTIONS ]\n");
        printf(" 4. Sale\n");
        printf(" 5. Void\n");
        printf(" 6. Settlement\n");
        printf(" 7. Refund\n\n");
        
        printf(" [ SETTINGS & TOOLS ]\n");
        printf(" 8. View Card Type Codes Menu\n");
        printf(" 9. Toggle Auto-Increment Invoice (Current: %s)\n", auto_inc ? "ON" : "OFF");
        printf(" 10. Exit\n");
        printf("=========================================\n");
        printf("Select option: ");
        
        get_input(input_buf, sizeof(input_buf));
        choice = atoi(input_buf);

        switch (choice) {
            case 1:
                auto_detect_and_connect();
                pause_console();
                break;
            case 2:
                printf("Enter COM Port Number (e.g., 1 for COM1): ");
                get_input(input_buf, sizeof(input_buf));
                int port_num = atoi(input_buf);
                if (port_num > 0) {
                    char temp_port[20];
                    sprintf(temp_port, "COM%d", port_num);
                    connect_serial(temp_port);
                } else {
                    set_color(COLOR_RED);
                    printf("Invalid port number.\n");
                    reset_color();
                }
                pause_console();
                break;
            case 3:
                disconnect_serial();
                pause_console();
                break;
            case 4:
                if (!is_connected) {
                    set_color(COLOR_RED);
                    printf("\n[!] Connect to a port first (Option 1 or 2).\n");
                    reset_color();
                    pause_console();
                    break;
                }
                printf("Enter Amount (RM) [Press Enter for 0.01]: ");
                get_input(input_buf, sizeof(input_buf));
                if (strlen(input_buf) == 0) {
                    amt = 0.01; 
                } else {
                    amt = atof(input_buf);
                }
                
                printf("Current Invoice No: %06d\n", inv);
                
                char log_desc[100];
                sprintf(log_desc, "SALE RM %.2f (Inv: %d)", amt, inv);
                
                if (send_transaction("020", amt, inv, cshr, log_desc)) {
                    if (auto_inc) inv++;
                }
                break;
            case 5:
                if (!is_connected) { set_color(COLOR_RED); printf("\n[!] Connect first.\n"); reset_color(); pause_console(); break; }
                printf("Enter Invoice No to Void: ");
                get_input(input_buf, sizeof(input_buf));
                int void_inv = atoi(input_buf);
                
                char void_desc[100];
                sprintf(void_desc, "VOID (Inv: %d)", void_inv);
                send_transaction("022", 0.0, void_inv, cshr, void_desc);
                break;
            case 6:
                if (!is_connected) { set_color(COLOR_RED); printf("\n[!] Connect first.\n"); reset_color(); pause_console(); break; }
                send_transaction("050", 0.0, 0, cshr, "SETTLEMENT");
                break;
            case 7:
                if (!is_connected) { set_color(COLOR_RED); printf("\n[!] Connect first.\n"); reset_color(); pause_console(); break; }
                printf("Enter Refund Amount (RM): ");
                get_input(input_buf, sizeof(input_buf));
                amt = atof(input_buf);
                
                char ref_desc[100];
                sprintf(ref_desc, "REFUND RM %.2f", amt);
                send_transaction("026", amt, 0, cshr, ref_desc);
                break;
            case 8:
                show_card_codes();
                break;
            case 9:
                auto_inc = !auto_inc;
                set_color(COLOR_YELLOW);
                printf("\n[*] Auto-Increment Invoice is now %s\n", auto_inc ? "ON" : "OFF");
                reset_color();
                pause_console();
                break;
            case 10:
                disconnect_serial();
                write_log("=== APP CLOSED ===\n");
                printf("\nExiting...\n");
                return 0;
            default:
                set_color(COLOR_RED);
                printf("\n[!] Invalid option. Try again.\n");
                reset_color();
                pause_console();
        }
    }
    return 0;
}