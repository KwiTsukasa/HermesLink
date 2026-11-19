import {t, useI18n} from '../../../shared/i18n';
import {useMemo, useState} from 'react';
import {RefreshControl, ScrollView, Text, View} from 'react-native';

import {useTheme} from '../../../shared/theme/ThemeProvider';
import {Button} from '../../../shared/ui/Button';
import {
  Card,
  Heading,
  Notice,
  ResourceStatus,
  detailStyles as styles,
} from '../components/DetailContent';
import {PageHeading} from '../components/PageHeading';
import {useConfirmedAction} from '../hooks/useConfirmedAction';
import {useDetailQuery} from '../hooks/useDetailQuery';
import {invoke} from '../services/workspaceApi';
import type {DetailScreenProps} from '../types';
import {
  plainText as plain,
  profileQuery,
  record,
  requireAccepted,
  rows,
} from '../utils/data';

export function SkillDetailsScreen({
  name,
  profile,
  byRoute,
  onBack,
  onWrite,
  onOpenForm,
}: DetailScreenProps & {name: string; profile?: string}) {
  useI18n();
  const c = useTheme();
  const [revision, setRevision] = useState(0);
  const refresh = () => setRevision((value) => value + 1);
  const {confirm, busy, feedback} = useConfirmedAction(onWrite, refresh);
  const profileInput = useMemo(() => ({query: profileQuery(profile)}), [profile]);
  const contentInput = useMemo(
    () => ({query: {...profileQuery(profile), name}}),
    [name, profile],
  );
  const content = useDetailQuery(
    byRoute.get('GET /api/skills/content'),
    contentInput,
    revision,
  );
  const status = useDetailQuery(
    byRoute.get('GET /api/skills'),
    profileInput,
    revision,
  );
  const skill = rows(status.value, ['skills']).find((item) => item.name === name);
  const enabled = skill?.enabled;
  const toggle = byRoute.get('PUT /api/skills/toggle');
  const lock = byRoute.get('PUT /api/skills/lock');
  const update = byRoute.get('PUT /api/skills/content');
  const profileLabel = profile || t('mobile.modelDetails.currentProfile');
  const label = enabled
    ? t('mobile.skillDetails.disableSkill')
    : t('mobile.skillDetails.enableSkill');
  const markdown = record(content.value).content;
  const loading = content.loading || status.loading;

  function toggleSkill() {
    confirm(
      label,
      t('mobile.jobDetails.profile', {v1: String(name), v2: String(profileLabel)}),
      async () => {
        const result = record(
          await invoke(
            toggle,
            {...profileInput, body: {name, enabled: !enabled}},
            true,
          ),
        );
        if (result.ok !== true || result.enabled !== !enabled)
          throw new Error(
            t('mobile.skillDetails.theGatewayDidNotConfirmTheSkillStatus'),
          );
      },
    );
  }

  function lockSkill() {
    const locked = skill?.locked !== true;
    const action = locked
      ? t('mobile.skillDetails.lockSkill')
      : t('mobile.skillDetails.unlockSkill');
    confirm(
      action,
      t('mobile.jobDetails.profile', {v1: String(name), v2: String(profileLabel)}),
      async () => {
        const result = requireAccepted(
          await invoke(lock, {...profileInput, body: {name, locked}}, true),
        );
        if (result.name !== name || result.locked !== locked)
          throw new Error(
            t('mobile.skillDetails.theServerDidNotConfirmTheSkillLock'),
          );
      },
    );
  }

  return (
    <View style={styles.screen}>
      <PageHeading
        title={t('mobile.skillDetails.skillDetails')}
        onBack={onBack}
        onRefresh={refresh}
        loading={loading || busy}
      />
      <ScrollView
        contentContainerStyle={styles.content}
        refreshControl={
          <RefreshControl
            refreshing={loading}
            onRefresh={busy ? undefined : refresh}
            colors={[c.accent]}
            tintColor={c.accent}
          />
        }
      >
        <Card>
          <Text accessibilityRole="header" style={[styles.title, {color: c.ink}]}>
            {name}
          </Text>
          <Text style={[styles.caption, {color: c.sub}]}>
            {profileLabel}
            {plain(skill?.category) ? ` · ${plain(skill?.category)}` : ''}
          </Text>
          <ResourceStatus
            resource={status}
            label={t('mobile.skillDetails.skillStatus')}
          />
          {typeof enabled === 'boolean' && (
            <>
              <Text style={[styles.status, {color: c.accent}]}>
                {enabled ? t('messaging.enabled') : t('mobile.mcp.disabled')}
              </Text>
              {toggle && !toggle.unavailable && (
                <View style={styles.actions}>
                  <Button
                    label={busy ? t('messaging.telegramQr.applying') : label}
                    variant="accent"
                    disabled={busy || status.loading}
                    onPress={toggleSkill}
                  />
                </View>
              )}
              <View style={styles.actions}>
                {lock && !lock.unavailable && typeof skill?.locked === 'boolean' ? (
                  <Button
                    label={
                      skill.locked
                        ? t('mobile.skillDetails.unlockSkill')
                        : t('mobile.skillDetails.lockSkill')
                    }
                    disabled={busy || status.loading}
                    onPress={lockSkill}
                  />
                ) : null}
                {update &&
                !update.unavailable &&
                skill?.locked === false &&
                ['agent', 'external'].includes(plain(skill.provenance)) &&
                typeof markdown === 'string' &&
                !content.loading &&
                !content.error ? (
                  <Button
                    label={t('mobile.skillDetails.editSkillMd')}
                    disabled={busy}
                    onPress={() =>
                      onOpenForm({
                        title: t('mobile.skillDetails.editSkillMd'),
                        action: t('mobile.skillDetails.saveSkill'),
                        detail: t('mobile.jobDetails.profile', {
                          v1: String(name),
                          v2: String(profileLabel),
                        }),
                        entry: update,
                        fields: [
                          {
                            key: 'content',
                            label: t('mobile.skillDetails.skillContent'),
                            required: true,
                            multiline: true,
                            initial: markdown,
                          },
                        ],
                        build: ({content: next}) => ({
                          ...profileInput,
                          body: {name, content: next},
                        }),
                      })
                    }
                  />
                ) : null}
              </View>
            </>
          )}
          {!status.error && !status.loading && typeof enabled !== 'boolean' && (
            <Notice>
              {t('mobile.skillDetails.couldNotLoadAnEditableSkillStatus')}
            </Notice>
          )}
          {!!feedback && <Notice>{feedback}</Notice>}
        </Card>
        <Heading>{t('mobile.skillDetails.skillInstructions')}</Heading>
        <ResourceStatus
          resource={content}
          label={t('mobile.skillDetails.skillInstructions')}
        />
        {!content.loading &&
          !content.error &&
          (typeof markdown !== 'string' ? (
            <Notice>
              {t('mobile.skillDetails.theGatewayDidNotReturnSkillInstructions')}
            </Notice>
          ) : markdown.trim() ? (
            <Card>
              <Text selectable style={[styles.document, {color: c.ink}]}>
                {markdown}
              </Text>
            </Card>
          ) : (
            <Notice>{t('mobile.skillDetails.noSkillInstructions')}</Notice>
          ))}
      </ScrollView>
    </View>
  );
}
