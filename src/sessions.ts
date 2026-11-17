import { collection, doc, getDoc, getDocs, limit, query, serverTimestamp, setDoc, where } from 'firebase/firestore';

import { ensureUser, getFirebase } from './firebase';

export type GuidanceAction = 'highlight' | 'explain' | 'ask' | 'complete';

export type StepRecord = {
  requestId: string | null;
  at: number;
  action: GuidanceAction | null;
  instruction: string | null;
  /** success = orientação válida exibida; discarded = a página mudou antes de exibir; failure = erro. */
  result: 'success' | 'failure' | 'discarded';
  errorCode: string | null;
};

export type SessionRecord = {
  sessionId: string;
  goal: string;
  mode: 'mock' | 'real';
  startedAt: number;
  endedAt: number;
  endReason: string;
  /** O assistente chegou à ação "complete". Não significa serviço público concluído de verdade. */
  completed: boolean;
  steps: StepRecord[];
};

const COLLECTION = 'sessions';

/** Converte o JSON vindo do módulo nativo, rejeitando formatos inesperados. */
export function parseSessionRecord(raw: string): SessionRecord {
  const data = JSON.parse(raw) as Partial<SessionRecord>;
  if (typeof data.sessionId !== 'string' || typeof data.goal !== 'string' || !Array.isArray(data.steps)) {
    throw new Error('Registro de sessão inválido');
  }
  return {
    sessionId: data.sessionId,
    goal: data.goal.slice(0, 500),
    mode: data.mode === 'real' ? 'real' : 'mock',
    startedAt: Number(data.startedAt) || Date.now(),
    endedAt: Number(data.endedAt) || Date.now(),
    endReason: String(data.endReason ?? 'user'),
    completed: Boolean(data.completed),
    steps: data.steps.slice(0, 100) as StepRecord[],
  };
}

/** Grava a sessão encerrada. O ID do documento é o próprio sessionId. */
export async function saveSession(record: SessionRecord): Promise<void> {
  const uid = await ensureUser();
  const { db } = getFirebase();
  await setDoc(doc(db, COLLECTION, record.sessionId), {
    ...record,
    uid,
    savedAt: serverTimestamp(),
  });
}

export async function listSessions(): Promise<SessionRecord[]> {
  const uid = await ensureUser();
  const { db } = getFirebase();
  // Só filtro por uid (sem orderBy) para não exigir índice composto; a ordenação é feita aqui.
  const snap = await getDocs(query(collection(db, COLLECTION), where('uid', '==', uid), limit(100)));
  return snap.docs
    .map((d) => d.data() as SessionRecord)
    .sort((a, b) => b.startedAt - a.startedAt);
}

export async function getSession(sessionId: string): Promise<SessionRecord | null> {
  await ensureUser();
  const { db } = getFirebase();
  try {
    const snap = await getDoc(doc(db, COLLECTION, sessionId));
    return snap.exists() ? (snap.data() as SessionRecord) : null;
  } catch {
    return null;
  }
}

export function describeError(error: unknown): string {
  if (error instanceof Error && error.message.startsWith('Firebase não configurado')) return error.message;
  const code = (error as { code?: string } | null)?.code ?? '';
  if (code.includes('permission-denied')) return 'O banco recusou o acesso. Confira as regras do Firestore e o login anônimo.';
  if (code.includes('unavailable') || code.includes('network')) return 'Sem conexão com o banco. Tente de novo com internet.';
  if (code.includes('operation-not-allowed') || code.includes('admin-restricted')) {
    return 'Ative o login Anônimo no Firebase Authentication.';
  }
  return 'Não foi possível acessar o banco de dados agora.';
}
