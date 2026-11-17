import { Stack } from 'expo-router';
import { StatusBar } from 'expo-status-bar';

import { AssistantProvider } from '../src/assistant';
import { colors } from '../src/ui';

export default function RootLayout() {
  return (
    <AssistantProvider>
      <StatusBar style="light" />
      <Stack
        screenOptions={{
          headerStyle: { backgroundColor: colors.primary },
          headerTintColor: '#FFFFFF',
          headerTitleStyle: { fontSize: 22, fontWeight: '700' },
          contentStyle: { backgroundColor: colors.bg },
        }}
      >
        <Stack.Screen name="index" options={{ title: 'Assistente Cidadão' }} />
        <Stack.Screen name="permissoes" options={{ title: 'Permissões' }} />
        <Stack.Screen name="historico/index" options={{ title: 'Histórico de ajudas' }} />
        <Stack.Screen name="historico/[id]" options={{ title: 'Detalhes da ajuda' }} />
        <Stack.Screen name="sobre" options={{ title: 'Como funciona' }} />
      </Stack>
    </AssistantProvider>
  );
}
