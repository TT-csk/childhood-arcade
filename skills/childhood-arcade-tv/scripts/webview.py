"""Read a fixed health expression from a localhost-forwarded debug WebView."""
import base64
import hashlib
import json
import os
import socket
import struct
from urllib.parse import urlsplit


def read_exact(stream, count):
    chunks = []
    while count:
        chunk = stream.recv(count)
        if not chunk:
            raise OSError('WebView连接已关闭')
        chunks.append(chunk)
        count -= len(chunk)
    return b''.join(chunks)


def send_frame(stream, payload, opcode=1):
    mask = os.urandom(4)
    size = len(payload)
    header = bytes([0x80 | opcode])
    if size < 126:
        header += bytes([0x80 | size])
    elif size < 65536:
        header += b'\xfe' + struct.pack('!H', size)
    else:
        header += b'\xff' + struct.pack('!Q', size)
    stream.sendall(header + mask + bytes(value ^ mask[i % 4] for i, value in enumerate(payload)))


def page_health(websocket_url, forwarded_port):
    url = urlsplit(websocket_url)
    if url.scheme != 'ws' or url.hostname not in ('localhost', '127.0.0.1') or url.port != forwarded_port:
        raise ValueError('WebView调试地址不属于本次本机转发')
    with socket.create_connection(('127.0.0.1', forwarded_port), timeout=3) as stream:
        stream.settimeout(3)
        key = base64.b64encode(os.urandom(16)).decode()
        route = url.path + (('?' + url.query) if url.query else '')
        request = ('GET ' + route + ' HTTP/1.1\r\nHost: 127.0.0.1:' + str(forwarded_port)
                   + '\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Key: ' + key
                   + '\r\nSec-WebSocket-Version: 13\r\n\r\n')
        stream.sendall(request.encode('ascii'))
        header = b''
        while not header.endswith(b'\r\n\r\n'):
            header += read_exact(stream, 1)
            if len(header) > 16384:
                raise ValueError('WebView握手过大')
        expected = base64.b64encode(hashlib.sha1((key + '258EAFA5-E914-47DA-95CA-C5AB0DC85B11').encode()).digest())
        headers = {}
        for line in header.split(b'\r\n')[1:]:
            if b':' in line:
                name, value = line.split(b':', 1)
                headers[name.lower()] = value.strip()
        if b' 101 ' not in header.split(b'\r\n')[0] or headers.get(b'sec-websocket-accept') != expected:
            raise ValueError('WebView握手失败')
        expression = '''(function(){var a=window.Arcade;return {title:document.title,ready:!!a,
          state:a?a.getState():null,catalogCount:window.GAME_CATALOG?window.GAME_CATALOG.length:0,
          visible:document.visibilityState,bodyText:document.body.innerText.slice(0,200)};})()'''
        command = {'id': 1, 'method': 'Runtime.evaluate', 'params': {'expression': expression, 'returnByValue': True}}
        send_frame(stream, json.dumps(command).encode())
        fragments = b''
        for _ in range(100):
            first, second = read_exact(stream, 2)
            size = second & 127
            if size == 126:
                size = struct.unpack('!H', read_exact(stream, 2))[0]
            elif size == 127:
                size = struct.unpack('!Q', read_exact(stream, 8))[0]
            if size > 1024 * 1024:
                raise ValueError('WebView响应过大')
            mask = read_exact(stream, 4) if second & 128 else None
            payload = read_exact(stream, size)
            if mask:
                payload = bytes(value ^ mask[i % 4] for i, value in enumerate(payload))
            opcode = first & 15
            if opcode == 8:
                raise OSError('WebView关闭了调试连接')
            if opcode == 9:
                send_frame(stream, payload, 10)
                continue
            if opcode in (0, 1):
                fragments += payload
                if len(fragments) > 1024 * 1024:
                    raise ValueError('WebView响应过大')
                if not first & 128:
                    continue
                message = json.loads(fragments)
                fragments = b''
                if message.get('id') == 1:
                    if message.get('error') or message.get('result', {}).get('exceptionDetails'):
                        raise ValueError('WebView页面检查失败')
                    return message['result']['result'].get('value')
        raise TimeoutError('未收到页面检查结果')
