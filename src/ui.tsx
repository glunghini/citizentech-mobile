import type { ReactNode } from 'react';
import { Pressable, ScrollView, StyleSheet, Text, View, type StyleProp, type ViewStyle } from 'react-native';

/** Cores com contraste alto (texto escuro em fundo claro, botões com texto branco em azul/vermelho escuros). */
export const colors = {
  bg: '#F4F4EF',
  card: '#FFFFFF',
  text: '#111111',
  muted: '#3A3A3A',
  border: '#BDBDB5',
  primary: '#0B4F8A',
  danger: '#B3261E',
  success: '#1B5E20',
  warning: '#6B4500',
  warningBg: '#FFF3CD',
  infoBg: '#E3F0FB',
};

export function Screen({ children }: { children: ReactNode }) {
  return (
    <ScrollView style={styles.screen} contentContainerStyle={styles.screenContent} keyboardShouldPersistTaps="handled">
      {children}
    </ScrollView>
  );
}

export function Title({ children }: { children: ReactNode }) {
  return (
    <Text style={styles.title} accessibilityRole="header">
      {children}
    </Text>
  );
}

export function Body({ children, muted }: { children: ReactNode; muted?: boolean }) {
  return <Text style={[styles.body, muted && { color: colors.muted }]}>{children}</Text>;
}

export function Card({ children, tone = 'default', style }: { children: ReactNode; tone?: 'default' | 'info' | 'warning'; style?: StyleProp<ViewStyle> }) {
  const bg = tone === 'info' ? colors.infoBg : tone === 'warning' ? colors.warningBg : colors.card;
  return <View style={[styles.card, { backgroundColor: bg }, style]}>{children}</View>;
}

type ButtonVariant = 'primary' | 'danger' | 'secondary';

export function BigButton({
  label,
  onPress,
  variant = 'primary',
  disabled,
  hint,
}: {
  label: string;
  onPress: () => void;
  variant?: ButtonVariant;
  disabled?: boolean;
  hint?: string;
}) {
  const bg = variant === 'primary' ? colors.primary : variant === 'danger' ? colors.danger : colors.card;
  const fg = variant === 'secondary' ? colors.primary : '#FFFFFF';
  return (
    <Pressable
      onPress={onPress}
      disabled={disabled}
      accessibilityRole="button"
      accessibilityLabel={label}
      accessibilityHint={hint}
      accessibilityState={{ disabled: !!disabled }}
      style={({ pressed }) => [
        styles.button,
        { backgroundColor: bg, borderColor: variant === 'secondary' ? colors.primary : bg },
        disabled && styles.buttonDisabled,
        pressed && !disabled && { opacity: 0.85 },
      ]}
    >
      <Text style={[styles.buttonText, { color: fg }]}>{label}</Text>
    </Pressable>
  );
}

export function Badge({ label, tone }: { label: string; tone: 'success' | 'neutral' | 'warning' }) {
  const bg = tone === 'success' ? colors.success : tone === 'warning' ? colors.warning : '#4A4A4A';
  return (
    <View style={[styles.badge, { backgroundColor: bg }]}>
      <Text style={styles.badgeText}>{label}</Text>
    </View>
  );
}

export function StatusLine({ ok, okText, badText }: { ok: boolean; okText: string; badText: string }) {
  return (
    <View style={styles.statusLine} accessible accessibilityLabel={ok ? okText : badText}>
      <Text style={[styles.statusIcon, { color: ok ? colors.success : colors.danger }]}>{ok ? '✓' : '✕'}</Text>
      <Text style={styles.body}>{ok ? okText : badText}</Text>
    </View>
  );
}

export function formatDateTime(ms: number): string {
  const d = new Date(ms);
  const pad = (n: number) => String(n).padStart(2, '0');
  return `${pad(d.getDate())}/${pad(d.getMonth() + 1)}/${d.getFullYear()} às ${pad(d.getHours())}:${pad(d.getMinutes())}`;
}

const styles = StyleSheet.create({
  screen: { flex: 1, backgroundColor: colors.bg },
  screenContent: { padding: 20, gap: 16, paddingBottom: 40 },
  title: { fontSize: 26, fontWeight: '700', color: colors.text, lineHeight: 34 },
  body: { fontSize: 20, lineHeight: 28, color: colors.text, flexShrink: 1 },
  card: { borderRadius: 16, padding: 18, gap: 12, borderWidth: 1, borderColor: colors.border },
  button: {
    minHeight: 60,
    borderRadius: 14,
    borderWidth: 2,
    paddingHorizontal: 18,
    paddingVertical: 14,
    alignItems: 'center',
    justifyContent: 'center',
  },
  buttonDisabled: { opacity: 0.45 },
  buttonText: { fontSize: 21, fontWeight: '700', textAlign: 'center' },
  badge: { alignSelf: 'flex-start', borderRadius: 999, paddingHorizontal: 12, paddingVertical: 4 },
  badgeText: { color: '#FFFFFF', fontSize: 16, fontWeight: '700' },
  statusLine: { flexDirection: 'row', alignItems: 'flex-start', gap: 10 },
  statusIcon: { fontSize: 24, fontWeight: '900', width: 26, lineHeight: 28 },
});
