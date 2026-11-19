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
  record,
  requireAccepted,
  rows,
  stringList,
} from '../utils/data';

export function SkillHubScreen({
  profile,
  selected,
  byRoute,
  onBack,
  onWrite,
  onOpenSkill,
}: DetailScreenProps & {
  profile?: string;
  selected?: Dict;
  onOpenSkill: (skill: Dict) => void;
}) {
  useI18n();
  const [search, setSearch] = useState('');
  const [query, setQuery] = useState('');
  const [showUpdates, setShowUpdates] = useState(false);
  const [scanStarted, setScanStarted] = useState(false);
  const [action, setAction] = useState<BackgroundAction & {installs: boolean}>();
  const [actionComplete, setActionComplete] = useState(false);
  const [installation, setInstallation] = useState<boolean>();
  const [revision, setRevision] = useState(0);
  const refresh = () => setRevision((value) => value + 1);
  const {confirm, busy, feedback, failed} = useConfirmedAction(onWrite, refresh);
  const input = useMemo(() => ({query: profileQuery(profile)}), [profile]);
  const identifier = plain(selected?.identifier);
  const detailInput = useMemo(
    () => ({query: {...profileQuery(profile), identifier}}),
    [profile, identifier],
  );
  const searchInput = useMemo(
    () => ({query: {...profileQuery(profile), q: query, limit: 40}}),
    [profile, query],
  );
  const official = useDetailQuery(
    byRoute.get('GET /api/skills/hub/official'),
    input,
    revision,
    !selected && !query,
  );
  const results = useDetailQuery(
    byRoute.get('GET /api/skills/hub/search'),
    searchInput,
    revision,
    !selected && !!query,
  );
  const updates = useDetailQuery(
    byRoute.get('GET /api/skills/hub/updates'),
    input,
    revision,
    !selected && showUpdates,
  );
  const preview = useDetailQuery(
    byRoute.get('GET /api/skills/hub/preview'),
    detailInput,
    revision,
    !!identifier,
  );
  const scan = useDetailQuery(
    byRoute.get('GET /api/skills/hub/scan'),
    detailInput,
    revision,
    !!identifier && scanStarted,
  );
  const inspect = record(preview.value);
  const findings = record(scan.value);
  const listing = query ? results : official;
  const install = byRoute.get('POST /api/skills/hub/install');
  const update = byRoute.get('POST /api/skills/hub/update');
  const uninstall = byRoute.get('POST /api/skills/hub/uninstall');
  const name = plain(inspect.name) || plain(selected?.name);
  const installed = installation ?? selected?.installed === true;

  function run(label: string, route: typeof install, body: Dict) {
    confirm(
      label,
      t('mobile.jobDetails.profile', {
        v1: String(name || t('skills.hub.pickerTitle')),
        v2: String(profile || t('mobile.groups.currentGateway')),
      }),
      async () => {
        const result = requireAccepted(await invoke(route, {...input, body}, true));
        const current = backgroundAction(result, label);
        if (!current)
          throw new Error(t('mobile.skillHub.theServerDidNotReturnASkillExecution'));
        setActionComplete(false);
        setAction({...current, installs: route === install});
        return t('mobile.skillHub.submitted');
      },
    );
  }

  return (
    <BusinessPage
      title={
        selected ? t('mobile.skillHub.hubSkillDetails') : t('skills.hub.pickerTitle')
      }
      onBack={onBack}
      onRefresh={refresh}
      loading={
        !!(listing.loading || preview.loading || scan.loading || updates.loading)
      }
    >
      {action ? (
        <ActionStatus
          key={`${action.name}:${action.pid ?? ''}`}
          action={action}
          byRoute={byRoute}
          onComplete={(successful) => {
            setActionComplete(true);
            if (successful && selected) setInstallation(action.installs);
            if (successful) refresh();
          }}
        />
      ) : null}
      {!!feedback && (!action || failed) ? <Notice>{feedback}</Notice> : null}
      {selected ? (
        <>
          <ResourceStatus
            label={t('mobile.skillHub.skillPreview')}
            resource={preview}
          />
          <Card>
            <Heading>{name || identifier}</Heading>
            <Fact
              label={t('catalog.about')}
              value={plain(inspect.description) || plain(selected.description)}
            />
            <Fact
              label={t('catalog.source')}
              value={[
                plain(inspect.source) || plain(selected.source),
                plain(inspect.trust_level) || plain(selected.trust_level),
              ]
                .filter(Boolean)
                .join(' · ')}
            />
            <Fact label={t('mobile.skillHub.identifier')} value={identifier} />
            <Fact
              label={t('artifacts.tabFiles')}
              value={stringList(inspect.files).join('\n')}
            />
            <Fact label="SKILL.md" value={plain(inspect.skill_md)} />
            {installed ? <Notice>{t('catalog.installed')}</Notice> : null}
          </Card>
          <Heading>{t('mobile.skillHub.securityScan')}</Heading>
          <ResourceStatus label={t('mobile.skillHub.scanResults')} resource={scan} />
          {scanStarted && !scan.loading && !scan.error ? (
            <Card>
              <Fact
                label={t('mobile.skillHub.verdict')}
                value={plain(findings.verdict)}
              />
              <Fact
                label={t('mobile.skillHub.policy')}
                value={plain(findings.policy)}
              />
              <Fact
                label={t('platform.teams.summary.heading')}
                value={plain(findings.summary) || plain(findings.policy_reason)}
              />
              {rows(findings.findings, [])
                .slice(0, 20)
                .map((item, index) => (
                  <Fact
                    key={index}
                    label={[plain(item.severity), plain(item.file)]
                      .filter(Boolean)
                      .join(' · ')}
                    value={plain(item.description)}
                  />
                ))}
            </Card>
          ) : null}
          <View style={styles.actions}>
            {byRoute.has('GET /api/skills/hub/scan') ? (
              <Button
                label={
                  scanStarted
                    ? t('mobile.skillHub.scanAgain')
                    : t('mobile.skillHub.scanSkill')
                }
                disabled={busy || scan.loading || !identifier}
                onPress={() => {
                  if (scanStarted) refresh();
                  else setScanStarted(true);
                }}
              />
            ) : null}
            {!installed && install && !install.unavailable && (
              <Button
                variant="accent"
                label={t('mobile.skillHub.installSkill')}
                disabled={
                  busy ||
                  (!!action && !actionComplete) ||
                  preview.loading ||
                  !!preview.error ||
                  findings.policy === 'block'
                }
                onPress={() =>
                  run(t('mobile.skillHub.installSkill'), install, {identifier})
                }
              />
            )}
            {installed && uninstall && !uninstall.unavailable && (
              <Button
                label={t('mobile.skillHub.uninstallSkill')}
                disabled={busy || (!!action && !actionComplete) || !name}
                onPress={() =>
                  run(t('mobile.skillHub.uninstallSkill'), uninstall, {name})
                }
              />
            )}
          </View>
        </>
      ) : (
        <>
          <SearchField
            placeholder={t('mobile.skillHub.searchSkillsHub')}
            value={search}
            onChange={setSearch}
            clearable
          />
          <View style={styles.actions}>
            <Button
              label={t('catalog.searchSkills')}
              variant="accent"
              disabled={!search.trim() || results.loading}
              onPress={() => setQuery(search.trim())}
            />
            {query ? (
              <Button
                label={t('mobile.mcp.officialCatalog')}
                onPress={() => {
                  setQuery('');
                  setSearch('');
                }}
              />
            ) : null}
            <Button
              label={
                showUpdates
                  ? t('mobile.skillHub.hideUpdates')
                  : t('mobile.skillHub.checkForUpdates')
              }
              onPress={() => setShowUpdates(!showUpdates)}
            />
          </View>
          {showUpdates && (
            <>
              <Heading>{t('mobile.skillHub.updatesForInstalledSkills')}</Heading>
              <ResourceStatus
                label={t('mobile.skillHub.updates')}
                resource={updates}
              />
              {rows(updates.value, ['updates']).map((item) => (
                <ListRow
                  key={plain(item.identifier) || plain(item.name)}
                  title={plain(item.name)}
                  subtitle={plain(item.source)}
                />
              ))}
              {!updates.loading &&
              !updates.error &&
              !rows(updates.value, ['updates']).length ? (
                <Notice>{t('mobile.skillHub.noUpdatesAvailable')}</Notice>
              ) : null}
              {rows(updates.value, ['updates']).length > 0 &&
              update &&
              !update.unavailable ? (
                <Button
                  label={t('mobile.skillHub.updateInstalledSkills')}
                  disabled={busy || (!!action && !actionComplete)}
                  onPress={() => run(t('mobile.skillHub.updateSkills'), update, {})}
                />
              ) : null}
            </>
          )}
          <Heading>
            {query
              ? t('mobile.skillHub.searchResults')
              : t('mobile.skillHub.officialSkills')}
          </Heading>
          <ResourceStatus label={t('mobile.skillHub.skills')} resource={listing} />
          {query && stringList(record(results.value).timed_out).length > 0 ? (
            <Notice>{t('mobile.skillHub.someSkillSourcesTimedOut')}</Notice>
          ) : null}
          {rows(listing.value, [query ? 'results' : 'skills']).map((item) => {
            const id = plain(item.identifier);
            const isInstalled =
              item.installed === true ||
              Object.prototype.hasOwnProperty.call(
                record(record(results.value).installed),
                id,
              );
            return id ? (
              <ListRow
                key={id}
                title={plain(item.name) || id}
                subtitle={[
                  isInstalled ? t('catalog.installed') : '',
                  plain(item.category) || plain(item.source),
                  plain(item.description),
                ]
                  .filter(Boolean)
                  .join(' · ')}
                onPress={() => onOpenSkill({...item, installed: isInstalled})}
              />
            ) : null;
          })}
          {!listing.loading &&
          !listing.error &&
          !rows(listing.value, [query ? 'results' : 'skills']).length ? (
            <Notice>{t('mobile.skillHub.noSkillsFound')}</Notice>
          ) : null}
        </>
      )}
    </BusinessPage>
  );
}
