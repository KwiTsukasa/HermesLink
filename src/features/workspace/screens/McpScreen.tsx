import {t, useI18n} from '../../../shared/i18n';
import {useMemo, useState} from 'react';
import {View} from 'react-native';

import {Button} from '../../../shared/ui/Button';
import {ListRow} from '../../../shared/ui/ListRow';
import {
  ActionStatus,
  backgroundAction,
  type BackgroundAction,
} from '../components/ActionStatus';
import {BusinessPage} from '../components/BusinessPage';
import {
  Card,
  Fact,
  Heading,
  Notice,
  ResourceStatus,
  detailStyles as styles,
} from '../components/DetailContent';
import {SearchField} from '../components/SearchField';
import {useConfirmedAction} from '../hooks/useConfirmedAction';
import {useDetailQuery} from '../hooks/useDetailQuery';
import {invoke} from '../services/workspaceApi';
import type {DetailScreenProps, Dict} from '../types';
import {
  plainText as plain,
  profileQuery,
  requireAccepted,
  rows,
  stringList,
  textValue,
} from '../utils/data';
import {BusinessFormScreen} from './BusinessFormScreen';

export type McpTarget =
  | {kind: 'installed'; name: string}
  | {kind: 'catalog'; name: string}
  | {kind: 'browse'}
  | {kind: 'add'; transport: 'http' | 'stdio'};

const serverRoute = '/api/mcp/servers/{name}';

