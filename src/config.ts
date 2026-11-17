import type { GuidanceMode } from '../modules/citizen-assist';

/**
 * Configuração de desenvolvimento lida do arquivo .env (veja .env.example).
 * Não existe tela de configurações para o usuário final.
 * As variáveis EXPO_PUBLIC_* precisam ser lidas exatamente assim (process.env.NOME) para o Expo incluí-las.
 */
const rawMode = process.env.EXPO_PUBLIC_GUIDANCE_MODE;

export const config = {
  guidanceMode: (rawMode === 'real' ? 'real' : 'mock') as GuidanceMode,
  apiBaseUrl: (process.env.EXPO_PUBLIC_API_BASE_URL ?? '').trim().replace(/\/+$/, ''),
  demoUrl: (process.env.EXPO_PUBLIC_DEMO_URL ?? '').trim(),
  firebase: {
    apiKey: process.env.EXPO_PUBLIC_FIREBASE_API_KEY ?? '',
    authDomain: process.env.EXPO_PUBLIC_FIREBASE_AUTH_DOMAIN ?? '',
    projectId: process.env.EXPO_PUBLIC_FIREBASE_PROJECT_ID ?? '',
    storageBucket: process.env.EXPO_PUBLIC_FIREBASE_STORAGE_BUCKET ?? '',
    messagingSenderId: process.env.EXPO_PUBLIC_FIREBASE_MESSAGING_SENDER_ID ?? '',
    appId: process.env.EXPO_PUBLIC_FIREBASE_APP_ID ?? '',
  },
};

export const firebaseConfigured = Boolean(config.firebase.apiKey && config.firebase.projectId && config.firebase.appId);

/** Texto curto para o diagnóstico: deixa claro quando as respostas são simuladas. */
export function modeLabel(): string {
  if (config.guidanceMode === 'mock') return 'Modo de teste: respostas simuladas (sem IA)';
  const host = config.apiBaseUrl.replace(/^https?:\/\//, '');
  return `Modo real: API ${host || '(baseUrl não configurada)'}`;
}
