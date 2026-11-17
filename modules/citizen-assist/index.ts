import { NativeModule, requireOptionalNativeModule } from 'expo';

export type GuidanceMode = 'mock' | 'real';

export type AssistStatusName = 'idle' | 'waiting' | 'reading' | 'requesting' | 'showing' | 'error';

/** Estado espelhado do serviço nativo. Strings vazias significam "nenhum". */
export type AssistState = {
  active: boolean;
  serviceRunning: boolean;
  sessionId: string;
  mode: string;
  status: AssistStatusName;
  message: string;
  lastAction: string;
  awaitingAnswer: boolean;
  voiceAvailable: boolean;
  completed: boolean;
  stepCount: number;
};

export type AssistStatus = {
  /** O usuário ligou o serviço nas configurações de acessibilidade. */
  serviceEnabled: boolean;
  /** O Android já conectou o serviço (pode levar um instante depois de ligar). */
  serviceRunning: boolean;
  speechRecognitionAvailable: boolean;
  state: AssistState;
};

type CitizenAssistEvents = {
  onStateChange(state: AssistState): void;
  onSessionEnded(event: { sessionId: string; reason: string }): void;
};

declare class CitizenAssistModule extends NativeModule<CitizenAssistEvents> {
  getStatus(): AssistStatus;
  openAccessibilitySettings(): void;
  /** Devolve o sessionId (UUID). Lança erro se o serviço não estiver ativo. */
  startSession(goal: string, mode: GuidanceMode, baseUrl: string): string;
  stopSession(): void;
  /** Resumos JSON das sessões encerradas ainda não salvas no banco. */
  consumeFinishedSessions(): string[];
  /** Texto reconhecido ou null se a pessoa cancelou. */
  recognizeSpeech(prompt: string): Promise<string | null>;
}

/**
 * null quando o código nativo não está no app (por exemplo, no Expo Go).
 * Este projeto precisa de development build: `npx expo run:android`.
 */
const CitizenAssist = requireOptionalNativeModule<CitizenAssistModule>('CitizenAssist');

export default CitizenAssist;
