import http from 'http';
import crypto from 'crypto';
import { WebSocketServer, WebSocket } from 'ws';
import dgram from 'dgram';
import os from 'os';

let httpServer = null;
let wss = null;
let udpServer = null;
let beaconInterval = null;
const rooms = new Map();
const UDP_PORT = 8888;
export const DEFAULT_PASSWORD = (process.env.RAMBOX_PIN || '240994').toString().trim() || '240994';
export const ALLOWED_PINS = (process.env.RAMBOX_ALLOWED_PINS || '')
  .split(',')
  .map(p => p.trim())
  .filter(Boolean);

function isPinAllowed(pin) {
  return ALLOWED_PINS.length === 0 || ALLOWED_PINS.includes(pin);
}

function maskPin(pin) {
  const s = String(pin || '');
  if (s.length <= 3) return '***';
  return s.slice(0, 2) + '*'.repeat(Math.max(3, s.length - 2));
}

function sendError(ws, code, message) {
  try {
    ws.send(JSON.stringify({ type: 'error', code, message }));
  } catch (e) {}
}

function cleanupRoom(pin) {
  const room = rooms.get(pin);
  if (room && !room.hostSocket && (!room.clients || room.clients.size === 0) && !room.clientSocket) {
    rooms.delete(pin);
    return true;
  }
  return false;
}

export function getAllLocalIps() {
  const interfaces = os.networkInterfaces();
  const validIps = [];
  const virtualKeywords = ['virtual', 'vbox', 'vmware', 'wsl', 'hyper-v', 'vethernet', 'tailscale', 'zerotier', 'bluetooth', 'loopback', 'teredo'];

  for (const name of Object.keys(interfaces)) {
    const isVirtual = virtualKeywords.some(k => name.toLowerCase().includes(k));
    for (const iface of interfaces[name]) {
      if (iface.family === 'IPv4' && !iface.internal) {
        if (iface.address.startsWith('169.254.')) continue;
        if (!isVirtual) {
          validIps.unshift(iface.address);
        } else {
          validIps.push(iface.address);
        }
      }
    }
  }
  return validIps.length > 0 ? validIps : ['127.0.0.1'];
}

export function getLocalIp() {
  const ips = getAllLocalIps();
  return ips[0] || '127.0.0.1';
}

export function getBroadcastAddresses() {
  const interfaces = os.networkInterfaces();
  const broadcasts = new Set(['255.255.255.255']);
  
  for (const name of Object.keys(interfaces)) {
    for (const iface of interfaces[name]) {
      if (iface.family === 'IPv4' && !iface.internal && !iface.address.startsWith('169.254.')) {
        try {
          const ipParts = iface.address.split('.').map(Number);
          const maskParts = (iface.netmask || '255.255.255.0').split('.').map(Number);
          const bcastParts = ipParts.map((part, idx) => (part | (~maskParts[idx] & 255)));
          broadcasts.add(bcastParts.join('.'));
        } catch (e) {}
      }
    }
  }
  return Array.from(broadcasts);
}

function startUdpDiscoveryListener() {
  if (udpServer) return;

  try {
    udpServer = dgram.createSocket({ type: 'udp4', reuseAddr: true });
    
    udpServer.on('error', (err) => {
      console.warn('[UdpDiscovery] UDP error:', err.message);
    });

    udpServer.on('message', (msg, rinfo) => {
      try {
        const data = JSON.parse(msg.toString());
        if (data.type === 'discover') {
          const currentIp = getLocalIp();
          const allIps = getAllLocalIps();
          const response = JSON.stringify({
            type: 'discover-reply',
            ip: currentIp,
            allIps: allIps,
            port: 8081,
            app: 'rambox'
          });
          const replyBuffer = Buffer.from(response);
          udpServer.send(replyBuffer, 0, replyBuffer.length, rinfo.port, rinfo.address, (err) => {
            if (!err) {
              console.log(`[UdpDiscovery] Sent reply to ${rinfo.address}:${rinfo.port}`);
            }
          });
        }
      } catch (e) {}
    });

    udpServer.bind(UDP_PORT, () => {
      try {
        udpServer.setBroadcast(true);
        console.log(`[UdpDiscovery] Listening on UDP port ${UDP_PORT}`);
      } catch (e) {}
    });
  } catch (err) {
    console.error('[UdpDiscovery] Failed to start UDP listener:', err);
  }
}

