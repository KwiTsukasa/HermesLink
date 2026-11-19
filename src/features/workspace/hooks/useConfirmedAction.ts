import {t} from '../../../shared/i18n';
import {useEffect, useRef, useState} from 'react';
import {useConfirmationDialog} from '../../../shared/ui/ConfirmationDialog';

import {errorMessage} from '../utils/data';

export function useConfirmedAction(onWrite: () => void, refresh: () => void) {
  const showConfirmation = useConfirmationDialog();
  const [busy, setBusy] = useState(false);
  const [feedback, setFeedback] = useState('');
  const [failed, setFailed] = useState(false);
  const currentAction = useRef<symbol | undefined>(undefined);

  useEffect(() => {
    return () => {
      currentAction.current = undefined;
    };
  }, []);

  function confirm(
    label: string,
    detail: string,
    action: () => Promise<void | string>,
  ) {
    if (currentAction.current) {
      return;
    }
    const token = Symbol(label);
    currentAction.current = token;

    async function run() {
      if (currentAction.current !== token) {
        return;
      }
      setBusy(true);
      setFeedback('');
      setFailed(false);
      try {
        const message = await action();
        onWrite();
        if (currentAction.current === token) {
          setFeedback(
            message || t('mobile.useConfirmedAction.succeeded', {v1: String(label)}),
          );
        }
      } catch (error) {
        if (currentAction.current === token) {
          setFailed(true);
          setFeedback(
            t('mobile.useConfirmedAction.failed', {
              v1: String(label),
              v2: String(errorMessage(error)),
            }),
          );
        }
      } finally {
        if (currentAction.current === token) {
          currentAction.current = undefined;
          refresh();
          setBusy(false);
        }
      }
    }

    showConfirmation(
      t('mobile.useConfirmedAction.confirm', {v1: String(label)}),
      detail,
      [
        {
          text: t('common.cancel'),
          style: 'cancel',
          onPress: () => {
            if (currentAction.current === token) {
              currentAction.current = undefined;
            }
          },
        },
        {text: label, onPress: run},
      ],
    );
  }

  return {confirm, busy, feedback, failed};
}
