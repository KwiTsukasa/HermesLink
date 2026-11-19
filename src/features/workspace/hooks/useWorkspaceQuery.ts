import {useCallback, useEffect, useState} from 'react';

import {errorMessage} from '../utils/data';

type Idle = {status: 'idle'};
type Loading<T> = {status: 'loading'; data?: T};
type Success<T> = {status: 'success'; data: T};
type Failure = {status: 'error'; error: string};
type QueryState<T> = Idle | Loading<T> | Success<T> | Failure;
type Request<T> = () => Promise<T>;

export function useWorkspaceQuery<T>(
  request: Request<T> | null,
  externalRevision = 0,
) {
  const [refreshCount, setRefreshCount] = useState(0);
  const revision = externalRevision + refreshCount;
  const [snapshot, setSnapshot] = useState<{
    request: Request<T>;
    revision: number;
    state: Success<T> | Failure;
  }>();

  const refresh = useCallback(() => setRefreshCount((previous) => previous + 1), []);

  useEffect(() => {
    if (!request) {
      return;
    }
    let active = true;

    async function load(query: Request<T>) {
      try {
        const data = await query();
        if (active) {
          setSnapshot({request: query, revision, state: {status: 'success', data}});
        }
      } catch (error) {
        if (active) {
          setSnapshot({
            request: query,
            revision,
            state: {status: 'error', error: errorMessage(error)},
          });
        }
      }
    }

    load(request);
    return () => {
      active = false;
    };
  }, [request, revision]);

  let state: QueryState<T> = {status: 'idle'};
  if (request) {
    state = {status: 'loading'};
    if (snapshot?.request === request) {
      if (snapshot.revision === revision) {
        state = snapshot.state;
      } else if (snapshot.state.status === 'success') {
        state.data = snapshot.state.data;
      }
    }
  }
  return {state, refresh};
}
