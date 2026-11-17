import { useLocalSearchParams } from 'expo-router';
import { useEffect, useState } from 'react';
import { ActivityIndicator, StyleSheet, Text, View } from 'react-native';

import { describeError, getSession, type SessionRecord, type StepRecord } from '../../src/sessions';
import { Badge, Body, Card, colors, formatDateTime, Screen, Title } from '../../src/ui';

const ACTION_LABEL: Record<string, string> = {
  highlight: 'Destacou um elemento',
  explain: 'Explicou sem destacar',
  ask: 'Fez uma pergunta',
  complete: 'Indicou conclusão',
};

const END_REASON: Record<string, string> = {
  user: 'Encerrada pelo painel no Chrome',
  app: 'Encerrada pelo aplicativo',
  screen_off: 'Encerrada ao bloquear a tela',
  service_stopped: 'Serviço de acessibilidade desligado',
  restarted: 'Substituída por uma nova ajuda',
};

function stepTitle(step: StepRecord): string {
  if (step.result === 'failure') return `Falha${step.errorCode ? ` (${step.errorCode})` : ''}`;
  const label = step.action ? (ACTION_LABEL[step.action] ?? step.action) : 'Orientação';
  return step.result === 'discarded' ? `${label} — descartada porque a página mudou` : label;
}

export default function SessionDetailScreen() {
  const { id } = useLocalSearchParams<{ id: string }>();
  const [session, setSession] = useState<SessionRecord | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    let alive = true;
    (async () => {
      try {
        const data = await getSession(String(id));
        if (alive) setSession(data);
      } catch (e) {
        if (alive) setError(describeError(e));
      } finally {
        if (alive) setLoading(false);
      }
    })();
    return () => {
      alive = false;
    };
  }, [id]);

  if (loading) {
    return (
      <Screen>
        <ActivityIndicator size="large" color={colors.primary} accessibilityLabel="Carregando" />
      </Screen>
    );
  }

  if (!session) {
    return (
      <Screen>
        <Card tone="warning">
          <Body>{error ?? 'Não encontrei esta ajuda.'}</Body>
        </Card>
      </Screen>
    );
  }

  return (
    <Screen>
      <Title>{session.goal}</Title>
      <Card>
        <Body>Início: {formatDateTime(session.startedAt)}</Body>
        <Body>Fim: {formatDateTime(session.endedAt)}</Body>
        <Body>{END_REASON[session.endReason] ?? session.endReason}</Body>
        <View style={styles.badges}>
          {session.completed ? <Badge label="Concluída" tone="success" /> : <Badge label="Não concluída" tone="neutral" />}
          <Badge label={session.mode === 'mock' ? 'Respostas de teste' : 'API real'} tone={session.mode === 'mock' ? 'warning' : 'neutral'} />
        </View>
        <Body muted>
          “Concluída” quer dizer que o assistente indicou o fim do passo a passo, não que um serviço real foi feito.
        </Body>
        <Text style={styles.small} selectable>
          sessionId: {session.sessionId}
        </Text>
      </Card>

      <Title>Passos ({session.steps.length})</Title>
      {session.steps.length === 0 ? (
        <Card>
          <Body>Nenhum passo foi pedido nesta ajuda.</Body>
        </Card>
      ) : (
        session.steps.map((step, index) => (
          <Card key={`${step.requestId ?? 'local'}-${index}`}>
            <Text style={[styles.stepTitle, step.result === 'failure' && { color: colors.danger }]}>
              {index + 1}. {stepTitle(step)}
            </Text>
            {step.instruction ? <Body>“{step.instruction}”</Body> : null}
            <Text style={styles.small}>{formatDateTime(step.at)}</Text>
            {step.requestId ? (
              <Text style={styles.small} selectable>
                requestId: {step.requestId}
              </Text>
            ) : null}
          </Card>
        ))
      )}
    </Screen>
  );
}

const styles = StyleSheet.create({
  badges: { flexDirection: 'row', flexWrap: 'wrap', gap: 8 },
  stepTitle: { fontSize: 20, fontWeight: '700', color: colors.text },
  small: { fontSize: 15, color: colors.muted },
});
