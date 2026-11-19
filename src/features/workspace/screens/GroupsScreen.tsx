import {t, useI18n} from '../../../shared/i18n';
import {useMemo, useState} from 'react';
import {View} from 'react-native';

import {Button} from '../../../shared/ui/Button';
import {Input} from '../../../shared/ui/Input';
import {ListRow} from '../../../shared/ui/ListRow';
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
import {gatewayRoute, invoke} from '../services/workspaceApi';
import type {DetailScreenProps, Entry} from '../types';
import {
  plainText as plain,
  profileQuery,
  record,
  rows,
  stringList,
  textValue,
} from '../utils/data';

function operationId(prefix: string): string {
  return `${prefix}-${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 12)}`;
}

const activityNames: Record<string, string> = {
  get queued() {
    return t('agents.queued');
  },
  get running() {
    return t('mobile.groups.running');
  },
  get stopping() {
    return t('mobile.groups.stopping');
  },
};
const eventNames: Record<string, string> = {
  get 'message.user'() {
    return t('platform.telegram.user_fallback');
  },
  get 'message.member'() {
    return t('mobile.groups.member');
  },
  get 'room.renamed'() {
    return t('mobile.groups.renameGroup');
  },
  get 'room.context'() {
    return t('mobile.groups.updateGroupContext');
  },
  get 'room.stopped'() {
    return t('mobile.groups.stopTasks');
  },
};

