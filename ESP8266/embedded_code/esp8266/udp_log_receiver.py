"""
ESP8266 UDP 日志接收器
用法: python udp_log_receiver.py
"""
import socket
import time

UDP_PORT = 4210
BUFFER_SIZE = 1024

def main():
    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    sock.bind(("0.0.0.0", UDP_PORT))
    sock.settimeout(1.0)

    print(f"=== ESP8266 UDP Log Receiver ===")
    print(f"Listening on UDP port {UDP_PORT}...")
    print(f"Make sure ESP8266 and your PC are on the same WiFi network.")
    print(f"Press Ctrl+C to stop.\n")

    while True:
        try:
            data, addr = sock.recvfrom(BUFFER_SIZE)
            msg = data.decode("utf-8", errors="replace").strip()
            timestamp = time.strftime("%H:%M:%S")
            print(f"[{timestamp}] {msg}")
        except socket.timeout:
            continue
        except KeyboardInterrupt:
            print("\nStopped.")
            break

    sock.close()

if __name__ == "__main__":
    main()