export function startSignalingServer(port = 8081) {
  startUdpDiscoveryListener();
  if (httpServer && wss) return { port, ip: getLocalIp() };

  try {
    httpServer = http.createServer((req, res) => {
      res.setHeader('Access-Control-Allow-Origin', '*');
      res.setHeader('Access-Control-Allow-Methods', 'GET, POST, OPTIONS');
      res.setHeader('Access-Control-Allow-Headers', '*');

      if (req.method === 'OPTIONS') {
        res.writeHead(204);
        res.end();
        return;
      }

      if (req.url === '/rambox-discovery' || req.url === '/discovery' || req.url === '/ping') {
        const payload = JSON.stringify({
          app: 'rambox',
          status: 'online',
          ip: getLocalIp(),
          allIps: getAllLocalIps(),
          port: port
        });
        res.writeHead(200, { 'Content-Type': 'application/json' });
        res.end(payload);
        return;
      }

      res.writeHead(200, { 'Content-Type': 'text/plain' });
      res.end('Rambox Signaling & Mirroring Server Running\n');
    });

    wss = new WebSocketServer({ server: httpServer });
    console.log(`[SignalingServer] HTTP & WebSocket running at ws://${getLocalIp()}:${port}`);

    httpServer.listen(port, '0.0.0.0', () => {
      console.log(`[SignalingServer] Listening on all network interfaces (0.0.0.0:${port})`);
    });

    wss.on('connection', (ws) => {
      let currentPass = null;
      let role = null;

      ws.on('message', (message, isBinary) => {
        const isRawBinary = isBinary || (Buffer.isBuffer(message) && message.length > 0 && message[0] !== 0x7B);
        if (isRawBinary) {
          const roomKey = currentPass || DEFAULT_PASSWORD;
          let room = rooms.get(roomKey);
          if (room) {
            if (role === 'host') {
              const target = room.clientSocket;
              if (target && target.readyState === WebSocket.OPEN) {
                target.send(message, { binary: true });
              }
            } else {
              if (room.hostSocket && room.hostSocket.readyState === WebSocket.OPEN) {
                room.hostSocket.send(message, { binary: true });
              }
            }
          }
          return;
        }

        try {
          const data = JSON.parse(message.toString());
          const { type, pin, password, payload, deviceId, deviceName } = data;
          const targetPass = (password || pin || DEFAULT_PASSWORD).toString().trim() || DEFAULT_PASSWORD;

          if (type === 'create-room') {
            currentPass = targetPass;
            role = 'host';
            let room = rooms.get(currentPass) || { hostSocket: null, clientSocket: null, clients: new Map() };
            room.hostSocket = ws;
            rooms.set(currentPass, room);
            ws.send(JSON.stringify({ type: 'room-created', password: currentPass, ip: getLocalIp() }));
          } else if (type === 'join-room') {
            currentPass = targetPass;
            role = 'client';
            const devId = deviceId || 'DEV-' + Math.random().toString(36).substring(2, 8).toUpperCase();
            ws.deviceId = devId;
            ws.deviceName = deviceName || 'Android Device';

            let room = rooms.get(currentPass) || { hostSocket: null, clientSocket: null, clients: new Map() };
            room.clientSocket = ws;
            rooms.set(currentPass, room);
            ws.send(JSON.stringify({ type: 'joined-success', password: currentPass, deviceId: devId }));
            if (room.hostSocket && room.hostSocket.readyState === WebSocket.OPEN) {
              room.hostSocket.send(JSON.stringify({ type: 'client-joined', deviceId: devId, deviceName: ws.deviceName }));
            }
          } else if (type === 'signal') {
            const room = rooms.get(currentPass || DEFAULT_PASSWORD);
            if (room) {
              if (role === 'host') {
                if (room.clientSocket && room.clientSocket.readyState === WebSocket.OPEN) {
                  room.clientSocket.send(JSON.stringify({ type: 'signal', password: currentPass, payload }));
                }
              } else {
                if (room.hostSocket && room.hostSocket.readyState === WebSocket.OPEN) {
                  room.hostSocket.send(JSON.stringify({ type: 'signal', password: currentPass, payload, deviceId: ws.deviceId }));
                }
              }
            }
          }
        } catch (err) {}
      });

      ws.on('close', () => {
        if (currentPass && rooms.has(currentPass)) {
          cleanupRoom(currentPass);
        }
      });
    });

    return { port, ip: getLocalIp() };
  } catch (err) {
    return { port, ip: getLocalIp(), error: err.message };
  }
}

if (process.argv[1] && (process.argv[1].endsWith('signaling-server.js') || process.argv[1].endsWith('server.js'))) {
  startSignalingServer(process.env.PORT || 8081);
}