export function GroupsScreen({
  profile,
  roomId,
  createMode,
  byRoute,
  onBack,
  onWrite,
  onOpenForm,
  onOpenRoom,
  onCreate,
  onBrowser,
}: DetailScreenProps & {
  profile?: string;
  roomId?: string;
  createMode?: boolean;
  onOpenRoom: (id: string) => void;
  onCreate: () => void;
  onBrowser: () => void;
}) {
  useI18n();
  const [revision, setRevision] = useState(0);
  const [offset, setOffset] = useState(0);
  const [includeDisbanded, setIncludeDisbanded] = useState(false);
  const [logCursor, setLogCursor] = useState<number>();
  const [name, setName] = useState('');
  const [seats, setSeats] = useState<string[]>([]);
  const [newRoomId] = useState(() => operationId('room'));
  const refresh = () => setRevision((value) => value + 1);
  const {confirm, busy, feedback} = useConfirmedAction(onWrite, refresh);
  const scope = useMemo(() => profileQuery(profile), [profile]);
  const capabilityInput = useMemo(() => ({body: scope}), [scope]);
  const roomsInput = useMemo(
    () => ({
      body: {...scope, include_disbanded: includeDisbanded, limit: 30, offset},
    }),
    [scope, includeDisbanded, offset],
  );
  const roomInput = useMemo(
    () => ({body: {...scope, room_id: roomId, include_disbanded: true}}),
    [scope, roomId],
  );
  const capabilities = useDetailQuery(
    gatewayRoute(byRoute, 'groups.capabilities'),
    capabilityInput,
    revision,
  );
  const roster = useDetailQuery(
    gatewayRoute(byRoute, 'groups.list'),
    roomsInput,
    revision,
    !roomId && !createMode,
  );
  const stateEntry = gatewayRoute(byRoute, 'groups.state');
  const detail = useDetailQuery(stateEntry, roomInput, revision, !!roomId);
  const result = record(detail.value);
  const room = record(result.room);
  const context = record(result.context);
  const driver = record(result.driver_status);
  const available = record(capabilities.value);
  const methods = stringList(available.methods);
  const seatable = stringList(available.seatable_profiles);
  const owned =
    room.room_id === roomId &&
    room.authority_gateway_id === available.authority_gateway_id &&
    !!room.authority_gateway_id;
  const disbanded = room.disbanded_at != null;
  const canWrite = owned && !disbanded && available.persistent_process === true;
  const members = rows(room.members, []);
  const activeTasks = rows(driver.member_activity, []);
  const latest = typeof room.latest_seq === 'number' ? room.latest_seq : 0;
  const logInput = useMemo(
    () => ({
      body: {
        ...scope,
        room_id: roomId,
        include_disbanded: true,
        since_seq: logCursor ?? Math.max(0, latest - 25),
        limit: 25,
      },
    }),
    [scope, roomId, logCursor, latest],
  );
  const log = useDetailQuery(
    gatewayRoute(byRoute, 'groups.log'),
    logInput,
    revision,
    !!roomId && room.room_id === roomId,
  );
  const page = record(log.value);
  const label = plain(room.name) || roomId || '';

  function route(method: string): Entry | undefined {
    const entry = gatewayRoute(byRoute, method);
    return entry && !entry.unavailable && methods.includes(method)
      ? entry
      : undefined;
  }

  const create = route('groups.create');
  const send = route('groups.send');
  const rename = route('groups.rename');
  const editContext = route('groups.context');
  const stop = route('groups.stop');
  const disband = route('groups.disband');

  async function freshRoom() {
    const now = record(await invoke(stateEntry, roomInput));
    const current = record(now.room);
    if (
      current.room_id !== roomId ||
      current.authority_gateway_id !== room.authority_gateway_id ||
      current.authority_epoch !== room.authority_epoch ||
      current.disbanded_at != null
    ) {
      throw new Error(
        t('mobile.groups.theGroupStateOrManagingGatewayChangedRefresh'),
      );
    }
    return now;
  }

  function createRoom() {
    if (!create || !name.trim() || seats.length < 2 || seats.length > 6) return;
    const id = newRoomId;
    const body = {
      ...scope,
      room_id: id,
      name: name.trim(),
      members: seats.map((seat, index) => ({
        profile: seat,
        member_id: `local-${index + 1}`,
        handle: `agent-${index + 1}`,
        display_name: seat,
      })),
    };
    confirm(
      t('mobile.groups.createGroup'),
      t('mobile.groups.members', {
        v1: String(body.name),
        v2: String(seats.join('、')),
      }),
      async () => {
        const created = record(record(await invoke(create, {body}, true)).room);
        const stored = rows(created.members, []).map((seat) => plain(seat.profile));
        if (
          created.room_id !== id ||
          created.name !== body.name ||
          stored.length !== seats.length ||
          !seats.every((seat) => stored.includes(seat))
        )
          throw new Error(t('mobile.groups.theServerDidNotConfirmTheNewGroup'));
        onOpenRoom(id);
      },
    );
  }

  function openEditor(entry: Entry, kind: 'name' | 'context' | 'message') {
    const action = {
      name: t('mobile.groups.saveGroupName'),
      context: t('mobile.groups.saveGroupContext'),
      message: t('mobile.groups.sendToGroup'),
    }[kind];
    const eventId = operationId('event');
    const destination = [
      label,
      t('mobile.groups.profile', {
        v1: String(profile || t('mobile.groups.currentGateway')),
      }),
      kind === 'message'
        ? t('mobile.groups.members2', {
            v1: String(
              members
                .map((item) => plain(item.display_name) || plain(item.profile))
                .filter(Boolean)
                .join('、'),
            ),
          })
        : '',
    ]
      .filter(Boolean)
      .join('\n');
    onOpenForm({
      title: action,
      action,
      detail: destination,
      entry,
      fields:
        kind === 'context'
          ? [
              {
                key: 'purpose',
                label: t('mobile.groups.groupPurpose'),
                initial: plain(context.purpose),
              },
              {
                key: 'notes',
                label: t('mobile.groups.sharedNotes'),
                initial: plain(context.notes),
                multiline: true,
              },
            ]
          : [
              {
                key: 'text',
                label:
                  kind === 'name'
                    ? t('mobile.groups.newGroupName')
                    : t('composer.message'),
                initial: kind === 'name' ? label : '',
                required: true,
                multiline: kind === 'message',
              },
            ],
      build: ({text, purpose, notes}) => {
        const body = {...scope, room_id: roomId, event_id: eventId};
        if (kind === 'name') return {body: {...body, name: text.trim()}};
        if (kind === 'context') return {body: {...body, purpose, notes}};
        if (encodeURIComponent(text).replace(/%[0-9A-F]{2}/g, 'a').length > 65536)
          throw new Error(t('mobile.groups.messagesCanBeUpTo64Kib'));
        return {body: {...body, payload: {thread_id: eventId, text: text.trim()}}};
      },
      beforeSubmit: async () => {
        const current = await freshRoom();
        if (
          (kind === 'name' && record(current.room).name !== room.name) ||
          (kind === 'context' &&
            (record(current.context).purpose !== context.purpose ||
              record(current.context).notes !== context.notes))
        )
          throw new Error(
            t('mobile.groups.theGroupDetailsChangedWhileYouWereEditing'),
          );
      },
      onSuccess: (value, input) => {
        const confirmation = record(value);
        const associated =
          kind === 'name' ? record(confirmation.room) : record(confirmation.event);
        if (
          associated.room_id !== roomId ||
          (kind === 'name' && associated.name !== input.body?.name) ||
          (kind === 'message' &&
            (confirmation.accepted !== true ||
              record(associated.payload).text !== record(input.body?.payload).text))
        )
          throw new Error(t('mobile.groups.theServerDidNotConfirmTheGroupAction'));
      },
    });
  }

  function lifecycle(entry: Entry, permanent: boolean) {
    const action = permanent
      ? t('mobile.groups.disbandGroup')
      : t('mobile.groups.stopGroupTasks');
    confirm(
      action,
      `${label}\n${permanent ? t('mobile.groups.theServerWillCloseTheGroupStopIts') : t('mobile.groups.theServerWillCancelQueuedAndRunningTasks')}`,
      async () => {
        await freshRoom();
        const reply = record(
          await invoke(
            entry,
            {body: {...scope, room_id: roomId, cancel_id: operationId('cancel')}},
            true,
          ),
        );
        if (permanent) {
          if (record(reply.tombstone).room_id !== roomId)
            throw new Error(t('mobile.groups.theServerDidNotConfirmThatTheGroup'));
          return t('mobile.groups.groupDisbanded');
        }
        if (typeof reply.cancelled !== 'number')
          throw new Error(t('mobile.groups.theServerDidNotConfirmThatTasksWere'));
        return t('mobile.groups.theServerCanceledTasks', {
          v1: String(reply.cancelled),
          count: Number(String(reply.cancelled)),
        });
      },
    );
  }

  if (createMode)
    return (
      <BusinessPage title={t('mobile.groups.newGroup')} onBack={onBack}>
        <ResourceStatus
          resource={capabilities}
          label={t('mobile.groups.profilesAvailableToJoinTheGroup')}
        />
        <Input
          accessibilityLabel={t('mobile.groups.groupName')}
          placeholder={t('mobile.groups.groupName')}
          value={name}
          onChangeText={setName}
          editable={!busy}
        />
        <Heading>{t('mobile.groups.select2To6MemberProfiles')}</Heading>
        {seatable.map((seat) => (
          <ListRow
            key={seat}
            title={seat}
            subtitle={
              seats.includes(seat)
                ? t('mobile.groups.selected')
                : t('mobile.groups.tapToAdd')
            }
            onPress={
              busy
                ? undefined
                : () =>
                    setSeats((current) =>
                      current.includes(seat)
                        ? current.filter((item) => item !== seat)
                        : current.length < 6
                          ? [...current, seat]
                          : current,
                    )
            }
          />
        ))}
        <Button
          label={t('mobile.groups.createGroup')}
          variant="accent"
          disabled={!create || busy || !name.trim() || seats.length < 2}
          onPress={createRoom}
        />
        {feedback ? <Notice>{feedback}</Notice> : null}
      </BusinessPage>
    );

  return (
    <BusinessPage
      title={roomId ? t('mobile.groups.groupDetails') : t('mobile.groups.groupChat')}
      onBack={onBack}
      onRefresh={refresh}
      loading={
        capabilities.loading ||
        roster.loading ||
        detail.loading ||
        log.loading ||
        busy
      }
    >
      <ResourceStatus
        resource={capabilities}
        label={t('mobile.groups.groupCapabilities')}
      />
      {feedback ? <Notice>{feedback}</Notice> : null}
      {!roomId ? (
        <>
          <View style={styles.actions}>
            {create &&
            available.persistent_process === true &&
            seatable.length >= 2 ? (
              <Button
                label={t('mobile.groups.newGroup')}
                variant="accent"
                onPress={onCreate}
              />
            ) : null}
            <Button
              label={
                includeDisbanded
                  ? t('mobile.groups.activeGroupsOnly')
                  : t('mobile.groups.includeDisbandedGroups')
              }
              onPress={() => {
                setOffset(0);
                setIncludeDisbanded(!includeDisbanded);
              }}
            />
            <Button
              label={t('mobile.groups.manageGroupsInTheWebapp')}
              onPress={onBrowser}
            />
          </View>
          <ResourceStatus resource={roster} label={t('mobile.groups.groups')} />
          {rows(roster.value, ['rooms']).map((item) => {
            const id = plain(item.room_id);
            return id ? (
              <ListRow
                key={id}
                title={plain(item.name) || id}
                subtitle={t('mobile.groups.members3', {
                  v1: String(rows(item.members, []).length),
                  v2: String(
                    item.disbanded_at != null ? t('mobile.groups.disbanded') : '',
                  ),
                  count: Number(String(rows(item.members, []).length)),
                })}
                onPress={() => onOpenRoom(id)}
              />
            ) : null;
          })}
          {!roster.loading &&
          !roster.error &&
          !rows(roster.value, ['rooms']).length ? (
            <Notice>{t('mobile.groups.noGroups')}</Notice>
          ) : null}
          {offset ? (
            <Button
              label={t('mobile.groups.previousPage')}
              onPress={() => setOffset(Math.max(0, offset - 30))}
            />
          ) : null}
          {typeof record(roster.value).next_offset === 'number' ? (
            <Button
              label={t('mobile.groups.nextPage')}
              onPress={() => setOffset(Number(record(roster.value).next_offset))}
            />
          ) : null}
        </>
      ) : (
        <>
          <ResourceStatus
            resource={detail}
            label={t('mobile.groups.groupDetails')}
          />
          {room.room_id === roomId ? (
            <>
              <Card>
                <Heading>{label}</Heading>
                <Fact
                  label={t('mobile.groups.groupStatus')}
                  value={
                    disbanded
                      ? t('mobile.groups.disbanded2')
                      : driver.working === true
                        ? t('mobile.groups.tasksRunning')
                        : driver.blocked === true
                          ? t('mobile.groups.needsAttention')
                          : t('mobile.groups.available')
                  }
                />
                <Fact
                  label={t('mobile.groups.groupPurpose')}
                  value={plain(context.purpose)}
                />
                <Fact
                  label={t('mobile.groups.sharedNotes')}
                  value={plain(context.notes)}
                />
                <Fact
                  label={t('mobile.groups.preparationError')}
                  value={plain(driver.last_error)}
                />
                <View style={styles.actions}>
                  {canWrite && available.driver === true && send ? (
                    <Button
                      variant="accent"
                      label={t('keybinds.actions.composer.send')}
                      disabled={busy}
                      onPress={() => openEditor(send, 'message')}
                    />
                  ) : null}
                  {canWrite && rename ? (
                    <Button
                      label={t('mobile.groups.renameGroup')}
                      disabled={busy}
                      onPress={() => openEditor(rename, 'name')}
                    />
                  ) : null}
                  {canWrite && editContext ? (
                    <Button
                      label={t('mobile.groups.editDescription')}
                      disabled={busy}
                      onPress={() => openEditor(editContext, 'context')}
                    />
                  ) : null}
                  {canWrite &&
                  stop &&
                  (driver.working === true || activeTasks.length) ? (
                    <Button
                      label={t('mobile.groups.stopTasks')}
                      disabled={busy}
                      onPress={() => lifecycle(stop, false)}
                    />
                  ) : null}
                  {canWrite && disband ? (
                    <Button
                      label={t('mobile.groups.disbandGroup')}
                      disabled={busy}
                      onPress={() => lifecycle(disband, true)}
                    />
                  ) : null}
                  <Button
                    label={t('mobile.groups.openWebappChatHome')}
                    onPress={onBrowser}
                  />
                </View>
              </Card>
              <Heading>{t('mobile.groups.member')}</Heading>
              {members.map((member, index) => {
                const id = plain(member.member_id);
                const tasks = activeTasks.filter((task) => task.member_id === id);
                const peer = rows(driver.peer_routes, []).find(
                  (item) => item.member_id === id,
                );
                return (
                  <ListRow
                    key={id || String(index)}
                    title={plain(member.display_name) || plain(member.profile) || id}
                    subtitle={[
                      plain(member.handle) ? `@${plain(member.handle)}` : '',
                      tasks
                        .map(
                          (task) =>
                            activityNames[plain(task.status)] || plain(task.status),
                        )
                        .filter(Boolean)
                        .join('、') ||
                        plain(peer?.status) ||
                        (driver.running === true ? t('sidebar.filter.idle') : ''),
                    ]
                      .filter(Boolean)
                      .join(' · ')}
                  />
                );
              })}
              <Heading>{t('mobile.groups.groupHistory')}</Heading>
              <ResourceStatus
                resource={log}
                label={t('mobile.groups.groupHistory')}
              />
              {rows(log.value, ['events'])
                .filter(
                  (event) =>
                    plain(event.kind).startsWith('message.') ||
                    Object.prototype.hasOwnProperty.call(
                      eventNames,
                      plain(event.kind),
                    ),
                )
                .map((event) => {
                  const payload = record(event.payload);
                  const actor = record(event.actor);
                  const kind = plain(event.kind);
                  const title =
                    plain(actor.display_name) ||
                    plain(actor.profile) ||
                    eventNames[kind] ||
                    kind;
                  return (
                    <Card key={plain(event.event_id) || textValue(event.seq)}>
                      <Fact
                        label={title}
                        value={
                          plain(payload.text) ||
                          plain(payload.name) ||
                          eventNames[kind] ||
                          kind
                        }
                      />
                    </Card>
                  );
                })}
              {page.has_more === true && typeof page.cursor === 'number' ? (
                <Button
                  label={t('mobile.groups.nextPageOfHistory')}
                  disabled={log.loading}
                  onPress={() => setLogCursor(Number(page.cursor))}
                />
              ) : null}
              {(logCursor ?? Math.max(0, latest - 25)) > 0 ? (
                <Button
                  label={t('mobile.groups.viewEarlierHistory')}
                  disabled={log.loading}
                  onPress={() =>
                    setLogCursor(Math.max(0, (logCursor ?? latest - 25) - 25))
                  }
                />
              ) : null}
              {logCursor !== undefined ? (
                <Button
                  label={t('mobile.groups.backToRecentHistory')}
                  disabled={log.loading}
                  onPress={() => setLogCursor(undefined)}
                />
              ) : null}
            </>
          ) : null}
        </>
      )}
    </BusinessPage>
  );
}
