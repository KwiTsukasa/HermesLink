import {createContext, useCallback, useContext, useRef, useState} from 'react';
import type {PropsWithChildren} from 'react';
import {
  Alert,
  Modal,
  Pressable,
  ScrollView,
  StyleSheet,
  Text,
  View,
} from 'react-native';
import type {AlertButton} from 'react-native';

import {t, useI18n} from '../i18n';
import {useTheme} from '../theme/ThemeProvider';
import {spacing} from '../theme/tokens';
import {Button} from './Button';

type ShowConfirmation = (
  title: string,
  message: string,
  actions: AlertButton[],
) => void;
type Confirmation = {title: string; message: string; actions: AlertButton[]};
const fallback: ShowConfirmation = (title, message, actions) =>
  Alert.alert(title, message, actions);
const Context = createContext<ShowConfirmation>(fallback);

export function useConfirmationDialog() {
  return useContext(Context);
}

export function ConfirmationDialogProvider({children}: PropsWithChildren) {
  useI18n();
  const colors = useTheme();
  const current = useRef<Confirmation | undefined>(undefined);
  const [dialog, setDialog] = useState<Confirmation>();
  const show = useCallback<ShowConfirmation>((title, message, actions) => {
    current.current?.actions
      .find((action) => action.style === 'cancel')
      ?.onPress?.();
    const next = {title, message, actions};
    current.current = next;
    setDialog(next);
  }, []);

  function act(action?: AlertButton) {
    if (!dialog || current.current !== dialog) return;
    current.current = undefined;
    setDialog(undefined);
    action?.onPress?.();
  }

  function cancel() {
    act(dialog?.actions.find((action) => action.style === 'cancel'));
  }

  return (
    <Context.Provider value={show}>
      {children}
      {dialog ? (
        <Modal visible transparent animationType="fade" onRequestClose={cancel}>
          <View accessibilityViewIsModal style={styles.overlay}>
            <Pressable
              accessibilityRole="button"
              accessibilityLabel={t('common.close')}
              style={StyleSheet.absoluteFill}
              onPress={cancel}
            />
            <View
              style={[
                styles.content,
                {backgroundColor: colors.panel, borderColor: colors.line},
              ]}
            >
              <Text
                accessibilityRole="header"
                style={[styles.title, {color: colors.ink}]}
              >
                {dialog.title}
              </Text>
              <ScrollView style={styles.message}>
                <Text selectable style={[styles.description, {color: colors.sub}]}>
                  {dialog.message}
                </Text>
              </ScrollView>
              <View style={styles.actions}>
                {dialog.actions.map((action, index) => (
                  <Button
                    key={index}
                    label={action.text || t('common.close')}
                    variant={action.style === 'cancel' ? 'plain' : 'accent'}
                    onPress={() => act(action)}
                  />
                ))}
              </View>
            </View>
          </View>
        </Modal>
      ) : null}
    </Context.Provider>
  );
}

const styles = StyleSheet.create({
  overlay: {
    flex: 1,
    alignItems: 'center',
    justifyContent: 'center',
    paddingHorizontal: spacing.lg,
    backgroundColor: 'rgba(0,0,0,0.58)',
  },
  content: {
    width: '100%',
    maxWidth: 448,
    borderWidth: StyleSheet.hairlineWidth,
    borderRadius: 4,
    padding: spacing.lg,
    gap: spacing.md,
  },
  title: {fontSize: 16, fontWeight: '600', lineHeight: 22},
  message: {maxHeight: 270, flexGrow: 0},
  description: {fontSize: 14, lineHeight: 20},
  actions: {
    flexDirection: 'row',
    flexWrap: 'wrap',
    gap: spacing.sm,
    justifyContent: 'flex-end',
  },
});
