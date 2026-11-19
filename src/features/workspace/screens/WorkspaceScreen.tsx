import {t, useI18n} from '../../../shared/i18n';
import {useCallback, useEffect, useMemo, useRef, useState} from 'react';
import {
  Alert,
  BackHandler,
  StyleSheet,
  View,
  useWindowDimensions,
} from 'react-native';

import {
  openOfficialDestination,
  type OfficialDestinationId,
} from '../../../native/HermesOfficial';
import {closeWorkspace, getHermesWorkspace} from '../../../native/HermesWorkspace';
import {AppearanceSettings} from '../../../shared/theme/AppearanceSettings';
import {useAppearanceProfile, useTheme} from '../../../shared/theme/ThemeProvider';
import {spacing, touchTarget} from '../../../shared/theme/tokens';
import {Button} from '../../../shared/ui/Button';
import {ConfirmationDialogProvider} from '../../../shared/ui/ConfirmationDialog';
import {Feedback} from '../../../shared/ui/Feedback';
import {LiveScreen} from '../../live/LiveScreen';
import {MediaToolsScreen} from '../../media/MediaToolsScreen';
import {TerminalScreen} from '../../terminal/TerminalScreen';
import {BusinessPage} from '../components/BusinessPage';
import {PageHeading} from '../components/PageHeading';
import {
  WorkspaceRail,
  workspaceRailLayout,
  type WorkspaceDestination,
} from '../components/WorkspaceRail';
import {tabLabels, WorkspacePaneTabs} from '../components/WorkspaceTabs';
import {useWorkspaceQuery} from '../hooks/useWorkspaceQuery';
import {
  catalogGroupId,
  catalogGroupLabel,
  getCatalog,
  indexRoutes,
  invoke,
} from '../services/workspaceApi';
import type {BusinessForm, DetailTarget, Dict, Entry, WorkspaceTab} from '../types';
import {errorMessage, plainText, profileQuery, record} from '../utils/data';
import {AchievementsScreen} from './AchievementsScreen';
import {ArtifactsScreen, type ArtifactTarget} from './ArtifactsScreen';
import {BusinessFormScreen} from './BusinessFormScreen';
import {CatalogScreen} from './CatalogScreen';
import {DetailScreen} from './DetailScreen';
import {FileBrowserScreen} from './FileBrowserScreen';
import {GatewaysScreen} from './GatewaysScreen';
import {GroupsScreen} from './GroupsScreen';
import {JobEditorScreen} from './JobEditorScreen';
import {KanbanScreen} from './KanbanScreen';
import {KanbanTaskScreen} from './KanbanTaskScreen';
import {KnowledgeScreen} from './KnowledgeScreen';
import {LanguageScreen} from './LanguageScreen';
import {MaintenanceScreen} from './MaintenanceScreen';
import {McpScreen, type McpTarget} from './McpScreen';
import {MessagingScreen} from './MessagingScreen';
import {OperationScreen} from './OperationScreen';
import {OpsScreen} from './OpsScreen';
import {OverviewScreen} from './OverviewScreen';
import {ProfilesScreen} from './ProfilesScreen';
import {ProjectsScreen} from './ProjectsScreen';
import {RelayAccountsScreen} from './RelayAccountsScreen';
import {SkillHubScreen} from './SkillHubScreen';
import {StarmapScreen} from './StarmapScreen';
import {WebhooksScreen} from './WebhooksScreen';
import {WorkspaceItemsScreen} from './WorkspaceItemsScreen';

type Overlay =
  | {kind: 'operation'; entry: Entry}
  | {kind: 'detail'; target: DetailTarget}
  | {kind: 'form'; form: BusinessForm}
  | {kind: 'files'; initial?: {name: string; path: string}}
  | {kind: 'terminal'}
  | {kind: 'media'}
  | {kind: 'live'}
  | {kind: 'artifacts'; target?: ArtifactTarget}
  | {kind: 'groups'; roomId?: string; create?: boolean}
  | {kind: 'starmap'; nodeId?: string}
  | {kind: 'gateways'}
  | {kind: 'relayAccounts'}
  | {kind: 'kanban'; slug?: string; task?: string}
  | {kind: 'achievements'; sessionId?: string; owner?: string}
  | {kind: 'knowledge'}
  | {kind: 'language'}
  | {kind: 'appearance'}
  | {kind: 'maintenance'; screen: 'checkpoints' | 'hooks'}
  | {kind: 'profiles'; name?: string}
  | {kind: 'jobEditor'; profile?: string; job?: Dict}
  | {kind: 'mcp'; target?: McpTarget}
  | {kind: 'skillsHub'; selected?: Dict}
  | {kind: 'projects'; id?: string}
  | {kind: 'ops'; screen?: 'logs'}
  | {kind: 'webhooks'; name?: string}
  | {kind: 'messaging'; id?: string};

