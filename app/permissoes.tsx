import { useFocusEffect } from 'expo-router';
import { useCallback } from 'react';
import { StyleSheet, Text, View } from 'react-native';

import { useAssistant } from '../src/assistant';
import { BigButton, Body, Card, colors, Screen, StatusLine, Title } from '../src/ui';

const STEPS = [
  'Toque em “Abrir configurações” aqui embaixo.',
  'Procure “Apps instalados” ou “Serviços baixados” (o nome muda conforme o celular).',
  'Toque em “CitizenTech Assistente” e ative a chave.',
  'Confirme no aviso do Android e volte para este app.',
];

export default function PermissionsScreen() {
  const { status, refresh, openAccessibilitySettings } = useAssistant();

  // Atualiza ao voltar das configurações (o provider também atualiza quando o app volta ao primeiro plano).
  useFocusEffect(
    useCallback(() => {
      refresh();
    }, [refresh]),
  );

  return (
    <Screen>
      <Title>Leitura da tela</Title>
      <Body>
        Para mostrar onde tocar, o app precisa da permissão de acessibilidade. O Android não deixa o app ligar isso
        sozinho: é você quem ativa.
      </Body>

      <Card>
        <StatusLine ok={!!status?.serviceEnabled} okText="Permissão ativada" badText="Permissão desativada" />
        {STEPS.map((step, i) => (
          <View key={step} style={styles.step}>
            <Text style={styles.stepNumber} accessibilityElementsHidden>
              {i + 1}
            </Text>
            <Body>{step}</Body>
          </View>
        ))}
        <BigButton label="Abrir configurações" onPress={openAccessibilitySettings} />
      </Card>

      <Card tone="warning">
        <Title>Não aparece ou está bloqueado?</Title>
        <Body>
          No Android 13 ou mais novo, apps instalados por arquivo APK podem ficar com essa opção bloqueada. Abra
          Configurações → Apps → CitizenTech Assistente → menu ⋮ → “Permitir configurações restritas” e tente de novo.
        </Body>
      </Card>

      <Card tone="info">
        <Title>O que o app lê</Title>
        <Body>• Só a página aberta no Chrome, e só enquanto a ajuda estiver ativa.</Body>
        <Body>• Textos, botões e nomes dos campos. Nunca senhas nem o que você digita.</Body>
        <Body>• O app nunca toca na tela por você.</Body>
        <Body muted>
          Para falar o objetivo, o app usa o reconhecimento de voz do próprio celular, só quando você toca em “Falar”. Por
          isso ele não pede permissão de microfone.
        </Body>
      </Card>
    </Screen>
  );
}

const styles = StyleSheet.create({
  step: { flexDirection: 'row', gap: 12, alignItems: 'flex-start' },
  stepNumber: {
    width: 34,
    height: 34,
    borderRadius: 17,
    backgroundColor: colors.primary,
    color: '#FFFFFF',
    fontSize: 18,
    fontWeight: '700',
    textAlign: 'center',
    lineHeight: 34,
  },
});
