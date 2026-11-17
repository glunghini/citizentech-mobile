import { createContext, useCallback, useContext, useEffect, useMemo, useRef, useState, type ReactNode } from 'react';
import { AppState } from 'react-native';

import CitizenAssist, { type AssistStatus } from '../modules/citizen-assist';
import { config } from './config';
import { describeError, parseSessionRecord, saveSession, type SessionRecord } from './sessions';

type SyncInfo = { pending: number; error: string | null; lastSavedAt: number | null };

type AssistantContextValue = {
  /** false quando o app roda sem o código nativo (ex.: Expo Go). */
  nativeAvailable: boolean;
  status: AssistStatus | null;
  sync: SyncInfo;
  refresh: () => void;
  start: (goal: string) => string;
  stop: () => void;
  recognizeSpeech: (prompt: string) => Promise<string | null>;
  openAccessibilitySettings: () => void;
};

const AssistantContext = createContext<AssistantContextValue | null>(null);

/**
 * Espelha o estado do serviço nativo e salva no Firestore cada sessão encerrada.
 * Se a gravação falhar (sem internet, Firebase não configurado), o resumo fica pendente
 * e é reenviado quando o app volta ao primeiro plano.
 */
export function AssistantProvider({ children }: { children: ReactNode }) {
  const [status, setStatus] = useState<AssistStatus | null>(() => CitizenAssist?.getStatus() ?? null);
  const [sync, setSync] = useState<SyncInfo>({ pending: 0, error: null, lastSavedAt: null });
  const pending = useRef<SessionRecord[]>([]);
  const syncing = useRef(false);

  const refresh = useCallback(() => {
    if (CitizenAssist) setStatus(CitizenAssist.getStatus());
  }, []);

  const flush = useCallback(async () => {
    if (!CitizenAssist || syncing.current) return;
    syncing.current = true;
    try {
      for (const raw of CitizenAssist.consumeFinishedSessions()) {
        try {
          pending.current.push(parseSessionRecord(raw));
        } catch {
          // registro corrompido: descartado
        }
      }
      while (pending.current.length > 0) {
        await saveSession(pending.current[0]);
        pending.current.shift();
        setSync((s) => ({ ...s, lastSavedAt: Date.now() }));
      }
      setSync((s) => ({ ...s, pending: 0, error: null }));
    } catch (error) {
      setSync((s) => ({ ...s, pending: pending.current.length, error: describeError(error) }));
    } finally {
      syncing.current = false;
    }
  }, []);

  useEffect(() => {
    const module = CitizenAssist;
    if (!module) return;
    const stateSub = module.addListener('onStateChange', (state) => {
      setStatus((prev) => (prev ? { ...prev, serviceRunning: state.serviceRunning, state } : module.getStatus()));
    });
    const endedSub = module.addListener('onSessionEnded', () => {
      void flush();
    });
    const appSub = AppState.addEventListener('change', (next) => {
      if (next === 'active') {
        refresh();
        void flush();
      }
    });
    void flush();
    return () => {
      stateSub.remove();
      endedSub.remove();
      appSub.remove();
    };
  }, [flush, refresh]);

  const value = useMemo<AssistantContextValue>(
    () => ({
      nativeAvailable: CitizenAssist != null,
      status,
      sync,
      refresh,
      start: (goal: string) => {
        if (!CitizenAssist) throw new Error('Módulo nativo indisponível. Use o development build.');
        const id = CitizenAssist.startSession(goal, config.guidanceMode, config.apiBaseUrl);
        refresh();
        return id;
      },
      stop: () => CitizenAssist?.stopSession(),
      recognizeSpeech: async (prompt: string) => (CitizenAssist ? CitizenAssist.recognizeSpeech(prompt) : null),
      openAccessibilitySettings: () => CitizenAssist?.openAccessibilitySettings(),
    }),
    [status, sync, refresh],
  );

  return <AssistantContext.Provider value={value}>{children}</AssistantContext.Provider>;
}

export function useAssistant(): AssistantContextValue {
  const ctx = useContext(AssistantContext);
  if (!ctx) throw new Error('useAssistant precisa estar dentro de AssistantProvider');
  return ctx;
}
