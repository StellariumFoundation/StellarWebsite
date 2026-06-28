import { writable, type Writable, type Readable } from 'svelte/store';

const BITRATE = 96000;
const PREFERRED_TYPES = ['audio/webm;codecs=opus', 'audio/webm'];

class OpusStream {
  private mediaRecorder: MediaRecorder | null = null;
  private mediaStream: MediaStream | null = null;
  private resolveCurrent: ((data: Uint8Array) => void) | null = null;

  private selectedMimeType = '';

  private initializedWritable: Writable<boolean> = writable(false);
  private recordingWritable: Writable<boolean> = writable(false);
  private micBlockedWritable: Writable<boolean> = writable(false);
  private micHintWritable: Writable<string> = writable('');

  initialized: Readable<boolean> = this.initializedWritable;
  recording: Readable<boolean> = this.recordingWritable;
  micBlocked: Readable<boolean> = this.micBlockedWritable;
  micHint: Readable<string> = this.micHintWritable;

  init(): void {
    this.initializedWritable.set(true);
  }

  private isTorBrowser(): boolean {
    try {
      return (window as any).chrome === undefined &&
        navigator.userAgent.includes('Firefox') &&
        navigator.userAgent.includes('Tor');
    } catch { return false; }
  }

  async initStream(): Promise<void> {
    if (this.mediaStream) return;

    const supported = PREFERRED_TYPES.find(t => MediaRecorder.isTypeSupported(t));
    if (!supported) {
      this.micBlockedWritable.set(true);
      this.micHintWritable.set('Your browser does not support audio recording.');
      return;
    }
    this.selectedMimeType = supported;

    if (typeof navigator.mediaDevices?.getUserMedia !== 'function') {
      this.micBlockedWritable.set(true);
      if (this.isTorBrowser()) {
        this.micHintWritable.set('Click the shield icon in the URL bar → set Security Level to "Standard" to enable microphone access.');
      } else if (typeof navigator.mediaDevices === 'undefined') {
        this.micHintWritable.set('Microphone access is blocked by your browser. Use HTTPS and a supported browser (Chrome, Firefox, Edge).');
      } else {
        this.micHintWritable.set('Microphone API unavailable in this browser.');
      }
      return;
    }

    try {
      this.mediaStream = await navigator.mediaDevices.getUserMedia({
        audio: { echoCancellation: true, noiseSuppression: true, autoGainControl: true }
      });
    } catch (e: any) {
      this.micBlockedWritable.set(true);
      if (e.name === 'NotAllowedError' || e.name === 'PermissionDeniedError') {
        this.micHintWritable.set('Microphone permission denied. Allow mic access in your browser settings, then refresh.');
      } else {
        this.micHintWritable.set('Could not access microphone: ' + e.message);
      }
      return;
    }

    this.mediaRecorder = new MediaRecorder(this.mediaStream, {
      mimeType: this.selectedMimeType,
      audioBitsPerSecond: BITRATE
    });

    this.mediaRecorder.ondataavailable = (event) => {
      if (!event.data || event.data.size === 0) return;
      event.data.arrayBuffer().then((buffer) => {
        this.resolveCurrent?.(new Uint8Array(buffer));
        this.resolveCurrent = null;
      });
    };
  }

  async startRecording(): Promise<Uint8Array> {
    return new Promise((resolve, reject) => {
      if (!this.mediaRecorder || !this.mediaStream) {
        reject(new Error('Not initialized. Call initStream() first.'));
        return;
      }
      if (this.mediaRecorder.state === 'recording') {
        reject(new Error('Already recording'));
        return;
      }

      this.resolveCurrent = resolve;
      this.mediaRecorder.start();
      this.recordingWritable.set(true);
    });
  }

  stopRecording(): void {
    if (this.mediaRecorder && this.mediaRecorder.state !== 'inactive') {
      this.mediaRecorder.stop();
    }
    this.recordingWritable.set(false);
  }

  releaseStream(): void {
    this.stopRecording();
    this.mediaRecorder = null;
    if (this.mediaStream) {
      this.mediaStream.getTracks().forEach(t => t.stop());
      this.mediaStream = null;
    }
    this.initializedWritable.set(false);
  }

  async playPacket(packet: Uint8Array): Promise<void> {
    const blob = new Blob([packet], { type: this.selectedMimeType || PREFERRED_TYPES[0] });
    const url = URL.createObjectURL(blob);
    const audio = new Audio(url);
    audio.onended = () => URL.revokeObjectURL(url);
    await audio.play();
  }

  stop(): void {
    this.releaseStream();
  }

  free(): void {
    this.releaseStream();
  }
}

export const opusStream = new OpusStream();
