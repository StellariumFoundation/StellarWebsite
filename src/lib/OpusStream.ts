import { writable, type Writable, type Readable } from 'svelte/store';

const BITRATE = 96000;
const MIME_TYPE = 'audio/webm;codecs=opus';

class OpusStream {
  private mediaRecorder: MediaRecorder | null = null;
  private mediaStream: MediaStream | null = null;
  private resolveCurrent: ((data: Uint8Array) => void) | null = null;

  private initializedWritable: Writable<boolean> = writable(false);
  private recordingWritable: Writable<boolean> = writable(false);

  initialized: Readable<boolean> = this.initializedWritable;
  recording: Readable<boolean> = this.recordingWritable;

  init(): void {
    this.initializedWritable.set(true);
  }

  async initStream(): Promise<void> {
    if (this.mediaStream) return;
    if (!MediaRecorder.isTypeSupported(MIME_TYPE)) {
      throw new Error(`${MIME_TYPE} not supported`);
    }
    this.mediaStream = await navigator.mediaDevices.getUserMedia({
      audio: { echoCancellation: true, noiseSuppression: true, autoGainControl: true }
    });
  }

  async startRecording(): Promise<Uint8Array> {
    return new Promise((resolve, reject) => {
      if (!this.mediaStream) {
        reject(new Error('Stream not initialized. Call initStream() first.'));
        return;
      }

      this.mediaRecorder = new MediaRecorder(this.mediaStream, {
        mimeType: MIME_TYPE,
        audioBitsPerSecond: BITRATE
      });

      this.resolveCurrent = resolve;

      this.mediaRecorder.onstart = () => {
        this.recordingWritable.set(true);
      };

      this.mediaRecorder.ondataavailable = (event) => {
        if (!event.data || event.data.size === 0) return;
        event.data.arrayBuffer().then((buffer) => {
          this.resolveCurrent?.(new Uint8Array(buffer));
          this.resolveCurrent = null;
        });
      };

      this.mediaRecorder.start();
    });
  }

  stopRecording(): void {
    if (this.mediaRecorder && this.mediaRecorder.state !== 'inactive') {
      this.mediaRecorder.stop();
    }
    this.mediaRecorder = null;
    this.recordingWritable.set(false);
  }

  releaseStream(): void {
    this.stopRecording();
    if (this.mediaStream) {
      this.mediaStream.getTracks().forEach(t => t.stop());
      this.mediaStream = null;
    }
    this.initializedWritable.set(false);
  }

  async playPacket(packet: Uint8Array): Promise<void> {
    const blob = new Blob([packet], { type: MIME_TYPE });
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
