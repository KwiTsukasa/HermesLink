import {t} from '../../../shared/i18n';
import {useEffect, useRef, useState} from 'react';
import {useConfirmationDialog} from '../../../shared/ui/ConfirmationDialog';

import {
  catalogEntryLabel,
  executeOperation,
  pickAttachment,
  saveFile,
} from '../services/workspaceApi';
import type {Attachment, Entry, OperationInput, OperationResult} from '../types';
import {errorMessage, record} from '../utils/data';
import {buildOperationInput, operationFields} from '../utils/operationForm';

type PendingAction = 'execute' | 'pick' | 'save';

export function useOperation(entry: Entry, onWrite: () => void) {
  const showConfirmation = useConfirmationDialog();
  const [values, setValues] = useState<Record<string, string>>({});
  const [attachment, setAttachment] = useState<Attachment | null>(null);
  const [result, setResult] = useState<OperationResult>();
  const [error, setError] = useState<string>();
  const [pending, setPending] = useState<PendingAction>();
  const mounted = useRef(false);
  const currentTask = useRef<symbol | undefined>(undefined);
  const confirmation = useRef<symbol | undefined>(undefined);

  useEffect(() => {
    mounted.current = true;
    return () => {
      mounted.current = false;
      currentTask.current = undefined;
      confirmation.current = undefined;
    };
  }, []);

  async function perform<T>(
    kind: PendingAction,
    work: () => Promise<T>,
    onComplete?: (value: T) => void,
  ) {
    if (!mounted.current || currentTask.current) {
      return;
    }

    const task = Symbol(kind);
    currentTask.current = task;
    setPending(kind);
    setError(undefined);
    try {
      const value = await work();
      if (currentTask.current === task) {
        onComplete?.(value);
      }
    } catch (cause) {
      if (currentTask.current === task && record(cause).code !== 'CANCELLED') {
        setError(errorMessage(cause));
      }
    } finally {
      if (currentTask.current === task) {
        currentTask.current = undefined;
        setPending(undefined);
      }
    }
  }

  const fields = operationFields(entry);

  function submit() {
    if (
      !mounted.current ||
      currentTask.current ||
      confirmation.current ||
      entry.unavailable
    ) {
      return;
    }

    let parameters: OperationInput;
    try {
      parameters = buildOperationInput(fields, values, attachment);
    } catch (cause) {
      setError(errorMessage(cause));
      return;
    }

    const run = (confirmed: boolean) => {
      perform(
        'execute',
        async () => {
          setResult(undefined);
          const response = await executeOperation(entry, parameters, confirmed);
          if (entry.constraints.mutating) {
            onWrite();
          }
          return response;
        },
        setResult,
      );
    };

    if (entry.constraints.mutating || entry.constraints.confirmationRequired) {
      const token = Symbol(entry.id);
      confirmation.current = token;
      showConfirmation(
        t('mobile.useOperation.confirmExecution'),
        `${catalogEntryLabel(entry)}\n${entry.method} ${entry.path}`,
        [
          {
            text: t('common.cancel'),
            style: 'cancel',
            onPress: () => {
              confirmation.current = undefined;
            },
          },
          {
            text: t('mobile.useOperation.run'),
            onPress: () => {
              if (confirmation.current === token) {
                confirmation.current = undefined;
                run(true);
              }
            },
          },
        ],
      );
    } else {
      run(false);
    }
  }

  return {
    attachment,
    error,
    fields,
    pending,
    result,
    values,
    submit,
    selectFile: () => perform('pick', pickAttachment, setAttachment),
    saveResult: (handle: string) => perform('save', () => saveFile(handle)),
    updateValue: (key: string, value: string) => {
      setValues((previous) => ({...previous, [key]: value}));
    },
  };
}
