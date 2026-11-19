import {t, useI18n} from '../../../shared/i18n';
import {useMemo, useState} from 'react';
import {RefreshControl, SectionList, StyleSheet, Text, View} from 'react-native';

import {useTheme} from '../../../shared/theme/ThemeProvider';
import {spacing, touchTarget} from '../../../shared/theme/tokens';
import {Button} from '../../../shared/ui/Button';
import {
  Card,
  Fact,
  Heading,
  Notice,
  ResourceStatus,
  detailStyles as styles,
} from '../components/DetailContent';
import {PageHeading} from '../components/PageHeading';
import {SearchField} from '../components/SearchField';
import {useConfirmedAction} from '../hooks/useConfirmedAction';
import {useDetailQuery} from '../hooks/useDetailQuery';
import {gatewayRoute, invoke} from '../services/workspaceApi';
import type {DetailScreenProps} from '../types';
import {
  plainText as plain,
  profileQuery,
  record,
  requireAccepted,
  rows,
} from '../utils/data';

type ModelSection = {
  key: string;
  name: string;
  slug: string;
  current: boolean;
  authenticated: boolean;
  authType: string;
  keyEnv: string;
  userDefined: boolean;
  data: string[];
};

export function ModelDetailsScreen({
  profile,
  byRoute,
  onBack,
  onWrite,
  onOpenForm,
  onAuthorize,
}: DetailScreenProps & {profile?: string; onAuthorize: () => void}) {
  useI18n();
  const c = useTheme();
  const [revision, setRevision] = useState(0);
  const [search, setSearch] = useState('');
  const [task, setTask] = useState('');
  const [showTasks, setShowTasks] = useState(false);
  const [showProviders, setShowProviders] = useState(false);
  const [warning, setWarning] = useState<{
    provider: string;
    model: string;
    task: string;
    message: string;
  }>();
  const refresh = () => setRevision((value) => value + 1);
  const {confirm, busy, feedback} = useConfirmedAction(onWrite, refresh);
  const input = useMemo(() => ({query: profileQuery(profile)}), [profile]);
  const optionsInput = useMemo(
    () => ({query: {...profileQuery(profile), include_unconfigured: showProviders}}),
    [profile, showProviders],
  );
  const options = useDetailQuery(
    byRoute.get('GET /api/model/options'),
    optionsInput,
    revision,
  );
  const provider = plain(record(options.value).provider);
  const model = plain(record(options.value).model);
  const auxiliary = useDetailQuery(
    byRoute.get('GET /api/model/auxiliary'),
    input,
    revision,
    showTasks,
  );
  const tasks = rows(auxiliary.value, ['tasks']);
  const assignment = tasks.find((item) => item.task === task);
  const route = byRoute.get('POST /api/model/set');
  const saveKey = gatewayRoute(byRoute, 'model.save_key');
  const disconnectRoute = gatewayRoute(byRoute, 'model.disconnect');
  const needle = search.trim().toLowerCase();
  const providers = useMemo(
    () =>
      rows(options.value, ['providers'])
        .map((item, index): ModelSection => {
          const slug = plain(item.slug);
          return {
            key: `${slug}-${index}`,
            name: plain(item.name) || slug,
            slug,
            current:
              typeof item.is_current === 'boolean'
                ? item.is_current
                : !!slug && slug === provider,
            authenticated: item.authenticated !== false,
            authType: plain(item.auth_type),
            keyEnv: plain(item.key_env),
            userDefined: item.is_user_defined === true,
            data: Array.isArray(item.models)
              ? [...new Set(item.models.map(plain).filter(Boolean))]
              : [],
          };
        })
        .filter((item) => item.name),
    [options.value, provider],
  );

  function connectProvider(section: ModelSection) {
    if (!saveKey || saveKey.unavailable) return;
    onOpenForm({
      title: t('mobile.modelDetails.connectModelProvider'),
      action: t('mobile.modelDetails.saveApiKey'),
      detail: t('mobile.jobDetails.profile', {
        v1: String(section.name),
        v2: String(profile || t('mobile.groups.currentGateway')),
      }),
      entry: saveKey,
      fields: [
        {
          key: 'key',
          label: t('settings.customEndpoints.fields.apiKey'),
          placeholder: section.keyEnv,
          required: true,
          secret: true,
        },
      ],
      build: ({key}) => ({
        ...input,
        body: {slug: section.slug, api_key: key.trim()},
      }),
      onSuccess: (value) => {
        const connected = record(record(value).provider);
        if (connected.slug !== section.slug || connected.authenticated !== true)
          throw new Error(
            t('mobile.modelDetails.theServerDidNotConfirmTheSelectedProvider'),
          );
      },
    });
  }

  function disconnectProvider(section: ModelSection) {
    confirm(
      t('mobile.modelDetails.disconnectModelProvider'),
      t('mobile.modelDetails.profileTheServerWillRemoveThisProviderS', {
        v1: String(section.name),
        v2: String(profile || t('mobile.groups.currentGateway')),
      }),
      async () => {
        const result = record(
          await invoke(
            disconnectRoute,
            {...input, body: {slug: section.slug}},
            true,
          ),
        );
        if (result.slug !== section.slug || result.disconnected !== true) {
          throw new Error(
            t('mobile.modelDetails.theServerDidNotConfirmThatTheProvider'),
          );
        }
      },
    );
  }
  const sections = useMemo(
    () =>
      providers.flatMap((item) => {
        if (!needle || `${item.name} ${item.slug}`.toLowerCase().includes(needle))
          return [item];
        const data = item.data.filter((name) => name.toLowerCase().includes(needle));
        return data.length ? [{...item, data}] : [];
      }),
    [providers, needle],
  );

  function assign(
    providerSlug: string,
    modelId: string,
    taskId: string,
    expensive = false,
  ) {
    const title = taskId
      ? t('mobile.modelDetails.setAuxiliaryModel')
      : t('mobile.modelDetails.switchMainModel');
    confirm(
      title,
      [
        warning?.message && expensive ? warning.message : '',
        `${providerSlug} · ${modelId || t('mobile.modelDetails.automatic')}`,
        t('mobile.groups.profile', {
          v1: String(profile || t('mobile.groups.currentGateway')),
        }),
        taskId
          ? t('mobile.modelDetails.task', {v1: String(taskId)})
          : t('mobile.modelDetails.newChatsWillUseThisModel'),
      ]
        .filter(Boolean)
        .join('\n'),
      async () => {
        const result = record(
          await invoke(
            route,
            {
              ...input,
              body: {
                scope: taskId ? 'auxiliary' : 'main',
                provider: providerSlug,
                model: modelId,
                ...(taskId ? {task: taskId} : {}),
                ...(expensive ? {confirm_expensive_model: true} : {}),
              },
            },
            true,
          ),
        );
        if (result.confirm_required === true && !expensive) {
          const message =
            plain(result.confirm_message) ||
            t('mobile.modelDetails.theServerRequiresConfirmationOfModelPricing');
          setWarning({
            provider: providerSlug,
            model: modelId,
            task: taskId,
            message,
          });
          throw new Error(t('mobile.modelDetails.confirmTheModelCost'));
        }
        requireAccepted(result);
        if (result.ok !== true)
          throw new Error(
            t('mobile.modelDetails.theServerDidNotConfirmTheModelChange'),
          );
        setWarning(undefined);
      },
    );
  }

  return (
    <View style={styles.screen}>
      <PageHeading
        title={t('mobile.modelDetails.modelOptions')}
        onBack={onBack}
        onRefresh={refresh}
        loading={options.loading || busy}
      />
      <SearchField
        placeholder={t('modelPicker.search')}
        value={search}
        onChange={setSearch}
      />
      <SectionList<string, ModelSection>
        keyboardShouldPersistTaps="handled"
        stickySectionHeadersEnabled={false}
        sections={sections}
        keyExtractor={(item) => item}
        contentContainerStyle={styles.content}
        refreshControl={
          <RefreshControl
            refreshing={options.loading}
            onRefresh={refresh}
            colors={[c.accent]}
            tintColor={c.accent}
          />
        }
        ListHeaderComponent={
          <>
            <ResourceStatus
              resource={options}
              label={t('mobile.modelDetails.modelOptions')}
            />
            {!options.loading && !options.error && (
              <Card>
                <Text style={[styles.caption, {color: c.sub}]}>
                  {t('mobile.overview.mainModel', {
                    v1: profile || t('mobile.modelDetails.currentProfile'),
                  })}
                </Text>
                <Text selectable style={[styles.title, {color: c.ink}]}>
                  {model || t('mobile.modelDetails.notConfigured')}
                </Text>
                {!!provider && (
                  <Text style={[styles.status, {color: c.accent}]}>
                    {providers.find((item) => item.current)?.name || provider}
                  </Text>
                )}
                <View style={styles.actions}>
                  <Button
                    label={
                      showProviders
                        ? t('mobile.modelDetails.showConnectedOnly')
                        : t('mobile.modelDetails.manageProviders')
                    }
                    disabled={busy}
                    onPress={() => setShowProviders(!showProviders)}
                  />
                  <Button
                    label={
                      showTasks
                        ? t('mobile.modelDetails.hideAuxiliaryTasks')
                        : t('mobile.modelDetails.auxiliaryTaskModels')
                    }
                    disabled={busy}
                    onPress={() => {
                      setShowTasks(!showTasks);
                      setTask('');
                      setWarning(undefined);
                    }}
                  />
                </View>
              </Card>
            )}
            {showTasks && (
              <Card>
                <Heading>{t('mobile.modelDetails.chooseATaskToConfigure')}</Heading>
                <ResourceStatus
                  resource={auxiliary}
                  label={t('settings.model.auxiliaryTitle')}
                />
                <View style={styles.actions}>
                  <Button
                    label={t('settings.subpages.modelMain')}
                    disabled={busy}
                    selected={!task}
                    onPress={() => {
                      setTask('');
                      setWarning(undefined);
                    }}
                  />
                  {tasks.map((item) => {
                    const id = plain(item.task);
                    return id ? (
                      <Button
                        key={id}
                        label={plain(item.label) || id}
                        disabled={busy}
                        selected={id === task}
                        onPress={() => {
                          setTask(id);
                          setWarning(undefined);
                        }}
                      />
                    ) : null;
                  })}
                </View>
                {assignment ? (
                  <>
                    <Fact
                      label={t('mobile.modelDetails.currentAuxiliaryModel')}
                      value={
                        plain(assignment.model) || t('mobile.modelDetails.automatic')
                      }
                    />
                    <Fact
                      label={t('cli.tui.picker_provider_fallback')}
                      value={plain(assignment.provider)}
                    />
                    <Fact
                      label={t('mobile.kanban.taskDescription')}
                      value={plain(assignment.hint)}
                    />
                    {route && !route.unavailable ? (
                      <Button
                        label={t('mobile.modelDetails.restoreAutomaticSelection')}
                        disabled={busy}
                        onPress={() => assign('auto', '', task)}
                      />
                    ) : null}
                  </>
                ) : null}
              </Card>
            )}
            {warning ? (
              <Card>
                <Notice>{warning.message}</Notice>
                <Fact
                  label={t('mobile.modelDetails.selectedModel')}
                  value={warning.model}
                />
                <View style={styles.actions}>
                  <Button
                    label={t('common.back')}
                    disabled={busy}
                    onPress={() => setWarning(undefined)}
                  />
                  <Button
                    label={t('mobile.modelDetails.confirmCostAndSwitch')}
                    disabled={busy}
                    variant="accent"
                    onPress={() =>
                      assign(warning.provider, warning.model, warning.task, true)
                    }
                  />
                </View>
              </Card>
            ) : null}
            {!!feedback && <Notice>{feedback}</Notice>}
          </>
        }
        ListEmptyComponent={
          !options.loading && !options.error ? (
            <Notice>
              {needle
                ? t('mobile.modelDetails.noMatchingProvidersOrModels')
                : t('mobile.modelDetails.noModelProvidersAvailable')}
            </Notice>
          ) : undefined
        }
        renderSectionHeader={({section}) => (
          <View style={[modelStyles.provider, {borderColor: c.line}]}>
            <Heading>{section.name}</Heading>
            <Text style={[styles.caption, {color: c.sub}]}>
              {section.authenticated
                ? t('mobile.modelDetails.models', {
                    v1: String(section.data.length),
                    count: Number(String(section.data.length)),
                  })
                : t('mobile.modelDetails.disconnected')}
            </Text>
            {!section.authenticated &&
            section.authType === 'api_key' &&
            section.keyEnv &&
            saveKey &&
            !saveKey.unavailable ? (
              <Button
                label={t('mobile.modelDetails.configureApiKey')}
                disabled={busy || options.loading}
                onPress={() => connectProvider(section)}
              />
            ) : !section.authenticated && section.authType === 'oauth' ? (
              <Button
                label={t('mobile.mcp.authorizeInBrowser')}
                onPress={onAuthorize}
                disabled={busy}
              />
            ) : null}
            {showProviders &&
            section.authenticated &&
            !section.userDefined &&
            !!section.slug &&
            !['local', 'virtual'].includes(section.authType) &&
            disconnectRoute &&
            !disconnectRoute.unavailable ? (
              <Button
                label={t('mobile.modelDetails.disconnectProvider')}
                disabled={busy || options.loading}
                onPress={() => disconnectProvider(section)}
              />
            ) : null}
          </View>
        )}
        renderSectionFooter={({section}) =>
          !section.data.length ? (
            <Notice>{t('mobile.modelDetails.thisProviderHasNoModelOptions')}</Notice>
          ) : null
        }
        renderItem={({item, section}) => (
          <View style={[modelStyles.modelRow, {borderColor: c.line}]}>
            <Text
              selectable
              style={[styles.itemTitle, modelStyles.modelName, {color: c.ink}]}
            >
              {item}
            </Text>
            {(
              task
                ? assignment?.provider === section.slug && assignment.model === item
                : section.current && item === model
            ) ? (
              <Text style={[styles.status, {color: c.accent}]}>
                {t('settings.connections.currentPill')}
              </Text>
            ) : route &&
              !route.unavailable &&
              section.authenticated &&
              section.slug ? (
              <Button
                label={t('settings.customEndpoints.use')}
                disabled={busy || options.loading || !!warning}
                onPress={() => assign(section.slug, item, task)}
              />
            ) : null}
          </View>
        )}
      />
    </View>
  );
}

const modelStyles = StyleSheet.create({
  provider: {
    flexDirection: 'row',
    flexWrap: 'wrap',
    alignItems: 'center',
    columnGap: spacing.md,
    paddingVertical: spacing.xs,
    borderBottomWidth: StyleSheet.hairlineWidth,
  },
  modelRow: {
    minHeight: touchTarget,
    flexDirection: 'row',
    alignItems: 'center',
    columnGap: spacing.md,
    paddingVertical: spacing.sm,
    borderBottomWidth: StyleSheet.hairlineWidth,
  },
  modelName: {flex: 1},
});
