import AsyncStorage from '@react-native-async-storage/async-storage';
import { getApps, initializeApp, type FirebaseApp } from 'firebase/app';
import * as FirebaseAuth from 'firebase/auth';
import { initializeFirestore, type Firestore } from 'firebase/firestore';

import { config, firebaseConfigured } from './config';

type FirebaseHandles = { app: FirebaseApp; auth: FirebaseAuth.Auth; db: Firestore };

let handles: FirebaseHandles | null = null;

export class FirebaseNotConfiguredError extends Error {
  constructor() {
    super('Firebase não configurado. Preencha as variáveis EXPO_PUBLIC_FIREBASE_* no arquivo .env.');
  }
}

/**
 * Firebase no plano gratuito (Spark): Authentication anônima + Cloud Firestore.
 * O login anônimo não pede nada ao usuário; só serve para cada aparelho ver apenas o próprio histórico.
 */
export function getFirebase(): FirebaseHandles {
  if (!firebaseConfigured) throw new FirebaseNotConfiguredError();
  if (handles) return handles;

  const app = getApps()[0] ?? initializeApp(config.firebase);

  // getReactNativePersistence só existe no build React Native do Firebase Auth.
  const getRNPersistence = (FirebaseAuth as unknown as {
    getReactNativePersistence?: (storage: typeof AsyncStorage) => FirebaseAuth.Persistence;
  }).getReactNativePersistence;

  let auth: FirebaseAuth.Auth;
  try {
    auth = getRNPersistence
      ? FirebaseAuth.initializeAuth(app, { persistence: getRNPersistence(AsyncStorage) })
      : FirebaseAuth.getAuth(app);
  } catch {
    auth = FirebaseAuth.getAuth(app);
  }

  // Long polling é mais estável no React Native que o WebChannel padrão.
  const db = initializeFirestore(app, { experimentalForceLongPolling: true });

  handles = { app, auth, db };
  return handles;
}

/** Garante um usuário anônimo e devolve o uid. */
export async function ensureUser(): Promise<string> {
  const { auth } = getFirebase();
  await auth.authStateReady();
  if (auth.currentUser) return auth.currentUser.uid;
  const credential = await FirebaseAuth.signInAnonymously(auth);
  return credential.user.uid;
}
