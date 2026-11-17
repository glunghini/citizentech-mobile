import { useFocusEffect, useRouter } from 'expo-router';
import { useCallback, useState } from 'react';
import { ActivityIndicator, FlatList, Pressable, RefreshControl, StyleSheet, Text, View } from 'react-native';

import { useAssistant } from '../../src/assistant';
import { describeError, listSessions, type SessionRecord } from '../../src/sessions';
import { Badge, Body, Card, colors, formatDateTime } from '../../src/ui';

export default function HistoryScreen() {
  const router = useRouter();
  const { sync } = useAssistant();
  const [items, setItems] = useState<SessionRecord[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  const load = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      setItems(await listSessions());
    } catch (e) {
      setError(describeError(e));
    } finally {
      setLoading(false);
    }
  }, []);

  useFocusEffect(
    useCallback(() => {
      void load();
    }, [load, sync.lastSavedAt]),
  );

  return (
    <FlatList
      style={styles.list}
      contentContainerStyle={styles.content}
      data={items}
      keyExtractor={(item) => item.sessionId}
      refreshControl={<RefreshControl refreshing={loading} onRefresh={load} />}
      ListHeaderComponent={
        <View style={styles.header}>
          <Body muted>Cada ajuda encerrada fica salva no banco de dados. Puxe para baixo para atualizar.</Body>
          {sync.pending > 0 ? (
            <Card tone="warning">
              <Body>{sync.pending} ajuda(s) ainda não foram salvas. Elas serão enviadas quando houver conexão.</Body>
            </Card>
          ) : null}
          {error ? (
            <Card tone="warning">
              <Body>{error}</Body>
            </Card>
          ) : null}
        </View>
      }
      ListEmptyComponent={
        loading ? (
          <ActivityIndicator size="large" color={colors.primary} accessibilityLabel="Carregando" />
        ) : error ? null : (
          <Card>
            <Body>Nenhuma ajuda registrada ainda. Inicie uma ajuda na tela inicial.</Body>
          </Card>
        )
      }
      renderItem={({ item }) => {
        const successful = item.steps.filter((s) => s.result === 'success').length;
        return (
          <Pressable
            onPress={() => router.push({ pathname: '/historico/[id]', params: { id: item.sessionId } })}
            accessibilityRole="button"
            accessibilityLabel={`${item.goal}. ${formatDateTime(item.startedAt)}. ${successful} orientações.`}
            accessibilityHint="Abre os detalhes"
            style={({ pressed }) => [styles.item, pressed && { opacity: 0.85 }]}
          >
            <Text style={styles.goal} numberOfLines={3}>
              {item.goal}
            </Text>
            <Text style={styles.meta}>{formatDateTime(item.startedAt)}</Text>
            <View style={styles.badges}>
              <Badge label={`${successful} orientação(ões)`} tone="neutral" />
              {item.completed ? <Badge label="Concluída" tone="success" /> : <Badge label="Encerrada" tone="neutral" />}
              {item.mode === 'mock' ? <Badge label="Teste" tone="warning" /> : null}
            </View>
          </Pressable>
        );
      }}
    />
  );
}

const styles = StyleSheet.create({
  list: { flex: 1, backgroundColor: colors.bg },
  content: { padding: 20, gap: 14, paddingBottom: 40 },
  header: { gap: 12 },
  item: {
    backgroundColor: colors.card,
    borderRadius: 16,
    borderWidth: 1,
    borderColor: colors.border,
    padding: 18,
    gap: 8,
  },
  goal: { fontSize: 21, fontWeight: '700', color: colors.text, lineHeight: 28 },
  meta: { fontSize: 18, color: colors.muted },
  badges: { flexDirection: 'row', flexWrap: 'wrap', gap: 8 },
});