export function McpScreen({
  profile,
  target,
  byRoute,
  onBack,
  onWrite,
  onOpenForm,
  onOpen,
  onAuthorize,
}: DetailScreenProps & {
  profile?: string;
  target?: McpTarget;
  onOpen: (target: McpTarget) => void;
  onAuthorize?: () => void;
}) {
  useI18n();
  const [search, setSearch] = useState('');
  const [probe, setProbe] = useState<Dict>();
  const [action, setAction] = useState<BackgroundAction>();
  const [revision, setRevision] = useState(0);
  const refresh = () => setRevision((value) => value + 1);
  const {confirm, busy, feedback} = useConfirmedAction(onWrite, refresh);
  const input = useMemo(() => ({query: profileQuery(profile)}), [profile]);
  const inCatalog = target?.kind === 'browse' || target?.kind === 'catalog';
  const servers = useDetailQuery(
    byRoute.get('GET /api/mcp/servers'),
    input,
    revision,
    !inCatalog && target?.kind !== 'add',
  );
  const catalog = useDetailQuery(
    byRoute.get('GET /api/mcp/catalog'),
    input,
    revision,
    inCatalog,
  );
  const current = inCatalog ? catalog : servers;
  const items = rows(current.value, [inCatalog ? 'entries' : 'servers']);
  const name =
    target?.kind === 'catalog' || target?.kind === 'installed' ? target.name : '';
  const detail = items.find((item) => item.name === name);
  const subtitle = t('mobile.groups.profile', {
    v1: String(profile || t('mobile.groups.currentGateway')),
  });
  const create = byRoute.get('POST /api/mcp/servers');
  const remove = byRoute.get(`DELETE ${serverRoute}`);
  const toggle = byRoute.get(`PUT ${serverRoute}/enabled`);
  const test = byRoute.get(`POST ${serverRoute}/test`);
  const install = byRoute.get('POST /api/mcp/catalog/install');

  if (target?.kind === 'add' && create && !create.unavailable) {
    const http = target.transport === 'http';
    return (
      <BusinessFormScreen
        form={{
          title: http
            ? t('mobile.mcp.addHttpMcpServer')
            : t('mobile.mcp.addLocalMcpServer'),
          action: t('mobile.mcp.addServer'),
          entry: create,
          detail: http
            ? t('mobile.mcp.theAppSupportsHttpEndpointsOnTheSame', {
                v1: String(subtitle),
              })
            : t('mobile.mcp.theServerProcessRunsOnTheHermesHost', {
                v1: String(subtitle),
              }),
          fields: [
            {key: 'name', label: t('mobile.mcp.serverName'), required: true},
            {
              key: 'endpoint',
              label: http
                ? t('mobile.mcp.httpUrl')
                : t('assistant.approval.command'),
              required: true,
            },
            ...(http
              ? [
                  {
                    key: 'token',
                    label: t('mobile.mcp.bearerTokenOptional'),
                    secret: true,
                  },
                ]
              : [
                  {
                    key: 'args',
                    label: t('mobile.mcp.argumentsOnePerLine'),
                    multiline: true,
                  },
                ]),
          ],
          build: ({name: next, endpoint, token, args}) => ({
            ...input,
            body: {
              name: next.trim(),
              ...(http
                ? {
                    url: endpoint.trim(),
                    ...(token ? {auth: 'header', bearer_token: token} : {}),
                  }
                : {
                    command: endpoint.trim(),
                    args: args
                      .split(/\r?\n/)
                      .map((line) => line.trim())
                      .filter(Boolean),
                  }),
            },
          }),
          onSuccess: (value) => {
            if (!plain(requireAccepted(value).name))
              throw new Error(
                t('mobile.mcp.theServerDidNotConfirmTheMcpConfiguration'),
              );
          },
        }}
        onBack={onBack}
        onWrite={onWrite}
      />
    );
  }

  function operate(kind: 'toggle' | 'remove' | 'test') {
    const enabled = detail?.enabled === true;
    const label = {
      toggle: enabled
        ? t('mobile.mcp.disableMcpServer')
        : t('mobile.mcp.enableMcpServer'),
      remove: t('mobile.mcp.deleteMcpConfiguration'),
      test: t('install.testConnection'),
    }[kind];
    const entry = {toggle, remove, test}[kind];
    const body = kind === 'toggle' ? {enabled: !enabled} : undefined;
    confirm(label, `${name}\n${subtitle}`, async () => {
      const result = requireAccepted(
        await invoke(entry, {...input, path: {name}, body}, true),
      );
      if (result.ok !== true || (kind === 'toggle' && result.enabled !== !enabled)) {
        throw new Error(t('mobile.mcp.theServerDidNotConfirmThisOperation'));
      }
      if (kind === 'remove') onBack();
      if (kind === 'test') setProbe(result);
    });
  }

  function installCatalog() {
    if (!install || install.unavailable || !detail) return;
    const requirements = rows(detail.required_env, []);
    if (!requirements.length) {
      confirm(t('mobile.mcp.installMcpServer'), `${name}\n${subtitle}`, async () => {
        const result = requireAccepted(
          await invoke(install, {...input, body: {name, enable: true}}, true),
        );
        if (result.ok !== true)
          throw new Error(t('mobile.mcp.theServerDidNotConfirmTheMcpInstallation'));
        setAction(backgroundAction(result, t('mobile.mcp.installMcpServer')));
      });
      return;
    }
    const fields = requirements.flatMap((item) => {
      const key = plain(item.name);
      return key
        ? [
            {
              key,
              label: plain(item.prompt) || key,
              required: item.required === true,
              secret: true,
            },
          ]
        : [];
    });
    onOpenForm({
      title: t('mobile.mcp.configureAndInstallMcpServer'),
      action: t('mobile.mcp.installMcpServer'),
      detail: `${name}\n${subtitle}`,
      entry: install,
      fields,
      build: (values) => ({
        ...input,
        body: {
          name,
          env: Object.fromEntries(
            Object.entries(values).filter(([, value]) => value.length > 0),
          ),
          enable: true,
        },
      }),
    });
  }

  const query = search.trim().toLowerCase();
  const visible = items.filter((item) =>
    `${plain(item.name)} ${plain(item.description)}`.toLowerCase().includes(query),
  );
  return (
    <BusinessPage
      title={
        name
          ? t('mobile.mcp.mcpServerDetails')
          : inCatalog
            ? t('mobile.mcp.officialMcpCatalog')
            : t('commandCenter.mcpServers')
      }
      onBack={onBack}
      onRefresh={refresh}
      loading={current.loading || busy}
    >
      <ResourceStatus
        resource={current}
        label={
          inCatalog ? t('mobile.mcp.mcpCatalog') : t('commandCenter.mcpServers')
        }
      />
      {action ? <ActionStatus action={action} byRoute={byRoute} /> : null}
      {!!feedback && !action ? <Notice>{feedback}</Notice> : null}
      {name ? (
        detail ? (
          <>
            <Card>
              <Heading>{name}</Heading>
              <Fact
                label={t('mobile.mcp.connectionMethod')}
                value={plain(detail.transport)}
              />
              <Fact
                label={t('install.authTitle')}
                value={plain(detail.auth) || plain(detail.auth_type)}
              />
              <Fact label={t('catalog.about')} value={plain(detail.description)} />
              <Fact
                label={t('catalog.source')}
                value={plain(detail.plugin) || plain(detail.source)}
              />
              <Fact label={t('preview.web.address')} value={plain(detail.url)} />
              <Fact
                label={t('mobile.mcp.hostCommand')}
                value={[plain(detail.command), ...stringList(detail.args)]
                  .filter(Boolean)
                  .join(' ')}
              />
              <Fact
                label={t('mobile.mcp.installationSource')}
                value={plain(detail.install_url)}
              />
              <Fact
                label={t('mobile.mcp.installationSteps')}
                value={stringList(detail.bootstrap).join('\n')}
              />
              <Fact
                label={t('mobile.mcp.installationNotes')}
                value={plain(detail.post_install)}
              />
              <Fact
                label={t('sidebar.filter.status')}
                value={
                  inCatalog && detail.installed !== true
                    ? t('assistant.catalogInstall.notInstalled')
                    : detail.enabled === true
                      ? t('messaging.enabled')
                      : t('mobile.mcp.disabled')
                }
              />
              <View style={styles.actions}>
                {inCatalog ? (
                  detail.installed !== true && install && !install.unavailable ? (
                    <Button
                      label={t('catalog.install')}
                      variant="accent"
                      disabled={busy || current.loading || !!action}
                      onPress={installCatalog}
                    />
                  ) : null
                ) : (
                  <>
                    {test && !test.unavailable ? (
                      <Button
                        label={t('install.testConnection')}
                        variant="accent"
                        disabled={busy || current.loading}
                        onPress={() => {
                          setProbe(undefined);
                          operate('test');
                        }}
                      />
                    ) : null}
                    {onAuthorize && detail.auth === 'oauth' ? (
                      <Button
                        label={t('mobile.mcp.authorizeInBrowser')}
                        onPress={onAuthorize}
                        disabled={busy}
                      />
                    ) : null}
                    {detail.source !== 'plugin' &&
                    toggle &&
                    !toggle.unavailable &&
                    typeof detail.enabled === 'boolean' ? (
                      <Button
                        label={
                          detail.enabled
                            ? t('mobile.mcp.disable')
                            : t('webhooks.enableRow')
                        }
                        disabled={busy || current.loading}
                        onPress={() => operate('toggle')}
                      />
                    ) : null}
                    {detail.source !== 'plugin' && remove && !remove.unavailable ? (
                      <Button
                        label={t('mobile.mcp.deleteConfiguration')}
                        disabled={busy || current.loading}
                        onPress={() => operate('remove')}
                      />
                    ) : null}
                  </>
                )}
              </View>
            </Card>
            {probe ? (
              <Card>
                <Heading>{t('mobile.mcp.connectionTest')}</Heading>
                <Fact
                  label={t('mobile.mcp.toolsPromptsResources')}
                  value={`${rows(probe.tools, []).length} / ${textValue(probe.prompts)} / ${textValue(probe.resources)}`}
                />
                {rows(probe.tools, []).map((item) => (
                  <Fact
                    key={plain(item.name)}
                    label={plain(item.name)}
                    value={plain(item.description) || plain(item.name)}
                  />
                ))}
              </Card>
            ) : null}
          </>
        ) : !current.loading && !current.error ? (
          <Notice>{t('mobile.mcp.thisServerIsUnavailable')}</Notice>
        ) : null
      ) : (
        <>
          <SearchField
            placeholder={t('mobile.mcp.searchMcpServers')}
            value={search}
            onChange={setSearch}
            clearable
          />
          {!inCatalog ? (
            <View style={styles.actions}>
              <Button
                label={t('mobile.mcp.officialCatalog')}
                onPress={() => onOpen({kind: 'browse'})}
              />
              {create && !create.unavailable ? (
                <>
                  <Button
                    label={t('mobile.mcp.addHttpServer')}
                    onPress={() => onOpen({kind: 'add', transport: 'http'})}
                  />
                  <Button
                    label={t('mobile.mcp.addLocalServer')}
                    onPress={() => onOpen({kind: 'add', transport: 'stdio'})}
                  />
                </>
              ) : null}
            </View>
          ) : null}
          {visible.map((item) => {
            const id = plain(item.name);
            return id ? (
              <ListRow
                key={id}
                title={id}
                subtitle={[
                  inCatalog && item.installed !== true
                    ? t('assistant.catalogInstall.notInstalled')
                    : item.enabled
                      ? t('messaging.enabled')
                      : t('mobile.mcp.disabled'),
                  plain(item.transport),
                  plain(item.description),
                ]
                  .filter(Boolean)
                  .join(' · ')}
                onPress={() =>
                  onOpen({kind: inCatalog ? 'catalog' : 'installed', name: id})
                }
              />
            ) : null;
          })}
          {!current.loading && !current.error && !visible.length ? (
            <Notice>
              {query
                ? t('mobile.mcp.noMatchingServers')
                : t('mobile.mcp.noMcpServers')}
            </Notice>
          ) : null}
        </>
      )}
    </BusinessPage>
  );
}
