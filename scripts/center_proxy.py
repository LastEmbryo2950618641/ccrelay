import selectors
import socket
import threading

LISTEN_HOST = "0.0.0.0"
LISTEN_PORT = 29292
TARGET_HOST = "127.0.0.1"
TARGET_PORT = 29291
BUFFER_SIZE = 65536


def close_quietly(sock):
    if sock is None:
        return
    try:
        sock.close()
    except Exception:
        pass


def shutdown_write_quietly(sock):
    if sock is None:
        return
    try:
        sock.shutdown(socket.SHUT_WR)
    except Exception:
        pass


def relay_connection(client, upstream):
    selector = selectors.DefaultSelector()
    sockets = (client, upstream)
    peer = {client: upstream, upstream: client}
    try:
        for sock in sockets:
            sock.setblocking(False)
            selector.register(sock, selectors.EVENT_READ)
        while selector.get_map():
            for key, _ in selector.select(timeout=30):
                source = key.fileobj
                target = peer[source]
                try:
                    data = source.recv(BUFFER_SIZE)
                except BlockingIOError:
                    continue
                except Exception:
                    data = b""
                if data:
                    try:
                        target.sendall(data)
                    except Exception:
                        data = b""
                if not data:
                    try:
                        selector.unregister(source)
                    except Exception:
                        pass
                    shutdown_write_quietly(target)
    finally:
        selector.close()
        close_quietly(client)
        close_quietly(upstream)


def serve_forever():
    server = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    server.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    server.bind((LISTEN_HOST, LISTEN_PORT))
    server.listen(100)
    while True:
        client, _ = server.accept()
        upstream = socket.create_connection((TARGET_HOST, TARGET_PORT), timeout=30)
        thread = threading.Thread(target=relay_connection, args=(client, upstream), daemon=True)
        thread.start()


if __name__ == "__main__":
    serve_forever()
