import {t, useI18n} from '../../../shared/i18n';
import {useState} from 'react';
import {View} from 'react-native';

import {Button} from '../../../shared/ui/Button';
import {Feedback} from '../../../shared/ui/Feedback';
import {BusinessField, BusinessPage} from '../components/BusinessPage';
import {Card, Notice, detailStyles as styles} from '../components/DetailContent';
import {useConfirmedAction} from '../hooks/useConfirmedAction';
import {invoke, pickAttachment} from '../services/workspaceApi';
import type {BusinessForm, OperationInput} from '../types';
import {errorMessage, requireAccepted} from '../utils/data';

export function BusinessFormScreen({
  form,
  onBack,
  onWrite,
}: {
  form: BusinessForm;
  onBack: () => void;
  onWrite: () => void;
}) {
  useI18n();
  const [values, setValues] = useState(() =>
    Object.fromEntries(form.fields.map((field) => [field.key, field.initial ?? ''])),
  );
  const [attachment, setAttachment] = useState(form.attachment);
  const [picking, setPicking] = useState(false);
  const [error, setError] = useState('');
  const {confirm, busy, feedback} = useConfirmedAction(onWrite, () => {});

  async function selectAttachment() {
    setError('');
    setPicking(true);
    try {
      setAttachment(await pickAttachment());
    } catch (cause) {
      if (
        !(cause instanceof Error && 'code' in cause && cause.code === 'CANCELLED')
      ) {
        setError(errorMessage(cause));
      }
    } finally {
      setPicking(false);
    }
  }

  function submit() {
    let input: OperationInput;
    try {
      const missing = form.fields.find(
        (field) => field.required && !values[field.key]?.trim(),
      );
      if (missing) {
        throw new Error(t('mobile.businessForm.enter', {v1: String(missing.label)}));
      }
      if (form.pickFile && !attachment) {
        throw new Error(t('mobile.businessForm.selectAFile'));
      }
      const completed = attachment
        ? {...values, attachmentName: attachment.name}
        : values;
      input = {...form.build(completed), attachment: attachment?.id};
      setError('');
    } catch (cause) {
      setError(errorMessage(cause));
      return;
    }
    confirm(form.action, form.detail ?? form.title, async () => {
      await form.beforeSubmit?.(input);
      const response = await invoke(form.entry, input, true);
      requireAccepted(response);
      form.onSuccess?.(response, input);
      onBack();
    });
  }

  return (
    <BusinessPage title={form.title} onBack={onBack} dialog>
      {form.detail ? <Notice>{form.detail}</Notice> : null}
      <Card>
        {form.fields.map((field) => (
          <BusinessField
            key={field.key}
            field={field}
            value={values[field.key]}
            disabled={busy || picking}
            onChange={(value) =>
              setValues((current) => ({...current, [field.key]: value}))
            }
          />
        ))}
        <View style={styles.actions}>
          {form.pickFile ? (
            <Button
              label={
                attachment?.name || t('mobile.businessForm.chooseAFileFromYourPhone')
              }
              onPress={selectAttachment}
              disabled={busy || picking}
              busy={picking}
            />
          ) : null}
          <Button
            label={busy ? t('mobile.businessForm.submitting') : form.action}
            onPress={submit}
            variant="accent"
            disabled={busy || picking}
            busy={busy}
          />
        </View>
        {error || feedback ? (
          <Feedback tone="error" message={error || feedback} />
        ) : null}
      </Card>
    </BusinessPage>
  );
}
