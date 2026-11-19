import {t, useI18n} from '../../../shared/i18n';
import {useMemo, useState} from 'react';
import {Alert, View} from 'react-native';

import {Button} from '../../../shared/ui/Button';
import {ListRow} from '../../../shared/ui/ListRow';
import {ProfileArchiveActions} from '../../resources/ProfileArchiveActions';
import {BusinessPage} from '../components/BusinessPage';
import {
  Card,
  Fact,
  Heading,
  Notice,
  ResourceStatus,
  detailStyles as styles,
} from '../components/DetailContent';
import {useConfirmedAction} from '../hooks/useConfirmedAction';
import {useDetailQuery} from '../hooks/useDetailQuery';
import {invoke} from '../services/workspaceApi';
import type {BusinessField, DetailScreenProps} from '../types';
import {plainText as plain, record, requireAccepted, rows} from '../utils/data';

const emptyInput = {};
const profileRoute = '/api/profiles/{name}';

type ProfileProps = DetailScreenProps & {
  selected?: string;
  onPick: (name?: string) => void;
  onOpenProfile: (name: string) => void;
  onOpenModels: (name: string) => void;
  onRenamed: (name: string, updated: string) => void;
  onRemoved: (name: string) => void;
  name?: string;
};

export function ProfilesScreen({
  selected,
  onPick,
  onOpenProfile,
  onOpenModels,
  onRenamed,
  onRemoved,
  name,
  byRoute,
  onBack,
  onWrite,
  onOpenForm,
}: ProfileProps) {
  useI18n();
  const [revision, setRevision] = useState(0);
  const refresh = () => setRevision((value) => value + 1);
  const {confirm, busy, feedback} = useConfirmedAction(onWrite, refresh);
  const roster = useDetailQuery(
    byRoute.get('GET /api/profiles'),
    emptyInput,
    revision,
  );
  const active = useDetailQuery(
    byRoute.get('GET /api/profiles/active'),
    emptyInput,
    revision,
  );
  const pathInput = useMemo(() => ({path: {name}}), [name]);
  const soul = useDetailQuery(
    byRoute.get(`GET ${profileRoute}/soul`),
    pathInput,
    revision,
    !!name,
  );
  const profiles = rows(roster.value, ['profiles']);
  const profile = profiles.find((item) => item.name === name);
  const defaultProfile = plain(record(active.value).active);
  const serverProfile = plain(record(active.value).current);
  const create = byRoute.get('POST /api/profiles');
  const setActive = byRoute.get('POST /api/profiles/active');
  const rename = byRoute.get(`PATCH ${profileRoute}`);
  const description = byRoute.get(`PUT ${profileRoute}/description`);
  const writeSoul = byRoute.get(`PUT ${profileRoute}/soul`);
  const remove = byRoute.get(`DELETE ${profileRoute}`);
  const shownName = plain(profile?.display_name) || name || '';

  function openTextForm(
    route: typeof rename,
    title: string,
    key: string,
    field: BusinessField,
    onSuccess?: (value: unknown) => void,
  ) {
    if (!route || route.unavailable || !name) return;
    onOpenForm({
      title,
      action: t('common.save'),
      detail: t('mobile.profiles.profile', {v1: String(shownName)}),
      entry: route,
      fields: [field],
      build: (values) => ({...pathInput, body: {[key]: values[field.key]}}),
      onSuccess,
    });
  }

  function createProfile() {
    if (!create || create.unavailable) return;
    onOpenForm({
      title: t('profiles.newProfile'),
      action: t('mobile.profiles.createProfile'),
      entry: create,
      fields: [
        {
          key: 'name',
          label: t('mobile.profiles.profileName'),
          required: true,
          placeholder: t('mobile.profiles.forExampleResearch'),
        },
        {
          key: 'description',
          label: t('mobile.achievements.description'),
          multiline: true,
        },
        {
          key: 'clone',
          label: t('mobile.profiles.copyFrom'),
          initial: '',
          choices: [
            {value: '', label: t('mobile.profiles.createBlankProfile')},
            ...profiles.flatMap((item) =>
              plain(item.name)
                ? [
                    {
                      value: plain(item.name),
                      label: plain(item.display_name) || plain(item.name),
                    },
                  ]
                : [],
            ),
          ],
        },
      ],
      build: ({name: next, description: note, clone}) => ({
        body: {
          name: next.trim(),
          description: note,
          ...(clone ? {clone_from: clone} : {}),
        },
      }),
    });
  }

  function setDefault() {
    if (!name) return;
    confirm(
      t('mobile.profiles.setAsServerDefaultProfile'),
      t('mobile.profiles.usedForFutureCliAndGatewayLaunchesOn', {
        v1: String(shownName),
      }),
      async () => {
        const result = requireAccepted(
          await invoke(setActive, {body: {name}}, true),
        );
        if (result.active !== name)
          throw new Error(
            t('mobile.profiles.theServerDidNotConfirmTheDefaultProfile'),
          );
      },
    );
  }

  function deleteProfile() {
    confirm(
      t('mobile.profiles.deleteProfile'),
      t('mobile.profiles.theServerWillRemoveTheProfileFolderAnd', {
        v1: String(shownName),
      }),
      async () => {
        const result = requireAccepted(await invoke(remove, pathInput, true));
        if (result.ok !== true)
          throw new Error(t('mobile.profiles.theServerDidNotConfirmDeletion'));
        if (name) onRemoved(name);
        if (result.settlement_pending === true) {
          Alert.alert(
            t('mobile.profiles.profileDeleted'),
            t('mobile.profiles.theServerIsFinishingIdentityCleanupForThis'),
          );
        }
        onBack();
      },
    );
  }

  return (
    <BusinessPage
      title={
        name ? t('mobile.profiles.profileDetails') : t('sidebar.filter.profile')
      }
      onBack={onBack}
      onRefresh={refresh}
      loading={roster.loading || active.loading || soul.loading}
    >
      <ResourceStatus resource={roster} label={t('profiles.title')} />
      <ResourceStatus resource={active} label={t('mobile.profiles.activeProfile')} />
      {!name ? (
        <>
          <Card>
            <Fact
              label={t('mobile.profiles.phoneWorkspace')}
              value={selected || t('mobile.profiles.followCurrentGateway')}
            />
            <Fact label={t('mobile.groups.currentGateway')} value={serverProfile} />
            <Fact
              label={t('mobile.profiles.serverDefault')}
              value={defaultProfile}
            />
            <View style={styles.actions}>
              {selected ? (
                <Button
                  label={t('mobile.profiles.followCurrentGateway')}
                  onPress={() => onPick()}
                />
              ) : null}
              {create && !create.unavailable ? (
                <Button
                  label={t('mobile.profiles.newProfile2')}
                  variant="accent"
                  onPress={createProfile}
                />
              ) : null}
            </View>
            <ProfileArchiveActions onImported={refresh} />
          </Card>
          {profiles.map((item) => {
            const id = plain(item.name);
            return id ? (
              <ListRow
                key={id}
                title={plain(item.display_name) || id}
                subtitle={[
                  selected === id ? t('mobile.profiles.selectedForWorkspace') : '',
                  defaultProfile === id ? t('mobile.profiles.serverDefault') : '',
                  plain(item.model),
                  plain(item.description),
                ]
                  .filter(Boolean)
                  .join(' · ')}
                onPress={() => onOpenProfile(id)}
              />
            ) : null;
          })}
          {!roster.loading && !roster.error && !profiles.length ? (
            <Notice>{t('mobile.profiles.noProfilesAvailable')}</Notice>
          ) : null}
        </>
      ) : profile ? (
        <>
          <Card>
            <Heading>{shownName}</Heading>
            <Fact label={t('mobile.profiles.profileName')} value={name} />
            <Fact
              label={t('mobile.achievements.description')}
              value={plain(profile.description)}
            />
            <Fact
              label={t('profiles.modelLabel')}
              value={[plain(profile.provider), plain(profile.model)]
                .filter(Boolean)
                .join(' · ')}
            />
            <Fact
              label={t('mobile.ops.messagingGateway')}
              value={
                profile.gateway_running === true
                  ? t('agents.running')
                  : t('mobile.ops.stopped')
              }
            />
            <View style={styles.actions}>
              <Button
                label={
                  selected === name
                    ? t('mobile.profiles.selectedForWorkspace')
                    : t('mobile.profiles.useInPhoneWorkspace')
                }
                variant="accent"
                disabled={selected === name || busy}
                onPress={() => onPick(name)}
              />
              <Button
                label={t('mobile.modelDetails.modelOptions')}
                disabled={busy}
                onPress={() => onOpenModels(name)}
              />
              {setActive && !setActive.unavailable && defaultProfile !== name ? (
                <Button
                  label={t('mobile.profiles.setServerDefault')}
                  disabled={busy}
                  onPress={setDefault}
                />
              ) : null}
              {rename && !rename.unavailable ? (
                <Button
                  label={t('profiles.rename')}
                  disabled={busy}
                  onPress={() =>
                    openTextForm(
                      rename,
                      t('mobile.profiles.renameProfile'),
                      'new_name',
                      {
                        key: 'text',
                        label: profile.is_default
                          ? t('mobile.profiles.displayName')
                          : t('mobile.profileArchiveActions.newProfileName'),
                        initial: shownName,
                        required: true,
                      },
                      (value) => {
                        const id = plain(record(value).name);
                        if (id && id !== name) onRenamed(name, id);
                      },
                    )
                  }
                />
              ) : null}
              {description && !description.unavailable ? (
                <Button
                  label={t('mobile.groups.editDescription')}
                  disabled={busy}
                  onPress={() =>
                    openTextForm(
                      description,
                      t('mobile.profiles.editProfileDescription'),
                      'description',
                      {
                        key: 'text',
                        label: t('mobile.achievements.description'),
                        initial: plain(profile.description),
                        multiline: true,
                      },
                    )
                  }
                />
              ) : null}
            </View>
            <ProfileArchiveActions profile={name} />
          </Card>
          <Heading>SOUL.md</Heading>
          <ResourceStatus
            resource={soul}
            label={t('mobile.profiles.profileInstructions')}
          />
          {!soul.loading && !soul.error && (
            <Card>
              <Fact
                label={t('mobile.profiles.profileInstructions')}
                value={plain(record(soul.value).content)}
              />
              {record(soul.value).exists === false ? (
                <Notice>{t('mobile.profiles.thisProfileHasNoSoulMd')}</Notice>
              ) : null}
              {writeSoul && !writeSoul.unavailable ? (
                <Button
                  label={t('profiles.editSoul')}
                  disabled={busy}
                  onPress={() =>
                    openTextForm(writeSoul, t('profiles.editSoul'), 'content', {
                      key: 'text',
                      label: t('mobile.profiles.profileInstructions'),
                      initial:
                        typeof record(soul.value).content === 'string'
                          ? String(record(soul.value).content)
                          : '',
                      multiline: true,
                    })
                  }
                />
              ) : null}
            </Card>
          )}
          {remove &&
          !remove.unavailable &&
          profile.is_default !== true &&
          name !== 'default' ? (
            <Button
              label={t('mobile.profiles.deleteProfile2')}
              disabled={busy}
              onPress={deleteProfile}
            />
          ) : null}
          {!!feedback && <Notice>{feedback}</Notice>}
        </>
      ) : !roster.loading && !roster.error ? (
        <Notice>{t('mobile.profiles.thisProfileNoLongerExists')}</Notice>
      ) : null}
    </BusinessPage>
  );
}
