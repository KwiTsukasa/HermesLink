import {useMemo} from 'react';

import {invoke} from '../services/workspaceApi';
import type {Entry, OperationInput} from '../types';
import {useWorkspaceQuery} from './useWorkspaceQuery';

export function useDetailQuery(
  entry: Entry | undefined,
  input: OperationInput,
  revision: number,
  enabled = true,
) {
  const request = useMemo(
    () => (enabled ? () => invoke(entry, input) : null),
    [enabled, entry, input],
  );
  const {state} = useWorkspaceQuery(request, revision);

  return {
    loading: state.status === 'loading',
    error: state.status === 'error' ? state.error : undefined,
    value: 'data' in state ? state.data : undefined,
  };
}
