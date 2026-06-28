import { writable, get, type Writable, type Readable } from 'svelte/store';
import { opusStream } from '../lib/OpusStream';

type CallState = 'idle' | 'calling' | 'in_call' | 'ended' | 'no_answer' | 'failed';

function getBaseUrl(): string {
  const wsUrl = (window as any).__WS_URL__;
  if (wsUrl) {
    return wsUrl.replace(/^ws:/, 'http:').replace(/^wss:/, 'https:').replace(/\/ws$/, '');
  }
  return window.location.origin;
}

const decoder = new TextDecoder();

function readUint32BE(buf: Uint8Array, offset: number): number {
  return ((buf[offset] << 24) | (buf[offset + 1] << 16) | (buf[offset + 2] << 8) | buf[offset + 3]) >>> 0;
}

class StreamParser {
  private buffer = new Uint8Array(0);

  push(data: Uint8Array, onControl: (msg: any) => void, onAudio: (payload: Uint8Array) => void) {
    const tmp = new Uint8Array(this.buffer.length + data.length);
    tmp.set(this.buffer);
    tmp.set(data, this.buffer.length);
    this.buffer = tmp;

    while (this.buffer.length >= 5) {
      const type = this.buffer[0];
      const length = readUint32BE(this.buffer, 1);
      const frameSize = 5 + length;
      if (this.buffer.length < frameSize) break;

      const payload = this.buffer.slice(5, frameSize);
      if (type === 0) {
        try { onControl(JSON.parse(decoder.decode(payload))); } catch {}
      } else if (type === 1) {
        onAudio(payload);
      }
      this.buffer = this.buffer.slice(frameSize);
    }
  }

  clear() { this.buffer = new Uint8Array(0); }
}

class CallClient {
  private abortController: AbortController | null = null;
  private reader: ReadableStreamDefaultReader<Uint8Array> | null = null;
  private callTimeout: ReturnType<typeof setTimeout> | null = null;
  private recording = false;
  private _callEnded = false;
  private parser = new StreamParser();

  private callStateWritable: Writable<CallState> = writable('idle');
  private errorTextWritable: Writable<string> = writable('');

  callState: Readable<CallState> = this.callStateWritable;
  errorText: Readable<string> = this.errorTextWritable;

  private baseUrl = getBaseUrl();

  async startCall(): Promise<void> {
    this.errorTextWritable.set('');
    this._callEnded = false;
    this.parser.clear();
    this.abortController = new AbortController();
    this.callStateWritable.set('calling');

    try {
      const response = await fetch(`${this.baseUrl}/caller`, {
        method: 'GET',
        signal: this.abortController.signal
      });

      if (!response.ok) {
        const errText = await response.text().catch(() => 'Call failed');
        this.errorTextWritable.set(errText);
        this.callStateWritable.set('failed');
        return;
      }

      this.reader = response.body!.getReader();
      this.readLoop();

      this.callTimeout = setTimeout(() => {
        if (!this._callEnded && get(this.callStateWritable) === 'calling') {
          this.errorTextWritable.set('No answer. Try again.');
          this.endCall();
          this.callStateWritable.set('no_answer');
        }
      }, 30000);
    } catch (err: any) {
      if (err.name === 'AbortError') return;
      this.errorTextWritable.set('Connection failed. Try again.');
      this.callStateWritable.set('failed');
    }
  }

  private async readLoop() {
    while (this.reader && !this._callEnded) {
      try {
        const { done, value } = await this.reader.read();
        if (done || this._callEnded) break;
        if (value.length === 0) continue;

        this.parser.push(value, (msg) => this.handleMessage(msg), (payload) => {
          opusStream.playPacket(payload).catch((e: any) => console.error('playPacket error:', e));
        });
      } catch (err: any) {
        if (err.name === 'AbortError' || this._callEnded) break;
        console.error('Read error:', err);
        break;
      }
    }
    if (!this._callEnded) {
      this.callStateWritable.set('ended');
      opusStream.stop();
    }
  }

  private handleMessage(msg: any) {
    switch (msg.type) {
      case 'call_answered':
        if (this.callTimeout) clearTimeout(this.callTimeout);
        this.callStateWritable.set('in_call');
        opusStream.initStream().catch((e: any) => console.error('initStream error:', e));
        break;
      case 'error':
        if (this.callTimeout) clearTimeout(this.callTimeout);
        this.callStateWritable.set('failed');
        this.errorTextWritable.set(msg.message || 'Call failed');
        break;
      case 'hangup':
        if (this.callTimeout) clearTimeout(this.callTimeout);
        this.callStateWritable.set('ended');
        opusStream.stop();
        break;
    }
  }

  async startTransmitting(): Promise<void> {
    if (get(this.callStateWritable) !== 'in_call') return;
    if (this.recording) return;
    this.recording = true;

    try {
      const packet = await opusStream.startRecording();
      if (this._callEnded) return;

      const response = await fetch(`${this.baseUrl}/caller`, {
        method: 'POST',
        body: packet,
        headers: { 'Content-Type': 'application/octet-stream' }
      });

      if (!response.ok) {
        console.error('Transmit failed:', await response.text().catch(() => ''));
      }
    } catch (e: any) {
      if (e.name === 'AbortError') return;
      console.error('PTT recording failed:', e);
    }
    this.recording = false;
  }

  stopTransmitting(): void {
    opusStream.stopRecording();
  }

  endCall(): void {
    if (this.callTimeout) clearTimeout(this.callTimeout);
    this._callEnded = true;

    fetch(`${this.baseUrl}/caller`, { method: 'DELETE' }).catch(() => {});

    this.abortController?.abort();
    this.reader = null;
    this.parser.clear();
    this.callStateWritable.set('ended');
    opusStream.stop();
  }

  resetCall(): void {
    this._callEnded = false;
    this.callStateWritable.set('idle');
    this.errorTextWritable.set('');
    if (this.callTimeout) clearTimeout(this.callTimeout);
    opusStream.stop();
  }
}

export const callClient = new CallClient();
