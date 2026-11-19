import {useCallback, useEffect, useRef, useState} from 'react';

import {
  getHermesOfficial,
  openOfficialDestination,
} from '../../native/HermesOfficial';
import type {
  OfficialDestination,
  OfficialDestinationId,
  ProviderLoginStatus,
  ProviderStatus,
  ProviderVerification,
} from '../../native/HermesOfficial';

type Verification = {provider: ProviderStatus; code: ProviderVerification};

function errorMessage(error: unknown) {
  return error instanceof Error ? error.message : String(error);
}

export function useOfficialTools() {
  const [destinations, setDestinations] = useState<OfficialDestination[]>([]);
  const [providers, setProviders] = useState<ProviderStatus[]>([]);
  const [verification, setVerification] = useState<Verification>();
  const [loginState, setLoginState] = useState<ProviderLoginStatus['state']>();
  const [loginError, setLoginError] = useState('');
  const [pagesError, setPagesError] = useState('');
  const [providersError, setProvidersError] = useState('');
  const [loading, setLoading] = useState(true);
  const [busy, setBusy] = useState(false);
  const reads = useRef({revision: 0});
  const action = useRef<object | null>(null);
  const mounted = useRef(false);

  useEffect(() => {
    const readState = reads.current;
    mounted.current = true;
    return () => {
      mounted.current = false;
      readState.revision++;
      action.current = null;
    };
  }, []);

  const refresh = useCallback(async () => {
    const readState = reads.current;
    const revision = ++readState.revision;
    setLoading(true);
    setPagesError('');
    setProvidersError('');
    const [pages, roster] = await Promise.allSettled([
      Promise.resolve().then(() => getHermesOfficial().destinations()),
      Promise.resolve().then(() => getHermesOfficial().providers()),
    ]);
    if (readState.revision !== revision) return;
    setLoading(false);
    if (pages.status === 'fulfilled') setDestinations(pages.value);
    else setPagesError(errorMessage(pages.reason));
    if (roster.status === 'fulfilled') setProviders(roster.value);
    else {
      setProviders([]);
      setProvidersError(errorMessage(roster.reason));
    }
  }, []);

  async function openPage(id: OfficialDestinationId) {
    setPagesError('');
    try {
      await openOfficialDestination(id);
    } catch (error) {
      if (mounted.current) setPagesError(errorMessage(error));
    }
  }

  async function run<Result>(
    request: () => Promise<Result>,
    success: (result: Result) => void,
  ) {
    if (action.current) return;
    const current = {};
    action.current = current;
    setBusy(true);
    setLoginError('');
    try {
      const result = await request();
      if (action.current === current) success(result);
    } catch (error) {
      if (action.current === current) setLoginError(errorMessage(error));
    } finally {
      if (action.current === current) {
        action.current = null;
        setBusy(false);
      }
    }
  }

  function begin(provider: ProviderStatus) {
    if (verification || !provider.deviceFlowAvailable || provider.connected === true)
      return;
    return run(
      () => getHermesOfficial().beginProviderLogin(provider.id),
      (code) => {
        setVerification({provider, code});
        setLoginState(undefined);
      },
    );
  }

  function verify() {
    if (!verification) return;
    return run(
      () => getHermesOfficial().openProviderVerification(verification.code.id),
      () => {},
    );
  }

  function check() {
    if (!verification) return;
    return run(
      () => getHermesOfficial().pollProviderLogin(verification.code.id),
      (status) => {
        setLoginState(status.state);
        if (status.message) setLoginError(status.message);
        if (status.state !== 'pending') {
          setVerification(undefined);
          refresh();
        }
      },
    );
  }

  function cancel() {
    if (!verification) return;
    return run(
      () => getHermesOfficial().cancelProviderLogin(verification.code.id),
      () => {
        setVerification(undefined);
        setLoginState('cancelled');
      },
    );
  }

  return {
    destinations,
    providers,
    verification,
    loginState,
    loginError,
    pagesError,
    providersError,
    loading,
    busy,
    refresh,
    openPage,
    begin,
    verify,
    check,
    cancel,
  };
}