const readRoutes = {
  overview: 'GET /api/model/info',
  skills: 'GET /api/skills',
  jobs: 'GET /api/cron/jobs',
};

export function WorkspaceScreen() {
  return (
    <ConfirmationDialogProvider>
      <WorkspaceBody />
    </ConfirmationDialogProvider>
  );
}

function WorkspaceBody() {
  const {locale} = useI18n();
  const colors = useTheme();
  const {width} = useWindowDimensions();
  const {docked, railWidth} = workspaceRailLayout(width);
  const [drawer, setDrawer] = useState(false);
  const [railCollapsed, setRailCollapsed] = useState(false);
  const [tab, setTab] = useState<WorkspaceTab>('overview');
  const [group, setGroup] = useState<string | null>(null);
  const [search, setSearch] = useState('');
  const [overlays, setOverlays] = useState<Overlay[]>([]);
  const overlay: Overlay | undefined = overlays[overlays.length - 1];
  const [profile, setProfile] = useState<string>();
  useAppearanceProfile(profile);

  const catalogQuery = useWorkspaceQuery(getCatalog);
  const refreshedLocale = useRef(locale);
  const refreshCatalog = catalogQuery.refresh;
  useEffect(() => {
    if (locale === refreshedLocale.current) return;
    refreshedLocale.current = locale;
    refreshCatalog();
  }, [locale, refreshCatalog]);
  const catalog =
    'data' in catalogQuery.state ? (catalogQuery.state.data ?? null) : null;
  const routes = useMemo(() => indexRoutes(catalog ?? []), [catalog]);
  const route = tab === 'catalog' ? null : readRoutes[tab];
  const request = useMemo(() => {
    if (!route || !catalog) {
      return null;
    }
    return () => invoke(routes.get(route), {query: profileQuery(profile)});
  }, [catalog, profile, route, routes]);
  const {state, refresh} = useWorkspaceQuery(request);
  const loading = state.status === 'loading';
  const error = state.status === 'error' ? state.error : undefined;
  const data = state.status === 'success' ? state.data : undefined;

  const selectTab = useCallback((next: WorkspaceTab) => {
    setTab(next);
    setOverlays([]);
    setGroup(null);
    setSearch('');
  }, []);

  const pushOverlay = useCallback(
    (next: Overlay) => setOverlays((stack) => [...stack, next]),
    [],
  );
  const closeOverlay = useCallback(
    () =>
      setOverlays((stack) =>
        stack[stack.length - 1] === overlay ? stack.slice(0, -1) : stack,
      ),
    [overlay],
  );

  const closeGroup = useCallback(() => {
    setGroup(null);
    setSearch('');
  }, []);

  const back = useCallback(() => {
    if (overlay?.kind === 'files' || overlay?.kind === 'terminal') {
      return false;
    }
    if (overlay) {
      closeOverlay();
    } else if (group) {
      closeGroup();
    } else if (tab !== 'overview') {
      selectTab('overview');
    } else {
      closeWorkspace();
    }
    return true;
  }, [closeGroup, closeOverlay, group, overlay, selectTab, tab]);

  useEffect(() => {
    const subscription = BackHandler.addEventListener('hardwareBackPress', back);
    return () => subscription.remove();
  }, [back]);

  const openDetail = useCallback(
    (target: DetailTarget) => pushOverlay({kind: 'detail', target}),
    [pushOverlay],
  );
  const openEntry = useCallback(
    (entry: Entry) => pushOverlay({kind: 'operation', entry}),
    [pushOverlay],
  );
  const openForm = useCallback(
    (form: BusinessForm) => pushOverlay({kind: 'form', form}),
    [pushOverlay],
  );

  function openJobEditor(jobProfile = profile, job?: Dict) {
    pushOverlay({kind: 'jobEditor', profile: jobProfile, job});
  }

  function openSkillEditor() {
    const entry = routes.get('POST /api/skills');
    if (!entry || entry.unavailable) return;
    openForm({
      title: t('mobile.workspaceItems.newSkill'),
      action: t('mobile.workspace.createSkill'),
      detail: t('mobile.groups.profile', {
        v1: String(profile || t('mobile.groups.currentGateway')),
      }),
      entry,
      fields: [
        {key: 'name', label: t('mobile.workspace.skillName'), required: true},
        {key: 'category', label: t('mobile.workspace.categoryOptional')},
        {
          key: 'content',
          label: t('mobile.workspace.skillMdContent'),
          required: true,
          multiline: true,
        },
      ],
      build: ({name, category, content}) => ({
        query: profileQuery(profile),
        body: {
          name: name.trim(),
          content,
          ...(category.trim() ? {category: category.trim()} : {}),
        },
      }),
    });
  }

  async function openProjectSession(id: string, sessionProfile?: string) {
    try {
      const owner =
        sessionProfile ||
        profile ||
        plainText(
          record(await invoke(routes.get('GET /api/profiles/active'))).current,
        );
      if (!owner)
        throw new Error(t('mobile.workspace.theServerDidNotReturnTheProfileFor'));
      await getHermesWorkspace().openConversation(owner, id);
    } catch (cause) {
      Alert.alert(
        t('mobile.workspace.couldNotOpenTheProjectChat'),
        errorMessage(cause),
      );
    }
  }

  function openOfficial(destination: OfficialDestinationId) {
    openOfficialDestination(destination).catch((cause: unknown) =>
      Alert.alert(t('mobile.workspace.couldNotOpenTheWebapp'), errorMessage(cause)),
    );
  }

  function navigateRail(destination: WorkspaceDestination) {
    setDrawer(false);
    if (
      destination === 'skills' ||
      destination === 'jobs' ||
      destination === 'catalog'
    ) {
      selectTab(destination);
    } else if (destination === 'newSession') {
      closeWorkspace();
    } else {
      setOverlays([{kind: destination}]);
    }
  }

  function renderContent() {
    if (overlay?.kind === 'language')
      return <LanguageScreen onBack={closeOverlay} />;
    if (overlay?.kind === 'appearance') {
      return (
        <BusinessPage title={t('settings.appearance.title')} onBack={closeOverlay}>
          <AppearanceSettings profile={profile} />
        </BusinessPage>
      );
    }
    if (catalogQuery.state.status === 'error') {
      return (
        <Feedback
          tone="error"
          message={catalogQuery.state.error}
          onRetry={catalogQuery.refresh}
        />
      );
    }
    if (!catalog) {
      return (
        <Feedback
          tone="loading"
          message={t('mobile.workspace.loadingFeatureCatalog')}
        />
      );
    }
    const detailProps = {
      byRoute: routes,
      onBack: closeOverlay,
      onWrite: refresh,
      onOpenForm: openForm,
    };
    if (overlay?.kind === 'kanban')
      return overlay.slug && overlay.task ? (
        <KanbanTaskScreen
          key={`${overlay.slug}:${overlay.task}`}
          {...detailProps}
          profile={profile}
          board={overlay.slug}
          id={overlay.task}
        />
      ) : (
        <KanbanScreen
          key={overlay.slug ?? 'boards'}
          {...detailProps}
          profile={profile}
          slug={overlay.slug}
          onOpenBoard={(slug) => pushOverlay({kind: 'kanban', slug})}
          onOpenTask={(task) =>
            pushOverlay({kind: 'kanban', slug: overlay.slug, task})
          }
        />
      );
    if (overlay?.kind === 'achievements')
      return (
        <AchievementsScreen
          key={overlay.sessionId ?? 'achievements'}
          {...detailProps}
          profile={overlay.owner || profile}
          sessionId={overlay.sessionId}
        />
      );
    if (overlay?.kind === 'knowledge')
      return (
        <KnowledgeScreen
          {...detailProps}
          profile={profile}
          onOpenNode={(nodeId) => pushOverlay({kind: 'starmap', nodeId})}
        />
      );
    if (overlay?.kind === 'maintenance')
      return (
        <MaintenanceScreen
          {...detailProps}
          profile={profile}
          mode={overlay.screen}
        />
      );
    if (overlay?.kind === 'artifacts')
      return (
        <ArtifactsScreen
          key={overlay.target?.session.id || 'recent'}
          {...detailProps}
          profile={profile}
          target={overlay.target}
          onOpen={(target) => pushOverlay({kind: 'artifacts', target})}
          onOpenSession={openProjectSession}
          onOpenBadges={(session) =>
            pushOverlay({
              kind: 'achievements',
              sessionId: session.id,
              owner: session.profile,
            })
          }
        />
      );
    if (overlay?.kind === 'starmap')
      return (
        <StarmapScreen
          key={overlay.nodeId || 'graph'}
          {...detailProps}
          profile={profile}
          nodeId={overlay.nodeId}
          onOpenNode={(nodeId) => pushOverlay({kind: 'starmap', nodeId})}
        />
      );
    if (overlay?.kind === 'gateways')
      return (
        <GatewaysScreen
          {...detailProps}
          onBrowser={() => openOfficial('relay')}
          onOpenProfile={(name) => pushOverlay({kind: 'profiles', name})}
        />
      );
    if (overlay?.kind === 'groups')
      return (
        <GroupsScreen
          key={overlay.roomId ?? (overlay.create ? 'create-group' : 'groups')}
          {...detailProps}
          profile={profile}
          roomId={overlay.roomId}
          createMode={overlay.create}
          onOpenRoom={(roomId) => {
            if (overlay.create)
              setOverlays((stack) =>
                stack[stack.length - 1] === overlay
                  ? [...stack.slice(0, -1), {kind: 'groups', roomId}]
                  : stack,
              );
            else pushOverlay({kind: 'groups', roomId});
          }}
          onCreate={() => pushOverlay({kind: 'groups', create: true})}
          onBrowser={() => openOfficial('groups')}
        />
      );
    if (overlay?.kind === 'relayAccounts')
      return (
        <RelayAccountsScreen
          {...detailProps}
          onOpenOfficial={() => openOfficial('messaging')}
        />
      );
    if (overlay?.kind === 'form')
      return (
        <BusinessFormScreen
          form={overlay.form}
          onBack={closeOverlay}
          onWrite={refresh}
        />
      );
    if (overlay?.kind === 'profiles')
      return (
        <ProfilesScreen
          key={overlay.name ?? 'profiles'}
          {...detailProps}
          selected={profile}
          onPick={setProfile}
          name={overlay.name}
          onOpenProfile={(name) => pushOverlay({kind: 'profiles', name})}
          onRemoved={(name) =>
            setProfile((current) => (current === name ? undefined : current))
          }
          onRenamed={(old, name) => {
            setProfile((current) => (current === old ? name : current));
            setOverlays((stack) =>
              stack.map((item) =>
                item.kind === 'profiles' && item.name === old
                  ? {...item, name}
                  : item,
              ),
            );
          }}
          onOpenModels={(name) => openDetail({kind: 'models', profile: name})}
        />
      );
    if (overlay?.kind === 'jobEditor')
      return (
        <JobEditorScreen
          {...detailProps}
          profile={overlay.profile}
          job={overlay.job}
        />
      );
    if (overlay?.kind === 'mcp')
      return (
        <McpScreen
          key={JSON.stringify(overlay.target)}
          {...detailProps}
          profile={profile}
          target={overlay.target}
          onOpen={(target) => pushOverlay({kind: 'mcp', target})}
          onAuthorize={() => openOfficial('mcp')}
        />
      );
    if (overlay?.kind === 'skillsHub')
      return (
        <SkillHubScreen
          key={plainText(overlay.selected?.identifier)}
          {...detailProps}
          profile={profile}
          selected={overlay.selected}
          onOpenSkill={(selected) => pushOverlay({kind: 'skillsHub', selected})}
        />
      );
    if (overlay?.kind === 'projects')
      return (
        <ProjectsScreen
          key={overlay.id ?? 'projects'}
          {...detailProps}
          profile={profile}
          id={overlay.id}
          onOpenProject={(id) => pushOverlay({kind: 'projects', id})}
          onOpenFolder={(name, path) =>
            pushOverlay({kind: 'files', initial: {name, path}})
          }
          onOpenSession={openProjectSession}
        />
      );
    if (overlay?.kind === 'ops')
      return (
        <OpsScreen
          key={overlay.screen || 'ops'}
          {...detailProps}
          profile={profile}
          screen={overlay.screen}
          onOpenLogs={() => pushOverlay({kind: 'ops', screen: 'logs'})}
          onOpenWebhooks={() => pushOverlay({kind: 'webhooks'})}
          onOpenMessaging={() => pushOverlay({kind: 'messaging'})}
          onOpenGateways={() => pushOverlay({kind: 'gateways'})}
          onOpenMaintenance={(screen) => pushOverlay({kind: 'maintenance', screen})}
        />
      );
    if (overlay?.kind === 'webhooks')
      return (
        <WebhooksScreen
          key={overlay.name ?? 'webhooks'}
          {...detailProps}
          profile={profile}
          name={overlay.name}
          onOpenHook={(name) => pushOverlay({kind: 'webhooks', name})}
        />
      );
    if (overlay?.kind === 'messaging')
      return (
        <MessagingScreen
          key={overlay.id ?? 'messaging'}
          {...detailProps}
          profile={profile}
          id={overlay.id}
          onOpenPlatform={(id) => pushOverlay({kind: 'messaging', id})}
          onOpenRelay={() => pushOverlay({kind: 'relayAccounts'})}
        />
      );
    if (overlay?.kind === 'media') return <MediaToolsScreen onBack={closeOverlay} />;
    if (overlay?.kind === 'live')
      return (
        <LiveScreen
          profile={profile}
          onBack={closeOverlay}
          onOpenBoard={(slug) => pushOverlay({kind: 'kanban', slug})}
        />
      );
    if (overlay?.kind === 'files') {
      return (
        <FileBrowserScreen
          byRoute={routes}
          initial={overlay.initial}
          onBack={closeOverlay}
        />
      );
    }
    if (overlay?.kind === 'operation') {
      const entry =
        catalog.find((item) => item.id === overlay.entry.id) ?? overlay.entry;
      return (
        <OperationScreen
          key={entry.id}
          entry={entry}
          onBack={closeOverlay}
          onWrite={refresh}
        />
      );
    }
    if (overlay?.kind === 'detail') {
      return (
        <DetailScreen
          target={overlay.target}
          {...detailProps}
          onEdit={openJobEditor}
          onAuthorize={() => openOfficial('providers')}
        />
      );
    }

    const selectedGroup = group
      ? catalog.find((entry) => catalogGroupId(entry) === group)
      : undefined;
    const heading = (
      <PageHeading
        title={selectedGroup ? catalogGroupLabel(selectedGroup) : tabLabels[tab]}
        loading={loading}
        onBack={
          group
            ? closeGroup
            : tab === 'jobs'
              ? () => selectTab('overview')
              : undefined
        }
        close={tab === 'jobs'}
        onRefresh={tab !== 'catalog' ? refresh : undefined}
      />
    );

    if (tab === 'catalog') {
      return (
        <>
          {heading}
          <CatalogScreen
            entries={catalog}
            group={group}
            search={search}
            onSearch={setSearch}
            onOpenGroup={(next) => {
              setGroup(next);
              setSearch('');
            }}
            onOpenEntry={openEntry}
          />
        </>
      );
    }

    const createEntry = routes.get(
      tab === 'jobs' ? 'POST /api/cron/jobs' : 'POST /api/skills',
    );
    const createItem = tab === 'jobs' ? () => openJobEditor() : openSkillEditor;
    return (
      <View
        style={[
          styles.page,
          tab === 'jobs' && styles.jobsPane,
          tab === 'jobs' && {borderColor: colors.line},
        ]}
      >
        {tab === 'skills' ? (
          <WorkspacePaneTabs
            items={[
              {
                id: 'skills',
                label: t('skills.tabSkills'),
                selected: true,
                onPress: refresh,
              },
              {
                id: 'connectors',
                label: t('settings.billing.freeTier.connectors'),
                selected: false,
                onPress: () => pushOverlay({kind: 'mcp'}),
              },
            ]}
          />
        ) : (
          heading
        )}
        {tab === 'overview' ? (
          <OverviewScreen
            routes={routes}
            data={data}
            error={error}
            loading={loading}
            onRefresh={refresh}
            profile={profile}
            onOpenModels={() => openDetail({kind: 'models', profile})}
            onOpenUsage={() => openDetail({kind: 'usage', profile})}
            onOpenFiles={() => pushOverlay({kind: 'files'})}
            onOpenTerminal={() => pushOverlay({kind: 'terminal'})}
            onOpenProfiles={() => pushOverlay({kind: 'profiles'})}
            onOpenProjects={() => pushOverlay({kind: 'projects'})}
            onOpenMcp={() => pushOverlay({kind: 'mcp'})}
            onOpenOps={() => pushOverlay({kind: 'ops'})}
            onOpenMedia={() => pushOverlay({kind: 'media'})}
            onOpenLive={() => pushOverlay({kind: 'live'})}
            onOpenArtifacts={() => pushOverlay({kind: 'artifacts'})}
            onOpenStarmap={() => pushOverlay({kind: 'starmap'})}
            onOpenGroups={() => pushOverlay({kind: 'groups'})}
            onOpenKanban={() => pushOverlay({kind: 'kanban'})}
            onOpenAchievements={() => pushOverlay({kind: 'achievements'})}
            onOpenKnowledge={() => pushOverlay({kind: 'knowledge'})}
            onOpenLanguage={() => pushOverlay({kind: 'language'})}
            onOpenAppearance={() => pushOverlay({kind: 'appearance'})}
          />
        ) : (
          <WorkspaceItemsScreen
            kind={tab}
            data={data}
            error={error}
            loading={loading}
            search={search}
            onSearch={setSearch}
            onRefresh={refresh}
            onOpenDetail={openDetail}
            profile={profile}
            onCreate={
              createEntry && !createEntry.unavailable ? createItem : undefined
            }
            onOpenHub={
              tab === 'skills' ? () => pushOverlay({kind: 'skillsHub'}) : undefined
            }
          />
        )}
      </View>
    );
  }

  if (overlay?.kind === 'terminal') {
    return <TerminalScreen onBack={closeOverlay} />;
  }

  const railVisible = docked ? !railCollapsed : drawer;
  const editorVisible = overlay?.kind === 'form' || overlay?.kind === 'jobEditor';
  const activeRail: WorkspaceDestination | undefined =
    overlay?.kind === 'skillsHub' || overlay?.kind === 'mcp'
      ? 'skills'
      : overlay?.kind === 'messaging' || overlay?.kind === 'relayAccounts'
        ? 'messaging'
        : overlay?.kind === 'files' ||
            overlay?.kind === 'profiles' ||
            overlay?.kind === 'projects' ||
            overlay?.kind === 'groups' ||
            overlay?.kind === 'kanban' ||
            overlay?.kind === 'live' ||
            overlay?.kind === 'artifacts'
          ? overlay.kind
          : overlay?.kind === 'jobEditor' ||
              (overlay?.kind === 'detail' && overlay.target.kind === 'job')
            ? 'jobs'
            : !overlay && tab !== 'overview'
              ? tab
              : undefined;
  const rail = {
    active: activeRail,
    profile,
    width: railWidth,
    onNavigate: navigateRail,
    disabled: editorVisible,
  };

  return (
    <View style={[styles.root, {backgroundColor: colors.bg}]}>
      <View
        style={[
          styles.header,
          {borderColor: colors.line, backgroundColor: colors.panel},
        ]}
      >
        <Button
          label={t(railVisible ? 'titlebar.hideSidebar' : 'titlebar.showSidebar')}
          icon="layout-sidebar-left"
          iconOnly
          disabled={editorVisible}
          onPress={() =>
            docked ? setRailCollapsed(!railCollapsed) : setDrawer(!drawer)
          }
        />
        <View style={styles.spacer} />
        <Button
          label={t('commandCenter.nav.settings.title')}
          icon="settings-gear"
          iconOnly
          disabled={editorVisible}
          onPress={() => selectTab('overview')}
        />
        <Button
          label={t('mobile.workspace.backToChat')}
          icon="arrow-left"
          iconOnly
          onPress={closeWorkspace}
        />
      </View>
      <View style={styles.body}>
        {docked && railVisible ? <WorkspaceRail {...rail} /> : null}
        <View style={styles.content}>{renderContent()}</View>
      </View>
      {!docked ? (
        <WorkspaceRail {...rail} open={drawer} onClose={() => setDrawer(false)} />
      ) : null}
    </View>
  );
}

const styles = StyleSheet.create({
  root: {flex: 1},
  body: {flex: 1, flexDirection: 'row'},
  content: {flex: 1},
  page: {flex: 1},
  jobsPane: {
    alignSelf: 'center',
    width: '94%',
    maxWidth: 680,
    marginVertical: spacing.sm,
    borderRadius: 4,
    borderWidth: StyleSheet.hairlineWidth,
  },
  header: {
    minHeight: touchTarget,
    paddingHorizontal: spacing.xs,
    flexDirection: 'row',
    alignItems: 'center',
    borderBottomWidth: StyleSheet.hairlineWidth,
  },
  spacer: {flex: 1},
});
