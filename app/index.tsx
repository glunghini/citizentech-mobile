import { useFocusEffect, useRouter } from 'expo-router';
import { useCallback, useState } from 'react';
import { Linking, StyleSheet, Text, TextInput, View } from 'react-native';

import { useAssistant } from '../src/assistant';
import { config, firebaseConfigured, modeLabel } from '../src/config';
import { Badge, BigButton, Body, Card, colors, Screen, StatusLine, Title } from '../src/ui';

const STATUS_TEXT: Record<string, string> = {
  waiting: 'Esperando você tocar em “Próximo passo” no Chrome.',
  reading: 'Lendo a página…',
  requesting: 'Pensando na orientação…',
  showing: 'Orientação mostrada no Chrome.',
  error: 'Algo deu errado no último passo. Tente de novo pelo painel.',
};

export default function HomeScreen() {
  const router = useRouter();
  const { nativeAvailable, status, sync, refresh, start, stop, recognizeSpeech } = useAssistant();
  const [goal, setGoal] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [listening, setListening] = useState(false);

  useFocusEffect(
    useCallback(() => {
      refresh();
    }, [refresh]),
  );

  const state = status?.state;
  const active = state?.active ?? false;
  const serviceReady = !!status?.serviceEnabled && !!status?.serviceRunning;
  const realWithoutUrl = config.guidanceMode === 'real' && !config.apiBaseUrl;
  const canStart = nativeAvailable && serviceReady && goal.trim().length > 0 && !realWithoutUrl;

  async function handleSpeak() {
    setError(null);
    setListening(true);
    try {
      const text = await recognizeSpeech('Diga o que você quer fazer');
      if (text) setGoal(text.slice(0, 500));
    } catch {
      setError('Não consegui usar a fala agora. Você pode escrever o objetivo.');
    } finally {
      setListening(false);
    }
  }

  function handleStart() {
    setError(null);
    try {
      start(goal.trim());
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Não foi possível iniciar a ajuda.');
    }
  }

  function handleOpenDemo() {
    Linking.openURL(config.demoUrl).catch(() => setError('Não consegui abrir o endereço. Abra a página pelo Chrome.'));
  }

  if (!nativeAvailable) {
    return (
      <Screen>
        <Title>Versão incompleta</Title>
        <Card tone="warning">
          <Body>
            Este app precisa do código Android próprio e não funciona no Expo Go. Gere o development build com
            “npx expo run:android” e abra por ele.
          </Body>
        </Card>
      </Screen>
    );
  }

  return (
    <Screen>
      <Body>
        Eu mostro <Text style={styles.bold}>onde tocar</Text> na página aberta no Chrome e{' '}
        <Text style={styles.bold}>falo o próximo passo</Text>. Durante a ajuda, leio os textos e botões da tela, nunca o
        que você digita em campos. Quem toca e preenche é sempre você.
      </Body>

      <Card>
        <Title>Permissões</Title>
        <StatusLine
          ok={!!status?.serviceEnabled}
          okText="Leitura da tela (acessibilidade) ativada"
          badText="Leitura da tela (acessibilidade) desativada"
        />
        {status?.serviceEnabled && !status.serviceRunning ? (
          <Body muted>O Android ainda está ligando o serviço. Aguarde alguns segundos.</Body>
        ) : null}
        <StatusLine
          ok={!!status?.speechRecognitionAvailable}
          okText="Fala disponível para dizer o objetivo"
          badText="Fala indisponível: escreva o objetivo"
        />
        {!status?.serviceEnabled ? (
          <BigButton label="Ativar leitura da tela" onPress={() => router.push('/permissoes')} hint="Mostra o passo a passo" />
        ) : null}
      </Card>

      {active ? (
        <Card tone="info">
          <Title>Ajuda em andamento</Title>
          <Body>{STATUS_TEXT[state?.status ?? ''] ?? ''}</Body>
          {state?.message ? (
            <View style={styles.quote} accessible accessibilityLabel={`Última mensagem: ${state.message}`}>
              <Text style={styles.quoteText}>“{state.message}”</Text>
            </View>
          ) : null}
          {state?.completed ? <Badge label="O assistente indicou que a tarefa terminou" tone="success" /> : null}
          {state && !state.voiceAvailable ? <Body muted>Voz indisponível: as orientações aparecem no painel.</Body> : null}
          {config.demoUrl ? <BigButton label="Abrir página de demonstração" onPress={handleOpenDemo} variant="secondary" /> : null}
          <BigButton label="Encerrar ajuda" onPress={stop} variant="danger" hint="Para a voz, apaga o destaque e salva o histórico" />
        </Card>
      ) : (
        <Card>
          <Title>O que você quer fazer?</Title>
          <TextInput
            value={goal}
            onChangeText={setGoal}
            placeholder="Ex.: Quero agendar a renovação do meu RG"
            placeholderTextColor="#6B6B6B"
            multiline
            maxLength={500}
            style={styles.input}
            accessibilityLabel="Objetivo da ajuda"
            accessibilityHint="Escreva o que você quer fazer no site"
          />
          <Body muted>Não escreva CPF, senhas ou outros dados pessoais.</Body>
          {status?.speechRecognitionAvailable ? (
            <BigButton
              label={listening ? 'Ouvindo…' : 'Falar o objetivo'}
              onPress={handleSpeak}
              variant="secondary"
              disabled={listening}
            />
          ) : null}
          <BigButton
            label="Iniciar ajuda"
            onPress={handleStart}
            disabled={!canStart}
            hint="Depois, abra a página no Chrome e use o painel flutuante"
          />
          {!serviceReady ? <Body muted>Ative a leitura da tela para começar.</Body> : null}
          {realWithoutUrl ? <Body muted>Modo real sem endereço da API configurado.</Body> : null}
        </Card>
      )}

      {error ? (
        <Card tone="warning">
          <Body>{error}</Body>
        </Card>
      ) : null}

      <BigButton label="Histórico de ajudas" onPress={() => router.push('/historico')} variant="secondary" />
      <BigButton label="Como funciona" onPress={() => router.push('/sobre')} variant="secondary" />

      <View style={styles.diagnostic} accessible accessibilityLabel={`Diagnóstico: ${modeLabel()}`}>
        <Text style={styles.diagnosticText}>{modeLabel()}</Text>
        <Text style={styles.diagnosticText}>
          {firebaseConfigured ? 'Banco: Firebase Firestore' : 'Banco: Firebase não configurado (.env)'}
          {sync.pending > 0 ? ` · ${sync.pending} ajuda(s) aguardando para salvar` : ''}
        </Text>
        {sync.error ? <Text style={[styles.diagnosticText, { color: colors.danger }]}>{sync.error}</Text> : null}
      </View>
    </Screen>
  );
}

const styles = StyleSheet.create({
  bold: { fontWeight: '700' },
  input: {
    minHeight: 110,
    borderWidth: 2,
    borderColor: colors.primary,
    borderRadius: 12,
    padding: 14,
    fontSize: 21,
    color: colors.text,
    backgroundColor: '#FFFFFF',
    textAlignVertical: 'top',
  },
  quote: { borderLeftWidth: 4, borderLeftColor: colors.primary, paddingLeft: 12 },
  quoteText: { fontSize: 20, lineHeight: 28, color: colors.text, fontStyle: 'italic' },
  diagnostic: { paddingTop: 8, gap: 4 },
  diagnosticText: { fontSize: 15, color: colors.muted },
});
